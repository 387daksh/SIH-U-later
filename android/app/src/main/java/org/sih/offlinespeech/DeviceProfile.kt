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
    val speechPadMs: Int = 96,
    val maxSegmentDurationMs: Int = 15000,
    val sampleRate: Int = 16000
) {
    val chunkSamples: Int = 512
    val minSpeechChunks: Int = maxOf(1, (minSpeechDurationMs * sampleRate / 1000) / chunkSamples)
    val minSilenceChunks: Int = maxOf(1, (minSilenceDurationMs * sampleRate / 1000) / chunkSamples)
    val speechPadChunks: Int = maxOf(1, (speechPadMs * sampleRate / 1000) / chunkSamples)
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

            return DeviceProfile(tier, totalRamGb, cpuCores)
        }
    }
}
