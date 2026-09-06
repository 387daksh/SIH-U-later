package org.sih.offlinespeech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import kotlin.math.floor
import kotlin.math.max

fun interface AudioChunkListener {
    fun onAudioChunk(chunk: FloatArray)
}

/** A continuous 500 ms, 16 kHz capture window and its sender-relative timestamp. */
fun interface StreamingAudioChunkListener {
    fun onStreamingChunk(chunk: FloatArray, senderTimestampMs: Long)
}

class AudioPipeline(
    private val context: Context,
    private val listener: AudioChunkListener,
    private val streamingListener: StreamingAudioChunkListener? = null,
) : Closeable {

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 512
        const val STREAMING_CHUNK_SAMPLES = TARGET_SAMPLE_RATE / 2
        private val CANDIDATE_RATES = intArrayOf(16000, 48000, 44100, 22050, 8000)
    }

    @Volatile
    private var running = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var actualSampleRate = TARGET_SAMPLE_RATE

    private val dcFilter = DcOffsetFilter(0.995f)
    private var resampler: LinearResampler? = null

    // Accumulator to ensure exactly 512 samples are emitted per chunk
    private val accumulator = FloatArray(CHUNK_SIZE * 4)
    private var accumulatorCount = 0
    private val streamingAccumulator = FloatArray(STREAMING_CHUNK_SAMPLES * 2)
    private var streamingAccumulatorCount = 0
    private var emittedStreamingSamples = 0L

    val sampleRate: Int
        get() = actualSampleRate

    val isRecording: Boolean
        get() = running

    @Synchronized
    fun start() {
        if (running) return

        val record = initAudioRecord()
        audioRecord = record
        actualSampleRate = record.sampleRate
        resampler = if (actualSampleRate != TARGET_SAMPLE_RATE) {
            LinearResampler(actualSampleRate, TARGET_SAMPLE_RATE)
        } else {
            null
        }

        dcFilter.reset()
        resampler?.reset()
        accumulatorCount = 0
        streamingAccumulatorCount = 0
        emittedStreamingSamples = 0

        running = true
        record.startRecording()

        captureThread = Thread({
            runCaptureLoop(record)
        }, "OfflineSpeech-AudioCapture").also { it.start() }
    }

    private fun initAudioRecord(): AudioRecord {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is not granted")
        }
        for (rate in CANDIDATE_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf > 0) {
                try {
                    val candidate = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(minBuf, 4096)
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        return candidate
                    }
                    candidate.release()
                } catch (_: Exception) {
                    // Try next candidate rate
                }
            }
        }
        throw IllegalStateException("No supported microphone sample rate available")
    }

    private fun runCaptureLoop(record: AudioRecord) {
        val shortBuffer = ShortArray(1024)
        val floatRaw = FloatArray(1024)

        while (running) {
            val readCount = record.read(shortBuffer, 0, shortBuffer.size)
            if (readCount <= 0) continue

            // 1. Convert PCM16 to float [-1.0, 1.0]
            for (i in 0 until readCount) {
                floatRaw[i] = shortBuffer[i] / 32768.0f
            }

            // 2. Apply DC offset removal filter
            dcFilter.applyInPlace(floatRaw, readCount)

            // 3. Resample to 16 kHz if native mic rate is different
            val processed: FloatArray
            val processedCount: Int
            val activeResampler = resampler
            if (activeResampler != null) {
                processed = activeResampler.resample(floatRaw, readCount)
                processedCount = processed.size
            } else {
                processed = floatRaw
                processedCount = readCount
            }

            // 4. Chunk into exact 512-sample blocks
            var srcOffset = 0
            while (srcOffset < processedCount) {
                val spaceInAccumulator = accumulator.size - accumulatorCount
                val toCopy = minOf(spaceInAccumulator, processedCount - srcOffset)
                System.arraycopy(processed, srcOffset, accumulator, accumulatorCount, toCopy)
                accumulatorCount += toCopy
                srcOffset += toCopy

                while (accumulatorCount >= CHUNK_SIZE) {
                    val chunk = FloatArray(CHUNK_SIZE)
                    System.arraycopy(accumulator, 0, chunk, 0, CHUNK_SIZE)

                    // Shift remaining samples down
                    accumulatorCount -= CHUNK_SIZE
                    if (accumulatorCount > 0) {
                        System.arraycopy(accumulator, CHUNK_SIZE, accumulator, 0, accumulatorCount)
                    }

                    if (running) {
                        listener.onAudioChunk(chunk)
                    }
                }
            }

            // 5. In parallel, emit exact 500 ms windows for streaming STT. This is
            // independent of the 512-sample VAD hop so neither consumer is rounded.
            val stream = streamingListener
            if (stream != null) {
                var streamOffset = 0
                while (streamOffset < processedCount) {
                    val room = streamingAccumulator.size - streamingAccumulatorCount
                    val count = minOf(room, processedCount - streamOffset)
                    System.arraycopy(processed, streamOffset, streamingAccumulator, streamingAccumulatorCount, count)
                    streamingAccumulatorCount += count
                    streamOffset += count
                    while (streamingAccumulatorCount >= STREAMING_CHUNK_SAMPLES) {
                        val chunk = streamingAccumulator.copyOfRange(0, STREAMING_CHUNK_SAMPLES)
                        streamingAccumulatorCount -= STREAMING_CHUNK_SAMPLES
                        if (streamingAccumulatorCount > 0) {
                            System.arraycopy(streamingAccumulator, STREAMING_CHUNK_SAMPLES,
                                streamingAccumulator, 0, streamingAccumulatorCount)
                        }
                        val timestamp = emittedStreamingSamples * 1_000 / TARGET_SAMPLE_RATE
                        emittedStreamingSamples += STREAMING_CHUNK_SAMPLES
                        if (running) stream.onStreamingChunk(chunk, timestamp)
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        captureThread?.interrupt()
        try {
            captureThread?.join(1000)
        } catch (_: InterruptedException) {}
        captureThread = null

        audioRecord?.release()
        audioRecord = null
    }

    override fun close() {
        stop()
    }
}

class DcOffsetFilter(private val r: Float = 0.995f) {
    private var xPrev: Float = 0f
    private var yPrev: Float = 0f

    fun applyInPlace(buffer: FloatArray, count: Int) {
        for (i in 0 until count) {
            val x = buffer[i]
            val y = x - xPrev + r * yPrev
            xPrev = x
            yPrev = y
            buffer[i] = y
        }
    }

    fun reset() {
        xPrev = 0f
        yPrev = 0f
    }
}

// ponytail: linear fallback is enough for microphones; use native DSP only if device WER regresses.
class LinearResampler(private val inRate: Int, private val outRate: Int = 16000) {
    private var lastSample: Float = 0f
    private var hasLastSample: Boolean = false
    private var phase: Double = 0.0
    private val step: Double = inRate.toDouble() / outRate.toDouble()

    fun resample(input: FloatArray, count: Int): FloatArray {
        require(count in 0..input.size)
        if (count == 0) return FloatArray(0)
        if (inRate == outRate) {
            return input.copyOf(count)
        }
        val estimatedOut = ((count + step) / step).toInt() + 1
        val outList = FloatArray(estimatedOut)
        var outIdx = 0

        var pos = phase
        while (true) {
            val left = floor(pos).toInt()
            val right = left + 1
            val frac = (pos - left).toFloat()
            val s0 = when {
                left < 0 && hasLastSample -> lastSample
                left in 0 until count -> input[left]
                else -> break
            }
            val s1 = when {
                right < 0 && hasLastSample -> lastSample
                right in 0 until count -> input[right]
                frac == 0f -> s0
                else -> break
            }

            outList[outIdx++] = s0 + frac * (s1 - s0)
            pos += step
        }

        selfShift(pos, count)
        if (count > 0) {
            lastSample = input[count - 1]
            hasLastSample = true
        }
        return outList.copyOf(outIdx)
    }

    private fun selfShift(pos: Double, count: Int) {
        phase = pos - count
    }

    fun reset() {
        lastSample = 0f
        hasLastSample = false
        phase = 0.0
    }
}
