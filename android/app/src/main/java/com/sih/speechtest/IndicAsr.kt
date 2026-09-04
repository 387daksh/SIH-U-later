package com.sih.speechtest

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/** Ports sih_speech.pipeline.IndicASR.transcribe_all: shared-encoder CTC decode,
 * 9-way parallel per-language masking, pick the highest-confidence language. */
class IndicAsr(env: OrtEnvironment, dir: String) {
    private val env = env
    private val preprocessor = env.createSession("$dir/preprocessor.onnx", OrtSession.SessionOptions())
    private val encoder = env.createSession("$dir/encoder.onnx", OrtSession.SessionOptions())
    private val ctcDecoder = env.createSession("$dir/ctc_decoder.onnx", OrtSession.SessionOptions())

    private val vocab: Map<String, List<String>>
    private val languageMasks: Map<String, BooleanArray>
    private val languages = listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
    private val blankId = 256

    init {
        val vocabJson = JSONObject(File("$dir/vocab.json").readText())
        val masksJson = JSONObject(File("$dir/language_masks.json").readText())
        val vocabMap = mutableMapOf<String, List<String>>()
        val maskMap = mutableMapOf<String, BooleanArray>()
        for (lang in languages) {
            val arr = vocabJson.getJSONArray(lang)
            vocabMap[lang] = (0 until arr.length()).map { arr.getString(it) }
            val maskArr = masksJson.getJSONArray(lang)
            maskMap[lang] = BooleanArray(maskArr.length()) { maskArr.getBoolean(it) }
        }
        vocab = vocabMap
        languageMasks = maskMap
    }

    data class Candidate(val language: String, val text: String, val confidence: Float)
    data class Result(val winner: Candidate, val candidates: List<Candidate>, val encoderMs: Long, val decoderMs: Long)

    fun transcribeAll(waveform: FloatArray): Result {
        val inputSignal = OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), longArrayOf(1, waveform.size.toLong()))
        val length = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(waveform.size.toLong())), longArrayOf(1))

        val (features, featLength) = preprocessor.run(mapOf("input_signal" to inputSignal, "length" to length)).use { out ->
            Pair(out[0].value as Array<Array<FloatArray>>, (out[1].value as LongArray))
        }
        inputSignal.close(); length.close()

        val timeSteps = features[0][0].size
        val nMels = features[0].size
        val flat = FloatArray(nMels * timeSteps)
        var idx = 0
        for (m in 0 until nMels) for (t in 0 until timeSteps) flat[idx++] = features[0][m][t]

        val audioSignal = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, nMels.toLong(), timeSteps.toLong()))
        val lengthTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(featLength), longArrayOf(1))

        val encStart = System.currentTimeMillis()
        // encoder output "outputs" is channel-first: [1, 1024, T] (not [1, T, 1024]).
        val (encoderOut, encodedLen, encDim) = encoder.run(mapOf("audio_signal" to audioSignal, "length" to lengthTensor)).use { out ->
            val outputs = out[0].value as Array<Array<FloatArray>>
            val encLen = (out[1].value as LongArray)[0].toInt()
            Triple(outputs[0], encLen, outputs[0].size)
        }
        val encoderMs = System.currentTimeMillis() - encStart
        audioSignal.close(); lengthTensor.close()

        val frames = minOf(encoderOut[0].size, encodedLen)
        val encFlat = FloatArray(encDim * frames)
        idx = 0
        for (d in 0 until encDim) for (t in 0 until frames) encFlat[idx++] = encoderOut[d][t]
        val encoderTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(encFlat), longArrayOf(1, encDim.toLong(), frames.toLong()))

        val decStart = System.currentTimeMillis()
        val logits = ctcDecoder.run(mapOf("encoder_output" to encoderTensor)).use { out ->
            (out[0].value as Array<Array<FloatArray>>)[0]
        }
        val decoderMs = System.currentTimeMillis() - decStart
        encoderTensor.close()

        val candidates = languages.map { lang -> decodeLanguage(logits, lang) }
        val winner = candidates.maxByOrNull { it.confidence }!!
        return Result(winner, candidates, encoderMs, decoderMs)
    }

    private fun decodeLanguage(logits: Array<FloatArray>, lang: String): Candidate {
        val mask = languageMasks[lang]!!
        val globalIndices = ArrayList<Int>()
        for (i in mask.indices) if (mask[i]) globalIndices.add(i)
        val localVocab = vocab[lang]!!

        val frames = logits.size
        val path = IntArray(frames)
        val frameConfidence = FloatArray(frames)
        for (t in 0 until frames) {
            var maxVal = Float.NEGATIVE_INFINITY
            var maxLocal = 0
            // log-softmax over the masked subset, done via max-subtraction for numerical stability
            var localMax = Float.NEGATIVE_INFINITY
            for (li in globalIndices.indices) {
                val v = logits[t][globalIndices[li]]
                if (v > localMax) localMax = v
            }
            var sumExp = 0.0
            for (li in globalIndices.indices) {
                sumExp += exp((logits[t][globalIndices[li]] - localMax).toDouble())
            }
            val logSumExp = localMax + ln(sumExp).toFloat()
            for (li in globalIndices.indices) {
                val logProb = logits[t][globalIndices[li]] - logSumExp
                if (logProb > maxVal) { maxVal = logProb; maxLocal = li }
            }
            path[t] = maxLocal
            frameConfidence[t] = exp(maxVal.toDouble()).toFloat()
        }

        val tokens = StringBuilder()
        val confidences = ArrayList<Float>()
        var t = 0
        while (t < frames) {
            val id = path[t]
            var end = t + 1
            while (end < frames && path[end] == id) end++
            if (id != blankId) {
                tokens.append(localVocab.getOrElse(id) { "" })
                var segMax = 0f
                for (k in t until end) if (frameConfidence[k] > segMax) segMax = frameConfidence[k]
                confidences.add(segMax)
            }
            t = end
        }
        val text = tokens.toString().replace("▁", " ").trim()
        val confidence = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
        return Candidate(lang, text, confidence)
    }
}
