package org.sih.offlinespeech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import kotlin.math.max

fun interface AudioChunkListener {
    fun onAudioChunk(chunk: FloatArray)
}

class AudioPipeline(
    private val listener: AudioChunkListener
) : Closeable {

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 512
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

        running = true
        record.startRecording()

        captureThread = Thread({
            runCaptureLoop(record)
        }, "OfflineSpeech-AudioCapture").also { it.start() }
    }

    private fun initAudioRecord(): AudioRecord {
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

class LinearResampler(private val inRate: Int, private val outRate: Int = 16000) {
    private var lastSample: Float = 0f
    private var hasLastSample: Boolean = false
    private var phase: Double = 0.0
    private val step: Double = inRate.toDouble() / outRate.toDouble()

    fun resample(input: FloatArray, count: Int): FloatArray {
        if (inRate == outRate) {
            return input.copyOf(count)
        }
        val estimatedOut = ((count + 1) / step).toInt() + 2
        val outList = FloatArray(estimatedOut)
        var outIdx = 0

        var pos = phase
        while (true) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()

            val s0 = if (i == 0) {
                if (hasLastSample) lastSample else input[0]
            } else if (i - 1 < count) {
                input[i - 1]
            } else {
                break
            }

            val s1 = if (i < count) {
                input[i]
            } else {
                break
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
