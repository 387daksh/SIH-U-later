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
    private val env = OrtEnvironment.getEnvironment()
    private val config = JSONObject(File(root, "meta.json").readText())
    private val fastPitch = session(File(root, "fastpitch.onnx"))
    private val hiFiGan = session(File(root, "hifigan.onnx"))
    private val speakerId = config.getInt("speaker_id").toLong()
    private val lengthScale = config.optDouble("length_scale_default", 0.9).toFloat()
    val sampleRate = config.getInt("sample_rate")
    val chunkGapFrames = sampleRate * config.optInt("chunk_gap_ms", 60) / 1_000
    private val text = IndicTtsTextProcessor(
        config.getJSONArray("vocab").let { array -> List(array.length()) { array.getString(it) } },
        config.optInt("max_chunk_chars", 70),
    )

    fun chunks(value: String): List<String> = text.chunks(value)

    fun synthesize(chunk: String): FloatArray {
        val ids = text.tokens(chunk)
        require(ids.isNotEmpty()) { "TTS text has no supported characters" }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tokens ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speakerId)), longArrayOf(1)).use { speaker ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf(1)).use { scale ->
                    fastPitch.run(mapOf("tokens" to tokens, "speaker_id" to speaker, "length_scale" to scale)).use { mel ->
                        hiFiGan.run(mapOf("mel" to mel[0] as OnnxTensor)).use { result ->
                            val buffer = (result[0] as OnnxTensor).floatBuffer
                            return FloatArray(buffer.remaining()).also(buffer::get)
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
