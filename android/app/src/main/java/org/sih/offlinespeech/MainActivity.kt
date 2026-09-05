package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val sttWorker = Executors.newSingleThreadExecutor()
    private val pendingSegments = AtomicInteger()

    private lateinit var status: TextView
    private lateinit var vadIndicator: TextView
    private lateinit var result: TextView
    private lateinit var startStopButton: Button
    private lateinit var routeButton: Button
    private lateinit var speakButton: Button

    private lateinit var deviceProfile: DeviceProfile
    private var audioPipeline: AudioPipeline? = null
    private var vadEngine: SileroVadEngine? = null
    private var pipeline: OfflineSpeechPipeline? = null
    private var tts: TextToSpeech? = null

    private var segmentCount = 0
    private var lastText = ""
    private var lastLanguage = "english"
    @Volatile private var listening = false
    @Volatile private var routingMode = RoutingMode.AUTO

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        deviceProfile = DeviceProfile.detect(this)
        val modelDir = File(filesDir, "models")
        val vadModelFile = File(modelDir, "vad/silero_vad.onnx")

        status = TextView(this).apply {
            text = "Model folder: $modelDir\n${deviceProfile.summary()}"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        vadIndicator = TextView(this).apply {
            text = "VAD: Idle"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }

        result = TextView(this).apply {
            textSize = 17f
            setPadding(0, 16, 0, 16)
            text = "Transcribed speech segments will appear here in real-time."
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            addView(result)
        }

        startStopButton = Button(this).apply {
            text = "Start continuous listening"
            setOnClickListener { toggleListening() }
        }

        routeButton = Button(this).apply {
            text = "Routing: AUTO"
            setOnClickListener {
                routingMode = RoutingMode.entries[(routingMode.ordinal + 1) % RoutingMode.entries.size]
                text = "Routing: $routingMode"
            }
        }

        speakButton = Button(this).apply {
            text = "Speak latest result"
            isEnabled = false
            setOnClickListener { speakResult() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(status)
            addView(vadIndicator)
            addView(startStopButton)
            addView(routeButton)
            addView(speakButton)
            addView(scrollView)
        })

        tts = TextToSpeech(this) { code ->
            if (code != TextToSpeech.SUCCESS) {
                runOnUiThread { status.append("\nTTS unavailable") }
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val modelError = runCatching { validateModelBundle(modelDir) }.exceptionOrNull()
        if (modelError != null || !vadModelFile.isFile) {
            startStopButton.isEnabled = false
            status.text = "Models missing or incomplete: ${modelError?.message ?: "Silero VAD missing"}\n" +
                "Copy artifacts/android_models over USB.\n$modelDir"
        } else {
            status.text = "Models ready (${deviceProfile.summary()}). Tap Start listening."
        }
    }

    private fun toggleListening() {
        val activePipeline = audioPipeline
        if (activePipeline == null) {
            // Start continuous listening
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return
            }

            val modelDir = File(filesDir, "models")
            val vadModel = File(modelDir, "vad/silero_vad.onnx")
            if (!vadModel.isFile) {
                vadIndicator.text = "Error: Silero VAD model missing at ${vadModel.path}"
                return
            }

            segmentCount = 0
            result.text = ""

            try {
                val engine = SileroVadEngine(
                    modelFile = vadModel,
                    config = deviceProfile.vadConfig,
                    listener = object : VadListener {
                        override fun onSpeechStart() {
                            runOnUiThread {
                                vadIndicator.text = "🗣️ Speech detected..."
                            }
                        }

                        override fun onSpeechEnd() {
                            runOnUiThread {
                                vadIndicator.text = "⏳ Pause detected, segmenting..."
                            }
                        }

                        override fun onSpeechSegment(audio: FloatArray) {
                            val segId = ++segmentCount
                            val mode = routingMode
                            // ponytail: one running + one queued; persist audio only if device tests show lost speech.
                            if (pendingSegments.incrementAndGet() > 2) {
                                pendingSegments.decrementAndGet()
                                runOnUiThread {
                                    vadIndicator.text = "Transcription busy; segment #$segId skipped"
                                }
                                return
                            }
                            runOnUiThread {
                                vadIndicator.text = "⏳ Transcribing segment #$segId (${"%.1f".format(audio.size / 16000.0)}s)..."
                            }

                            sttWorker.execute {
                                try {
                                    val sttEngine = pipeline ?: OfflineSpeechPipeline(modelDir).also { pipeline = it }
                                    val answer = sttEngine.transcribe(audio, mode)
                                    Log.i("SIH_METRIC", "segment=$segId mode=$mode language=${answer.language} " +
                                        "backend=${answer.backend} audio_ms=${audio.size * 1000 / 16000} " +
                                        "inference_ms=${answer.milliseconds}")
                                    runOnUiThread {
                                        lastText = answer.text
                                        lastLanguage = answer.language
                                        speakButton.isEnabled = answer.text.isNotBlank()

                                        val line = "[#$segId] ${answer.language.uppercase()} (${answer.milliseconds}ms):\n${answer.text}\n\n"
                                        result.append(line)
                                        vadIndicator.text = if (listening) {
                                            "🟢 Listening (Segment #$segId completed in ${answer.milliseconds}ms)"
                                        } else {
                                            "Stopped (Segment #$segId completed in ${answer.milliseconds}ms)"
                                        }
                                    }
                                } catch (error: Exception) {
                                    runOnUiThread {
                                        vadIndicator.text = "⚠️ Segment #$segId error: ${error.message}"
                                    }
                                } finally {
                                    pendingSegments.decrementAndGet()
                                }
                            }
                        }
                    }
                )
                vadEngine = engine

                val pipelineCapture = AudioPipeline(this@MainActivity) { chunk ->
                    engine.processChunk(chunk)
                }
                pipelineCapture.start()
                audioPipeline = pipelineCapture
                listening = true

                startStopButton.text = "Stop listening"
                vadIndicator.text = "🟢 Listening (${pipelineCapture.sampleRate} Hz mic, VAD active)"
            } catch (error: Exception) {
                vadIndicator.text = "Failed to start: ${error.message}"
            }
        } else {
            // Stop continuous listening
            listening = false
            try {
                activePipeline.stop()
            } catch (_: Exception) {}
            audioPipeline = null

            vadEngine?.flush()
            vadEngine?.close()
            vadEngine = null

            startStopButton.text = "Start continuous listening"
            vadIndicator.text = "⏹️ Stopped listening"
        }
    }

    private fun speakResult() {
        val engine = tts ?: return
        val tag = mapOf(
            "english" to "en-IN", "hindi" to "hi-IN", "gujarati" to "gu-IN",
            "marathi" to "mr-IN", "kannada" to "kn-IN", "malayalam" to "ml-IN",
            "tamil" to "ta-IN", "telugu" to "te-IN", "odia" to "or-IN",
            "bengali" to "bn-IN",
        )[lastLanguage] ?: "en-IN"

        val locale = Locale.forLanguageTag(tag)
        val voice = engine.voices
            .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            .maxByOrNull { if (it.locale.country == locale.country) 1 else 0 }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            status.text = "No offline TTS voice installed for $lastLanguage"
            return
        }
        engine.speak(lastText, TextToSpeech.QUEUE_FLUSH, null, "transcription")
    }

    override fun onDestroy() {
        listening = false
        audioPipeline?.stop()
        audioPipeline = null
        vadEngine?.close()
        vadEngine = null
        sttWorker.execute { pipeline?.close(); pipeline = null }
        sttWorker.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }
}
