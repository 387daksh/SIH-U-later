package org.sih.offlinespeech

import android.app.ActivityManager
import android.content.Context

enum class DeviceTier {
    LOW,
    MID,
    HIGH
}

data class VadConfig(
    val threshold: Float = 0.50f,
    val silenceThreshold: Float = threshold - 0.15f,
    val minSpeechDurationMs: Int = 250,
    val minSilenceDurationMs: Int = 500,
    /**
     * Pause accepted as end-of-utterance once [confidentSpeechMs] of voiced audio has accumulated.
     * Shorter than [minSilenceDurationMs] because by then the utterance is certainly real, and the
     * full window is only needed to protect a hesitant speaker who has barely started.
     */
    val minSilenceDurationConfidentMs: Int = 320,
    /** Pause accepted while push-to-talk is physically held, where intent is not in doubt. */
    val minSilenceDurationHeldMs: Int = 256,
    /** Voiced audio after which the shorter window applies. */
    val confidentSpeechMs: Int = 800,
    val speechPadMs: Int = 96,
    /**
     * Audio retained from *before* the speech trigger and prepended to the segment.
     *
     * Separate from [speechPadMs] (which also trims the tail) so leading context can be generous
     * without keeping more trailing silence. The VAD only fires once a phrase is already under way,
     * so unvoiced onsets -- /k/ /t/ /p/ bursts, initial fricatives -- sit in the chunks just before
     * the trigger and were being cut off the front of every utterance.
     */
    val preRollMs: Int = 192,
    val maxSegmentDurationMs: Int = 15000,
    val sampleRate: Int = 16000
) {
    val chunkSamples: Int = 512
    private val chunksPerMs: (Int) -> Int = { ms -> maxOf(1, (ms * sampleRate / 1000) / chunkSamples) }
    val minSpeechChunks: Int = chunksPerMs(minSpeechDurationMs)
    val minSilenceChunks: Int = chunksPerMs(minSilenceDurationMs)
    val minSilenceChunksConfident: Int = minOf(minSilenceChunks, chunksPerMs(minSilenceDurationConfidentMs))
    val minSilenceChunksHeld: Int = minOf(minSilenceChunks, chunksPerMs(minSilenceDurationHeldMs))
    val confidentSpeechChunks: Int = chunksPerMs(confidentSpeechMs)
    val speechPadChunks: Int = chunksPerMs(speechPadMs)
    val preRollChunks: Int = maxOf(speechPadChunks, chunksPerMs(preRollMs))
    val maxSegmentChunks: Int = (maxSegmentDurationMs * sampleRate / 1000) / chunkSamples
}

data class DeviceProfile(
    val tier: DeviceTier,
    val totalRamGb: Double,
    val cpuCores: Int,
    val vadConfig: VadConfig = VadConfig()
) {
    fun summary(): String {
        return "Tier: $tier (${"%.1f".format(totalRamGb)} GB RAM, $cpuCores cores)"
    }

    companion object {
        fun detect(context: Context): DeviceProfile {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            if (actManager != null) {
                actManager.getMemoryInfo(memInfo)
            }
            val totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val cpuCores = Runtime.getRuntime().availableProcessors()

            val tier = when {
                totalRamGb < 3.0 || cpuCores <= 4 -> DeviceTier.LOW
                totalRamGb <= 6.0 -> DeviceTier.MID
                else -> DeviceTier.HIGH
            }

            // Activation memory scales with utterance length: measured on the quantised Indic
            // encoder, a 30 s utterance costs 214 MB of activations against 52 MB for 8 s. Capping
            // the segment bounds the worst case on the devices that cannot absorb it, at the cost
            // of splitting a very long monologue into two messages.
            val vad = if (tier == DeviceTier.LOW) VadConfig(maxSegmentDurationMs = 8_000) else VadConfig()
            return DeviceProfile(tier, totalRamGb, cpuCores, vad)
        }
    }
}
