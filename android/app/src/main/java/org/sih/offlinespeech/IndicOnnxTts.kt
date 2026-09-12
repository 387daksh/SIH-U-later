package org.sih.offlinespeech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale

internal class IndicTtsTextProcessor(vocab: List<String>, private val maxChunkChars: Int) {
    private val ids = vocab.mapIndexedNotNull { id, token ->
        token.singleOrNull()?.let { it to id.toLong() }
    }.toMap()

    fun tokens(text: String): LongArray = normalise(text).mapNotNull(ids::get).toLongArray()

    fun prosodyInputs(text: String, emphasizedWord: String?, bias: Float): Triple<LongArray, FloatArray, FloatArray> {
        val clean = normalise(text).let { value ->
            if (value.lastOrNull()?.let { it in ".!?" } == true || '.' !in ids) value else "$value."
        }
        val tokens = clean.mapNotNull(ids::get).toLongArray()
        val pitchBias = FloatArray(tokens.size)
        val target = normalise(emphasizedWord.orEmpty()).trim().takeIf { it.isNotEmpty() && ' ' !in it }
        if (target != null) {
            var cursor = 0
            clean.split(' ').firstOrNull { part ->
                val word = part.trimEnd('.', '!', '?', ',', ';', ':')
                if (word == target) {
                    repeat(word.length) { pitchBias[cursor + it] = bias }
                    true
                } else {
                    cursor += part.length + 1
                    false
                }
            }
        }
        return Triple(tokens, pitchBias, FloatArray(tokens.size) { 1f })
    }

    fun chunks(text: String): List<String> {
        val clean = normalise(text)
        if (clean.isBlank()) return emptyList()
        val clauses = clean.split(Regex("(?<=[।.!?,;:])\\s+")).filter(String::isNotBlank)
        return clauses.flatMap { clause ->
            if (clause.length <= maxChunkChars) listOf(clause)
            else {
                val out = ArrayList<String>()
                var current = ""
                clause.split(Regex("\\s+")).forEach { word ->
                    if (current.isNotEmpty() && current.length + word.length + 1 > maxChunkChars) {
                        out += current
                        current = word
                    } else current = if (current.isEmpty()) word else "$current $word"
                }
                if (current.isNotEmpty()) out += current
                out
            }
        }
    }

    private fun normalise(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .map { if (it.isWhitespace()) ' ' else it }
        .filter { it == ' ' || ids.containsKey(it) }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()
}

internal class IndicOnnxTts(root: File) : Closeable {
    // Pooled pitch_calibration.json interpolation for a +25 Hz target.
    companion object { const val EMPHASIS_BIAS = 0.3996795f }

    private val env = OrtEnvironment.getEnvironment()
    private val config = JSONObject(File(root, "meta.json").readText())
    private val fastPitch = session(File(root, "fastpitch.onnx"))
    private val hiFiGan = session(File(root, "hifigan.onnx"))
    val supportsPitchConditioning = "pitch_bias" in fastPitch.inputNames && "duration_scale" in fastPitch.inputNames
    private val speakerId = config.getInt("speaker_id").toLong()
    private val lengthScale = config.optDouble("length_scale_default", 0.9).toFloat()
    val sampleRate = config.getInt("sample_rate")
    val chunkGapFrames = sampleRate * config.optInt("chunk_gap_ms", 60) / 1_000
    private val text = IndicTtsTextProcessor(
        config.getJSONArray("vocab").let { array -> List(array.length()) { array.getString(it) } },
        config.optInt("max_chunk_chars", 70),
    )

    fun chunks(value: String): List<String> = text.chunks(value)

    fun synthesize(chunk: String, emphasizedWord: String? = null): FloatArray {
        val (ids, pitchValues, durationValues) = text.prosodyInputs(chunk, emphasizedWord, EMPHASIS_BIAS)
        require(ids.isNotEmpty()) { "TTS text has no supported characters" }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tokens ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speakerId)), longArrayOf(1)).use { speaker ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf(1)).use { scale ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchValues), longArrayOf(1, ids.size.toLong())).use { pitch ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(durationValues), longArrayOf(1, ids.size.toLong())).use { duration ->
                            val inputs = mutableMapOf<String, OnnxTensor>(
                                "tokens" to tokens, "speaker_id" to speaker, "length_scale" to scale,
                            )
                            if (supportsPitchConditioning) {
                                inputs["pitch_bias"] = pitch
                                inputs["duration_scale"] = duration
                            }
                            fastPitch.run(inputs).use { mel ->
                                hiFiGan.run(mapOf("mel" to mel[0] as OnnxTensor)).use { result ->
                                    val buffer = (result[0] as OnnxTensor).floatBuffer
                                    return FloatArray(buffer.remaining()).also(buffer::get)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        hiFiGan.close()
        fastPitch.close()
    }

    private fun session(file: File): OrtSession {
        require(file.isFile) { "Missing ${file.path}" }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(2)
            env.createSession(file.path, options)
        }
    }
}
