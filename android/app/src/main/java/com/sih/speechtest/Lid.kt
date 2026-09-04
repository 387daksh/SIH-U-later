package com.sih.speechtest

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** VoxLingua coarse LID: waveform -> 9-way softmax (english + 8 covered Indic langs). */
class Lid(env: OrtEnvironment, modelPath: String, private val englishThreshold: Float = DEFAULT_ENGLISH_THRESHOLD) {
    companion object {
        val LABELS = listOf("english", "hindi", "gujarati", "marathi", "kannada", "malayalam", "tamil", "telugu", "bengali")

        // Personalized-calibration finding (see PROGRESS.md / conversation): for Indian-accented
        // English speakers, VoxLingua107's "english" score often doesn't win the argmax on genuine
        // English speech (it wasn't trained on this accent), but it still tends to sit measurably
        // above the near-zero noise floor seen on genuine Indic speech (real-mic test: Indic clips'
        // english score maxed at 0.0033; English clips ranged 0.00007-0.72, most above 0.01). A
        // threshold on the english score alone -- instead of pure argmax -- recovered most real-mic
        // English clips (0% -> ~73% correct) with only one flip on the 300-sample FLEURS set.
        const val DEFAULT_ENGLISH_THRESHOLD = 0.005f
    }

    private val env = env
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())

    data class Result(val label: String, val isEnglish: Boolean, val scores: FloatArray, val latencyMs: Long)

    fun predict(waveform: FloatArray): Result {
        val start = System.currentTimeMillis()
        val shape = longArrayOf(1, waveform.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), shape).use { input ->
            session.run(mapOf("waveform" to input)).use { output ->
                val scores = (output[0].value as Array<FloatArray>)[0]
                var best = 0
                for (i in scores.indices) if (scores[i] > scores[best]) best = i
                val latency = System.currentTimeMillis() - start
                val isEnglish = scores[0] > englishThreshold
                return Result(LABELS[best], isEnglish, scores, latency)
            }
        }
    }
}
