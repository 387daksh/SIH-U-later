package org.sih.offlinespeech

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One ordered, half-second STT stream. Inference and packetization share one
 * worker so sequence numbers and sender timestamps cannot be reordered.
 */
class StreamingSpeechSession(
    private val pipelineFactory: () -> OfflineSpeechPipeline,
    private val routingMode: RoutingMode,
    private val send: (VoiceBurstPacket) -> Unit,
    private val onTranscript: (SpeechResult, Long) -> Unit = { _, _ -> },
    private val onFinished: () -> Unit = {},
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : Closeable {
    private val packetizer = StreamingTextPacketizer()
    @Volatile private var acceptingAudio = true
    private var sentStart = false
    private var lastTimestampMs = 0L

    fun start() {
        worker.execute {
            if (!sentStart) {
                send(packetizer.start())
                sentStart = true
            }
        }
    }

    fun submit(audio: FloatArray, senderTimestampMs: Long) {
        if (!acceptingAudio) return
        val immutableAudio = audio.copyOf()
        worker.execute {
            if (!acceptingAudio && !sentStart) return@execute
            lastTimestampMs = maxOf(lastTimestampMs, senderTimestampMs)
            val result = pipelineFactory().transcribe(immutableAudio, routingMode)
            packetizer.data(senderTimestampMs, SutraLanguage.fromPipeline(result.language), result.text)?.let(send)
            onTranscript(result, senderTimestampMs)
        }
    }

    fun finish(canceled: Boolean = false) {
        acceptingAudio = false
        worker.execute {
            if (!sentStart) {
                send(packetizer.start())
                sentStart = true
            }
            send(packetizer.end(lastTimestampMs + AudioPipeline.STREAMING_CHUNK_SAMPLES * 1_000L / AudioPipeline.TARGET_SAMPLE_RATE,
                canceled))
            onFinished()
            worker.shutdown()
        }
    }

    override fun close() = finish(canceled = true)
}
