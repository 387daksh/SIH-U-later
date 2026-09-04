package com.sih.speechtest

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Base64
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/** Whisper-tiny (sherpa-onnx int8 export): mel spectrogram -> encoder -> KV-cache decoder loop.
 * Feature recipe validated against the HF whisper-tiny checkpoint offline before porting here. */
class WhisperAsr(env: OrtEnvironment, dir: String) {
    companion object {
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 80
        const val SOT = 50258
        const val EOT = 50257
        const val LANG_EN = 50259
        const val TASK_TRANSCRIBE = 50359
        const val NO_TIMESTAMPS = 50363
        const val MAX_NEW_TOKENS = 60
    }

    private val env = env
    private val encoder = env.createSession("$dir/encoder.onnx", OrtSession.SessionOptions())
    private val decoder = env.createSession("$dir/decoder.onnx", OrtSession.SessionOptions())
    private val idToBytes: Map<Int, ByteArray>
    private val melFilters: Array<FloatArray>  // [80][201]
    private val hannWindow: FloatArray

    init {
        val map = mutableMapOf<Int, ByteArray>()
        File("$dir/tokens.txt").forEachLine { line ->
            val parts = line.trim().split(" ")
            if (parts.size >= 2) {
                val id = parts.last().toIntOrNull()
                if (id != null) {
                    map[id] = try {
                        Base64.decode(parts[0], Base64.DEFAULT)
                    } catch (e: Exception) {
                        parts[0].toByteArray(Charsets.UTF_8)
                    }
                }
            }
        }
        idToBytes = map

        val bytes = File("$dir/mel_filters_80x201.bin").readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        melFilters = Array(N_MELS) { m -> FloatArray(201) { f -> buf.get(m * 201 + f) } }

        hannWindow = FloatArray(N_FFT) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)).toFloat() }
    }

    // Precomputed windowed DFT basis (cos/sin per freq bin per sample) -- avoids ~10^8
    // transcendental calls per clip; built once, reused as a plain multiply-accumulate.
    private val nFreq = N_FFT / 2 + 1
    private val cosBasis: Array<FloatArray> by lazy {
        Array(nFreq) { k -> FloatArray(N_FFT) { n -> (cos(-2.0 * PI * k * n / N_FFT) * hannWindow[n]).toFloat() } }
    }
    private val sinBasis: Array<FloatArray> by lazy {
        Array(nFreq) { k -> FloatArray(N_FFT) { n -> (kotlin.math.sin(-2.0 * PI * k * n / N_FFT) * hannWindow[n]).toFloat() } }
    }

    data class Result(val text: String, val confidence: Float)

    private fun logMelSpectrogram(waveform: FloatArray): Array<FloatArray> {
        val padded = FloatArray(waveform.size + N_FFT)
        System.arraycopy(waveform, 0, padded, N_FFT / 2, waveform.size)
        // reflect padding at both ends, matching torch.stft(center=True, pad_mode="reflect")
        for (i in 0 until N_FFT / 2) {
            padded[N_FFT / 2 - 1 - i] = waveform.getOrElse(i + 1) { 0f }
            padded[N_FFT / 2 + waveform.size + i] = waveform.getOrElse(waveform.size - 2 - i) { 0f }
        }
        val numFrames = (padded.size - N_FFT) / HOP + 1
        val power = Array(numFrames) { FloatArray(nFreq) }
        for (t in 0 until numFrames) {
            val base = t * HOP
            for (k in 0 until nFreq) {
                var re = 0f
                var im = 0f
                val cosRow = cosBasis[k]
                val sinRow = sinBasis[k]
                for (n in 0 until N_FFT) {
                    val sample = padded[base + n]
                    re += sample * cosRow[n]
                    im += sample * sinRow[n]
                }
                power[t][k] = re * re + im * im
            }
        }
        // whisper drops the last STFT frame (torch's stft[..., :-1] convention)
        val usedFrames = numFrames - 1
        val mel = Array(N_MELS) { FloatArray(usedFrames) }
        var maxVal = Float.NEGATIVE_INFINITY
        for (m in 0 until N_MELS) {
            for (t in 0 until usedFrames) {
                var sum = 0f
                for (k in 0 until nFreq) sum += melFilters[m][k] * power[t][k]
                val logVal = log10(max(sum, 1e-10f))
                mel[m][t] = logVal
                if (logVal > maxVal) maxVal = logVal
            }
        }
        for (m in 0 until N_MELS) for (t in 0 until usedFrames) {
            val clamped = max(mel[m][t], maxVal - 8.0f)
            mel[m][t] = (clamped + 4.0f) / 4.0f
        }
        return mel
    }

    fun transcribe(waveform: FloatArray): Result {
        val mel = logMelSpectrogram(waveform)
        val timeSteps = mel[0].size
        val flat = FloatArray(N_MELS * timeSteps)
        var idx = 0
        for (m in 0 until N_MELS) for (t in 0 until timeSteps) flat[idx++] = mel[m][t]
        val melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, N_MELS.toLong(), timeSteps.toLong()))

        val (crossK, crossV, crossShape) = encoder.run(mapOf("mel" to melTensor)).use { out ->
            val k = out[0].value
            val v = out[1].value
            val shape = (out[0] as OnnxTensor).info.shape
            Triple(flattenNested4D(k, shape), flattenNested4D(v, shape), shape)
        }
        melTensor.close()

        var selfK = FloatArray(4 * 448 * 384)
        var selfV = FloatArray(4 * 448 * 384)
        val crossTLen = crossShape[2].toInt()

        var tokens = longArrayOf(SOT.toLong(), LANG_EN.toLong(), TASK_TRANSCRIBE.toLong(), NO_TIMESTAMPS.toLong())
        var offset = 0L
        val generated = ArrayList<Int>()
        var nextToken: Int

        val step1 = runDecoderStep(tokens, selfK, selfV, crossK, crossV, crossTLen, offset)
        nextToken = step1.first
        selfK = step1.second
        selfV = step1.third
        offset = tokens.size.toLong()

        var steps = 0
        while (nextToken != EOT && steps < MAX_NEW_TOKENS) {
            generated.add(nextToken)
            val stepResult = runDecoderStep(longArrayOf(nextToken.toLong()), selfK, selfV, crossK, crossV, crossTLen, offset)
            nextToken = stepResult.first
            selfK = stepResult.second
            selfV = stepResult.third
            offset += 1
            steps += 1
            if (generated.size >= 6) {
                val n = generated.size
                if (generated.subList(n - 3, n) == generated.subList(n - 6, n - 3)) break
            }
        }

        val bytes = java.io.ByteArrayOutputStream()
        for (t in generated) idToBytes[t]?.let { bytes.write(it) }
        val text = bytes.toByteArray().toString(Charsets.UTF_8).trim()
        return Result(text, if (generated.isEmpty()) 0f else 1f)
    }

    private fun flattenNested4D(value: Any, shape: LongArray): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val arr = value as Array<Array<Array<FloatArray>>>
        val d0 = shape[0].toInt(); val d1 = shape[1].toInt(); val d2 = shape[2].toInt(); val d3 = shape[3].toInt()
        val flat = FloatArray(d0 * d1 * d2 * d3)
        var idx = 0
        for (a in 0 until d0) for (b in 0 until d1) for (c in 0 until d2) for (d in 0 until d3) flat[idx++] = arr[a][b][c][d]
        return flat
    }

    private fun runDecoderStep(
        tokens: LongArray, selfK: FloatArray, selfV: FloatArray,
        crossK: FloatArray, crossV: FloatArray, crossTLen: Int, offset: Long,
    ): Triple<Int, FloatArray, FloatArray> {
        val tokensTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong()))
        val selfKTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(selfK), longArrayOf(4, 1, 448, 384))
        val selfVTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(selfV), longArrayOf(4, 1, 448, 384))
        val crossKTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(crossK), longArrayOf(4, 1, crossTLen.toLong(), 384))
        val crossVTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(crossV), longArrayOf(4, 1, crossTLen.toLong(), 384))
        val offsetTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(offset)), longArrayOf(1))

        val inputs = mapOf(
            "tokens" to tokensTensor, "in_n_layer_self_k_cache" to selfKTensor,
            "in_n_layer_self_v_cache" to selfVTensor, "n_layer_cross_k" to crossKTensor,
            "n_layer_cross_v" to crossVTensor, "offset" to offsetTensor,
        )
        try {
            decoder.run(inputs).use { out ->
                val logits = out[0].value as Array<Array<FloatArray>>
                val lastStep = logits[0][logits[0].size - 1]
                var best = 0
                for (i in lastStep.indices) if (lastStep[i] > lastStep[best]) best = i
                val newSelfK = flattenNested4D(out[1].value, (out[1] as OnnxTensor).info.shape)
                val newSelfV = flattenNested4D(out[2].value, (out[2] as OnnxTensor).info.shape)
                return Triple(best, newSelfK, newSelfV)
            }
        } finally {
            tokensTensor.close(); selfKTensor.close(); selfVTensor.close()
            crossKTensor.close(); crossVTensor.close(); offsetTensor.close()
        }
    }
}
