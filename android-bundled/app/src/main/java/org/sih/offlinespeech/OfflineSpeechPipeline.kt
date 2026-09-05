package org.sih.offlinespeech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64
import kotlin.math.exp

data class SpeechResult(val language: String, val text: String, val backend: String, val milliseconds: Long)
enum class RoutingMode { AUTO, ENGLISH, INDIC }

fun validateModelBundle(root: File) {
    val manifest = JSONObject(File(root, "manifest.json").also { require(it.isFile) { "Missing model manifest" } }.readText())
    val files = manifest.getJSONObject("files")
    files.keys().forEach { relative ->
        require(".." !in relative) { "Unsafe model path: $relative" }
        val file = File(root, relative)
        require(file.isFile && file.length() == files.getJSONObject(relative).getLong("bytes")) {
            "Missing or incomplete model: $relative"
        }
    }
}

class OfflineSpeechPipeline(private val root: File) : Closeable {
    init { validateModelBundle(root) }
    private val environment = OrtEnvironment.getEnvironment()
    private var whisper: WhisperRuntime? = null
    private var indic: IndicRuntime? = null

    fun transcribe(audio: FloatArray, mode: RoutingMode = RoutingMode.AUTO): SpeechResult {
        require(audio.size >= 1_600) { "Recording is shorter than 0.1 seconds" }
        val started = System.nanoTime()
        val answer = when (mode) {
            RoutingMode.AUTO -> whisper().transcribeEnglish(audio, verifyLanguage = true) ?: indic().transcribe(audio)
            RoutingMode.ENGLISH -> {
                indic?.close(); indic = null
                requireNotNull(whisper().transcribeEnglish(audio, verifyLanguage = false))
            }
            RoutingMode.INDIC -> {
                whisper?.close(); whisper = null
                indic().transcribe(audio)
            }
        }
        return answer.copy(milliseconds = (System.nanoTime() - started) / 1_000_000)
    }

    private fun whisper() = whisper ?: WhisperRuntime(environment, File(root, "whisper")).also { whisper = it }
    private fun indic() = indic ?: IndicRuntime(environment, File(root, "indic")).also { indic = it }

    override fun close() { indic?.close(); whisper?.close() }
}

private class WhisperRuntime(private val env: OrtEnvironment, root: File) : Closeable {
    private val config = JSONObject(File(root, "config.json").readText())
    private val preprocessor = session(File(root, "preprocessor.onnx"))
    private val encoder = session(File(root, "encoder.onnx"))
    private val decoder = session(File(root, "decoder.onnx"))
    private val sot = config.getInt("sot")
    private val eot = config.getInt("eot")
    private val transcribe = config.getInt("transcribe")
    private val noTimestamps = config.getInt("no_timestamps")
    private val noSpeech = config.getInt("no_speech")
    private val translate = config.getInt("translate")
    private val blank = config.getInt("blank_id")
    private val context = config.getInt("n_text_ctx")
    private val layers = config.getInt("n_text_layer")
    private val state = config.getInt("n_text_state")
    private val englishToken = config.getJSONObject("language_tokens").getInt("en")
    private val languageTokens = config.getJSONObject("language_tokens").let { json ->
        IntArray(json.length()).also { values -> json.keys().asSequence().forEachIndexed { i, key -> values[i] = json.getInt(key) } }
    }
    private val tokens = File(root, "tokens.txt").readLines().associate { line ->
        val (encoded, id) = line.split(' ', limit = 2)
        id.toInt() to if (encoded == "=") byteArrayOf() else Base64.getDecoder().decode(encoded)
    }

    fun transcribeEnglish(audio: FloatArray, verifyLanguage: Boolean): SpeechResult? {
        val input = audio.copyOf(minOf(audio.size, 480_000))
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { waveform ->
            preprocessor.run(mapOf("waveform" to waveform)).use { front ->
                encoder.run(mapOf("mel" to front[0] as OnnxTensor)).use { encoded ->
                    val crossK = encoded[0] as OnnxTensor
                    val crossV = encoded[1] as OnnxTensor
                    if (verifyLanguage && englishProbability(crossK, crossV) < config.getDouble("english_threshold")) return null
                    return SpeechResult("english", decodeEnglish(crossK, crossV), "whisper-tiny-int8", 0)
                }
            }
        }
    }

