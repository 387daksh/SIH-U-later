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

internal data class IndicVoiceConfig(
    val code: String,
    val modelFile: File,
    val speakerId: Long,
    val lengthScale: Float,
    val sampleRate: Int,
    val chunkGapFrames: Int,
    val textProcessor: IndicTtsTextProcessor,
)

internal class IndicOnnxTts(root: File) : Closeable {
    // Pooled pitch_calibration.json interpolation for a +25 Hz target.
    companion object {
        const val EMPHASIS_BIAS = 0.3996795f
        const val MAX_LOADED_FASTPITCH = 1

        fun normalizeLanguage(language: String): String = when (language.lowercase(Locale.ROOT).trim()) {
            "english", "en", "hindi", "hi", "en-hi", "en+hi" -> "en-hi"
            "gujarati", "gu" -> "gu"
            "marathi", "mr" -> "mr"
            "kannada", "kn" -> "kn"
            "malayalam", "ml" -> "ml"
            "tamil", "ta" -> "ta"
            "telugu", "te" -> "te"
            "odia", "or" -> "or"
            "bengali", "bn" -> "bn"
            else -> language.lowercase(Locale.ROOT).trim()
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val hiFiGan: OrtSession
    private val voices = HashMap<String, IndicVoiceConfig>()
    private val fastPitchSessions = LinkedHashMap<String, OrtSession>(4, 0.75f, true)
    private val supportsPitchMap = HashMap<String, Boolean>()
    private val defaultVoiceCode: String

    val supportsPitchConditioning: Boolean
        get() = synchronized(fastPitchSessions) {
            val voice = voices[defaultVoiceCode] ?: return false
            getFastPitch(voice)
            supportsPitchMap[voice.code] ?: false
        }

    val sampleRate: Int
    val chunkGapFrames: Int

    init {
        require(root.exists()) { "TTS root not found: ${root.path}" }
        val sharedVocoder = when {
            File(root, "shared/hifigan.onnx").isFile -> File(root, "shared/hifigan.onnx")
            File(root, "hifigan.onnx").isFile -> File(root, "hifigan.onnx")
            else -> error("HiFi-GAN vocoder not found in $root")
        }
        hiFiGan = session(sharedVocoder)

        // Check if root is a direct single-language folder (e.g. tts/en-hi)
        if (File(root, "meta.json").isFile && File(root, "fastpitch.onnx").isFile) {
            val code = normalizeLanguage(root.name)
            val cfg = parseVoice(code, File(root, "fastpitch.onnx"), File(root, "meta.json"))
            voices[code] = cfg
            if (code == "en-hi") {
                voices["english"] = cfg
                voices["hindi"] = cfg
            }
            defaultVoiceCode = code
        } else {
            // Multi-language folder structure: scan subdirectories
            val subdirs = root.listFiles { f -> f.isDirectory && f.name != "shared" } ?: emptyArray()
            for (dir in subdirs) {
                val fpFile = File(dir, "fastpitch.onnx")
                val metaFile = File(dir, "meta.json")
                if (fpFile.isFile && metaFile.isFile) {
                    val code = normalizeLanguage(dir.name)
                    val cfg = parseVoice(code, fpFile, metaFile)
                    voices[code] = cfg
                    if (code == "en-hi") {
                        voices["english"] = cfg
                        voices["hindi"] = cfg
                    }
                }
            }
            require(voices.isNotEmpty()) { "No valid FastPitch voices found in $root" }
            defaultVoiceCode = if (voices.containsKey("en-hi")) "en-hi" else voices.keys.first()
        }

        val primary = voices.getValue(defaultVoiceCode)
        sampleRate = primary.sampleRate
        chunkGapFrames = primary.chunkGapFrames
    }

    private fun parseVoice(code: String, fpFile: File, metaFile: File): IndicVoiceConfig {
        val config = JSONObject(metaFile.readText())
        val sr = config.getInt("sample_rate")
        val gap = sr * config.optInt("chunk_gap_ms", 60) / 1_000
        val textProc = IndicTtsTextProcessor(
            config.getJSONArray("vocab").let { array -> List(array.length()) { array.getString(it) } },
            config.optInt("max_chunk_chars", 70),
        )
        return IndicVoiceConfig(
            code = code,
            modelFile = fpFile,
            speakerId = config.getInt("speaker_id").toLong(),
            lengthScale = config.optDouble("length_scale_default", 0.9).toFloat(),
            sampleRate = sr,
            chunkGapFrames = gap,
            textProcessor = textProc,
        )
    }

    fun supportsLanguage(language: String): Boolean {
        val code = normalizeLanguage(language)
        return voices.containsKey(code) || voices.containsKey(language.lowercase(Locale.ROOT).trim())
    }

    fun supportedLanguages(): Set<String> = voices.keys

    fun chunks(value: String, language: String? = null): List<String> {
        val voice = resolveVoice(language)
        return voice.textProcessor.chunks(value)
    }

    fun synthesize(chunk: String, emphasizedWord: String? = null, language: String? = null): FloatArray {
        val voice = resolveVoice(language)
        val fastPitch = getFastPitch(voice)
        val supportsPitch = supportsPitchMap[voice.code] ?: false

        val (ids, pitchValues, durationValues) = voice.textProcessor.prosodyInputs(chunk, emphasizedWord, EMPHASIS_BIAS)
        require(ids.isNotEmpty()) { "TTS text has no supported characters for language: ${voice.code}" }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tokens ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(voice.speakerId)), longArrayOf(1)).use { speaker ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(voice.lengthScale)), longArrayOf(1)).use { scale ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchValues), longArrayOf(1, ids.size.toLong())).use { pitch ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(durationValues), longArrayOf(1, ids.size.toLong())).use { duration ->
                            val inputs = mutableMapOf<String, OnnxTensor>(
                                "tokens" to tokens, "speaker_id" to speaker, "length_scale" to scale,
                            )
                            if (supportsPitch) {
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

    private fun resolveVoice(language: String?): IndicVoiceConfig {
        if (language == null) return voices.getValue(defaultVoiceCode)
        val code = normalizeLanguage(language)
        return voices[code] ?: voices[language.lowercase(Locale.ROOT).trim()] ?: voices.getValue(defaultVoiceCode)
    }

    private fun getFastPitch(voice: IndicVoiceConfig): OrtSession = synchronized(fastPitchSessions) {
        fastPitchSessions[voice.code]?.let { return it }
        while (fastPitchSessions.size >= MAX_LOADED_FASTPITCH) {
            val oldest = fastPitchSessions.entries.first()
            oldest.value.close()
            fastPitchSessions.remove(oldest.key)
        }
        val session = session(voice.modelFile)
        supportsPitchMap[voice.code] = "pitch_bias" in session.inputNames && "duration_scale" in session.inputNames
        fastPitchSessions[voice.code] = session
        return session
    }

    override fun close() {
        synchronized(fastPitchSessions) {
            fastPitchSessions.values.forEach { runCatching { it.close() } }
            fastPitchSessions.clear()
        }
        runCatching { hiFiGan.close() }
    }

    private fun session(file: File): OrtSession {
        require(file.isFile) { "Missing ${file.path}" }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setIntraOpNumThreads(4)
            options.setMemoryPatternOptimization(true)
            env.createSession(file.path, options)
        }
    }
}
