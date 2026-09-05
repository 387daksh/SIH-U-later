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
    val vadConfig: VadConfig
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

            val vadConfig = when (tier) {
                DeviceTier.LOW -> VadConfig(
                    threshold = 0.55f,
                    silenceThreshold = 0.38f,
                    minSpeechDurationMs = 300,
                    minSilenceDurationMs = 600,
                    speechPadMs = 128,
                    maxSegmentDurationMs = 15000
                )
                DeviceTier.MID -> VadConfig(
                    threshold = 0.50f,
                    silenceThreshold = 0.35f,
                    minSpeechDurationMs = 250,
                    minSilenceDurationMs = 500,
                    speechPadMs = 96,
                    maxSegmentDurationMs = 15000
                )
                DeviceTier.HIGH -> VadConfig(
                    threshold = 0.45f,
                    silenceThreshold = 0.30f,
                    minSpeechDurationMs = 200,
                    minSilenceDurationMs = 400,
                    speechPadMs = 64,
                    maxSegmentDurationMs = 15000
                )
            }

            return DeviceProfile(tier, totalRamGb, cpuCores, vadConfig)
        }
    }
}