    private fun englishProbability(crossK: OnnxTensor, crossV: OnnxTensor): Double {
        val zeroK = zeroCache(); val zeroV = zeroCache()
        val result = runDecoder(longArrayOf(sot.toLong()), 0, zeroK, zeroV, crossK, crossV)
        zeroK.close(); zeroV.close()
        result.use {
            val logits = it[0] as OnnxTensor
            val data = logits.floatBuffer
            val width = logits.info.shape.last().toInt()
            val start = (logits.info.shape[1].toInt() - 1) * width
            val maximum = languageTokens.maxOf { token -> data.get(start + token).toDouble() }
            val denominator = languageTokens.sumOf { token -> exp(data.get(start + token) - maximum) }
            return exp(data.get(start + englishToken) - maximum) / denominator
        }
    }

    private fun decodeEnglish(crossK: OnnxTensor, crossV: OnnxTensor): String {
        val zeroK = zeroCache(); val zeroV = zeroCache()
        var owner = runDecoder(longArrayOf(sot.toLong(), englishToken.toLong(), transcribe.toLong(),
            noTimestamps.toLong()), 0, zeroK, zeroV, crossK, crossV)
        zeroK.close(); zeroV.close()
        val generated = ArrayList<Int>()
        var offset = 4L
        while (generated.size < context - 4) {
            val token = bestToken(owner, generated.isEmpty())
            if (token == eot) break
            generated += token
            val old = owner
            owner = runDecoder(longArrayOf(token.toLong()), offset++, old[1] as OnnxTensor,
                old[2] as OnnxTensor, crossK, crossV)
            old.close()
        }
        owner.close()
        val bytes = ByteArrayOutputStream()
        generated.forEach { tokens[it]?.let { value -> bytes.write(value) } }
        return bytes.toByteArray().toString(Charsets.UTF_8).trim()
    }

    private fun bestToken(result: OrtSession.Result, first: Boolean): Int {
        val logits = result[0] as OnnxTensor
        val data = logits.floatBuffer
        val width = logits.info.shape.last().toInt()
        val start = (logits.info.shape[1].toInt() - 1) * width
        var best = eot; var bestScore = Float.NEGATIVE_INFINITY
        for (token in 0 until width) {
            if (token == noTimestamps || token == sot || token == noSpeech || token == translate ||
                first && (token == blank || token == eot)) continue
            val score = data.get(start + token)
            if (score > bestScore) { best = token; bestScore = score }
        }
        return best
    }

    private fun runDecoder(tokenIds: LongArray, offsetValue: Long, cacheK: OnnxTensor,
                           cacheV: OnnxTensor, crossK: OnnxTensor, crossV: OnnxTensor): OrtSession.Result {
        OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, tokenIds.size.toLong())).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(offsetValue)), longArrayOf(1)).use { offset ->
                return decoder.run(mapOf("tokens" to input, "in_n_layer_self_k_cache" to cacheK,
                    "in_n_layer_self_v_cache" to cacheV, "n_layer_cross_k" to crossK,
                    "n_layer_cross_v" to crossV, "offset" to offset))
            }
        }
    }

    private fun zeroCache() = OnnxTensor.createTensor(env,
        FloatBuffer.wrap(FloatArray(layers * context * state)),
        longArrayOf(layers.toLong(), 1, context.toLong(), state.toLong()))

    override fun close() { decoder.close(); encoder.close(); preprocessor.close() }

    private fun session(file: File): OrtSession {
        require(file.isFile) { "Missing ${file.path}" }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(file.path, options)
        }
    }
}

