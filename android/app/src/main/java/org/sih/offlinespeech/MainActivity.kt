package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var button: Button
    private lateinit var speakButton: Button
    private var recorder: MicRecorder? = null
    private var pipeline: OfflineSpeechPipeline? = null
    private var tts: TextToSpeech? = null
    private var lastText = ""
    private var lastLanguage = "english"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val modelDir = File(filesDir, "models")
        status = TextView(this).apply { text = "Model folder:\n$modelDir"; textSize = 15f }
        result = TextView(this).apply { textSize = 20f; setPadding(0, 32, 0, 32) }
        button = Button(this).apply { text = "Start recording"; setOnClickListener { toggleRecording() } }
        speakButton = Button(this).apply {
            text = "Speak result"; isEnabled = false; setOnClickListener { speakResult() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(status); addView(result); addView(button); addView(speakButton)
        })
        tts = TextToSpeech(this) { code ->
            if (code != TextToSpeech.SUCCESS) runOnUiThread { status.append("\nTTS unavailable") }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        if (!File(modelDir, "manifest.json").isFile) {
            button.isEnabled = false
            status.text = "Models missing. Install the APK and copy artifacts/android_models over USB.\n$modelDir"
        } else {
            status.text = "Models ready. Tap Start recording."
        }
    }

    private fun toggleRecording() {
        val active = recorder
        if (active == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                recorder = MicRecorder().also { it.start() }
                button.text = "Stop and transcribe"
                status.text = "Recording 16 kHz mono…"
            } catch (error: Exception) { status.text = error.message }
            return
        }
        recorder = null
        button.isEnabled = false
        status.text = "Transcribing offline…"
        worker.execute {
            try {
                val audio = active.stop()
                val models = File(filesDir, "models")
                val engine = pipeline ?: OfflineSpeechPipeline(models).also { pipeline = it }
                val answer = engine.transcribe(audio)
                runOnUiThread {
                    lastText = answer.text
                    lastLanguage = answer.language
                    speakButton.isEnabled = answer.text.isNotBlank()
                    result.text = "${answer.language} · ${answer.backend}\n${answer.text}"
                    status.text = "Stop-to-text: %.2f s".format(answer.milliseconds / 1000.0)
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "Error: ${error.message}" }
            } finally {
                runOnUiThread { button.isEnabled = true; button.text = "Start recording" }
            }
        }
    }

    private fun speakResult() {
        val engine = tts ?: return
        val tag = mapOf(
            "english" to "en-IN", "hindi" to "hi-IN", "gujarati" to "gu-IN",
            "marathi" to "mr-IN", "kannada" to "kn-IN", "malayalam" to "ml-IN",
            "tamil" to "ta-IN", "telugu" to "te-IN", "odia" to "or-IN",
            "bengali" to "bn-IN",
        ).getValue(lastLanguage)
        if (engine.setLanguage(Locale.forLanguageTag(tag)) < TextToSpeech.LANG_AVAILABLE) {
            status.text = "No installed TTS voice for $lastLanguage"
            return
        }
        engine.speak(lastText, TextToSpeech.QUEUE_FLUSH, null, "transcription")
    }

    override fun onDestroy() {
        recorder?.stop(); worker.shutdownNow(); pipeline?.close(); tts?.shutdown(); super.onDestroy()
    }
}

private class MicRecorder {
    private val bytes = ByteArrayOutputStream()
    @Volatile private var running = false
    private lateinit var audioRecord: AudioRecord
    private lateinit var thread: Thread

    fun start() {
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "No usable 16 kHz microphone" }
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
        running = true
        audioRecord.startRecording()
        thread = Thread {
            val buffer = ByteArray(4096)
            while (running) {
                val count = audioRecord.read(buffer, 0, buffer.size)
                if (count > 0) bytes.write(buffer, 0, count)
            }
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        if (!running) return FloatArray(0)
        running = false
        audioRecord.stop(); thread.join(); audioRecord.release()
        val pcm = bytes.toByteArray()
        return FloatArray(pcm.size / 2) { index ->
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768f
        }
    }
}
