package org.sih.offlinespeech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.ArrayDeque

interface VadListener {
    fun onSpeechStart() {}
    fun onSpeechEnd() {}
    fun onSpeechSegment(audio: FloatArray)
    fun onSpeechProb(prob: Float) {}
}

class SileroVadEngine(
    modelFile: File,
    private val config: VadConfig = VadConfig(),
    private val listener: VadListener
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // Rolling context buffer: 64 samples prepended to each 512 chunk -> 576 model input
    private val context = FloatArray(64)

    // LSTM hidden/cell state: shape [2, 1, 128] = 256 floats
    private val state = FloatArray(2 * 1 * 128)

    // Pre-allocated combined input buffer: [64 context + 512 chunk]
    private val inputBuffer = FloatArray(576)

    // State machine
    private var isSpeaking = false
    private var silenceChunkCount = 0
    private val ringBuffer = ArrayDeque<FloatArray>(maxOf(1, config.speechPadChunks))
    private val speechAccumulator = ArrayList<FloatArray>()

    init {
        require(modelFile.isFile) { "Silero VAD model file missing at ${modelFile.absolutePath}" }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(modelFile.absolutePath, options)
    }

    @Synchronized
    fun processChunk(chunk: FloatArray) {
        require(chunk.size == config.chunkSamples) {
            "Audio chunk size must be ${config.chunkSamples}, got ${chunk.size}"
        }

        // 1. Build 576-sample input: [64 context | 512 new chunk]
        System.arraycopy(context, 0, inputBuffer, 0, 64)
        System.arraycopy(chunk, 0, inputBuffer, 64, 512)

        // 2. Prepare ONNX tensors
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuffer),
            longArrayOf(1, 576)
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, 128)
        )
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(config.sampleRate.toLong())),
            longArrayOf(1)
        )

        val prob: Float
        try {
            val result = session.run(
                mapOf(
                    "input" to inputTensor,
                    "state" to stateTensor,
                    "sr" to srTensor
                )
            )
            result.use { res ->
                val outTensor = res[0] as OnnxTensor
                prob = outTensor.floatBuffer.get(0)

                val stateNTensor = res[1] as OnnxTensor
                stateNTensor.floatBuffer.get(state)
            }
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }

        // 3. Update rolling context buffer from the last 64 samples of inputBuffer
        System.arraycopy(inputBuffer, 512, context, 0, 64)

        listener.onSpeechProb(prob)

        // 4. State machine & segment collection
        if (!isSpeaking) {
            if (prob >= config.threshold) {
                // Speech onset detected
                isSpeaking = true
                silenceChunkCount = 0
                speechAccumulator.clear()

                // Prepend pre-padding from ring buffer
                while (!ringBuffer.isEmpty()) {
                    speechAccumulator.add(ringBuffer.removeFirst())
                }
                speechAccumulator.add(chunk.copyOf())
                listener.onSpeechStart()
            } else {
                // Maintain pre-padding ring buffer during silence
                if (ringBuffer.size >= config.speechPadChunks) {
                    ringBuffer.removeFirst()
                }
                ringBuffer.addLast(chunk.copyOf())
            }
        } else {
            // Already speaking
            speechAccumulator.add(chunk.copyOf())

            if (prob < config.silenceThreshold) {
                silenceChunkCount++
                if (silenceChunkCount >= config.minSilenceChunks) {
                    // Confirmed speech end after pause
                    emitCurrentSegment()
                }
            } else {
                silenceChunkCount = 0
            }

            // Safety limit: if speech duration exceeds maxSegmentDurationMs, force segment split
            if (speechAccumulator.size >= config.maxSegmentChunks) {
                emitCurrentSegment()
            }
        }
    }

    private fun emitCurrentSegment() {
        val excessSilence = maxOf(0, silenceChunkCount - config.speechPadChunks)
        val keepChunks = speechAccumulator.size - excessSilence

        if (keepChunks >= config.minSpeechChunks) {
            val totalSamples = keepChunks * config.chunkSamples
            val audio = FloatArray(totalSamples)
            var offset = 0
            for (i in 0 until keepChunks) {
                val c = speechAccumulator[i]
                System.arraycopy(c, 0, audio, offset, config.chunkSamples)
                offset += config.chunkSamples
            }
            listener.onSpeechEnd()
            listener.onSpeechSegment(audio)
        } else {
            listener.onSpeechEnd()
        }

        speechAccumulator.clear()
        silenceChunkCount = 0
        isSpeaking = false
    }

    @Synchronized
    fun flush() {
        if (isSpeaking && speechAccumulator.size >= config.minSpeechChunks) {
            emitCurrentSegment()
        } else {
            speechAccumulator.clear()
            silenceChunkCount = 0
            isSpeaking = false
        }
    }

    @Synchronized
    fun reset() {
        context.fill(0f)
        state.fill(0f)
        inputBuffer.fill(0f)
        ringBuffer.clear()
        speechAccumulator.clear()
        silenceChunkCount = 0
        isSpeaking = false
    }

    override fun close() {
        session.close()
    }
}