private class IndicRuntime(private val env: OrtEnvironment, root: File) : Closeable {
    private val preprocessor = session(File(root, "preprocessor.onnx"))
    private val encoder = session(File(root, "encoder.onnx"))
    private val decoder = session(File(root, "ctc_decoder.onnx"))
    private val blank = JSONObject(File(root, "config.json").readText()).optInt("BLANK_ID", 256)
    private val languages = linkedMapOf("hindi" to "hi", "gujarati" to "gu", "marathi" to "mr",
        "kannada" to "kn", "malayalam" to "ml", "tamil" to "ta", "telugu" to "te",
        "odia" to "or", "bengali" to "bn")
    private val masks = JSONObject(File(root, "language_masks.json").readText()).let { all ->
        languages.values.associateWith { code ->
            all.getJSONArray(code).let { mask -> (0 until mask.length()).filter { mask.getBoolean(it) }.toIntArray() }
        }
    }
    private val vocab = JSONObject(File(root, "vocab.json").readText()).let { all ->
        languages.values.associateWith { code ->
            all.getJSONArray(code).let { words -> Array(words.length()) { words.getString(it) } }
        }
    }

    fun transcribe(audio: FloatArray): SpeechResult {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { signal ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(audio.size.toLong())), longArrayOf(1)).use { length ->
                preprocessor.run(mapOf("input_signal" to signal, "length" to length)).use { front ->
                    encoder.run(mapOf("audio_signal" to front[0] as OnnxTensor,
                        "length" to front[1] as OnnxTensor)).use { encoded ->
                        val frames = (encoded[1] as OnnxTensor).longBuffer.get(0).toInt()
                        decoder.run(mapOf("encoder_output" to encoded[0] as OnnxTensor)).use { decoded ->
                            return vote(decoded[0] as OnnxTensor, frames)
                        }
                    }
                }
            }
        }
    }

    private fun vote(output: OnnxTensor, frames: Int): SpeechResult {
        val shape = output.info.shape
        val width = shape[2].toInt()
        val count = minOf(frames, shape[1].toInt())
        val logits = output.floatBuffer
        val normalizers = DoubleArray(count)
        repeat(count) { frame ->
            val base = frame * width
            var maximum = Float.NEGATIVE_INFINITY
            repeat(width) { maximum = maxOf(maximum, logits.get(base + it)) }
            var sum = 0.0
            repeat(width) { sum += exp(logits.get(base + it) - maximum) }
            normalizers[frame] = maximum + kotlin.math.ln(sum)
        }
        var winner: SpeechResult? = null; var winnerConfidence = -1.0
        for ((language, code) in languages) {
            val indices = masks.getValue(code)
            val path = IntArray(count)
            repeat(count) { frame ->
                val base = frame * width
                var best = 0; var score = Float.NEGATIVE_INFINITY
                indices.forEachIndexed { local, global ->
                    val candidate = logits.get(base + global)
                    if (candidate > score) { best = local; score = candidate }
                }
                path[frame] = best
            }
            val text = StringBuilder(); var confidence = 0.0; var tokenCount = 0; var start = 0
            while (start < count) {
                var end = start + 1
                while (end < count && path[end] == path[start]) end++
                val local = path[start]
                if (local != blank) {
                    text.append(vocab.getValue(code)[local]); tokenCount++
                    val global = indices[local]
                    var bestPosterior = 0.0
                    for (frame in start until end)
                        bestPosterior = maxOf(bestPosterior, exp(logits.get(frame * width + global) - normalizers[frame]))
                    confidence += bestPosterior
                }
                start = end
            }
            val average = if (tokenCount == 0) 0.0 else confidence / tokenCount
            if (average > winnerConfidence) {
                winnerConfidence = average
                winner = SpeechResult(language, text.toString().replace("▁", " ").trim(),
                    "indic-conformer-600m-hybrid-int8", 0)
            }
        }
        return requireNotNull(winner)
    }

    override fun close() { decoder.close(); encoder.close(); preprocessor.close() }

    private fun session(file: File): OrtSession {
        require(file.isFile) { "Missing ${file.path}" }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(file.path, options)
        }
    }
}
