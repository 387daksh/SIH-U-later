package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var button: Button
    private var recorder: MicRecorder? = null
    private var pipeline: OfflineSpeechPipeline? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val modelDir = File(getExternalFilesDir(null), "models")
        status = TextView(this).apply { text = "Model folder:\n$modelDir"; textSize = 15f }
        result = TextView(this).apply { textSize = 20f; setPadding(0, 32, 0, 32) }
        button = Button(this).apply { text = "Start recording"; setOnClickListener { toggleRecording() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(status); addView(result); addView(button)
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        if (runCatching { validateModelBundle(modelDir) }.isFailure) installEmbeddedModels(modelDir)
    }

    private fun toggleRecording() {
        val active = recorder
        if (active == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                recorder = MicRecorder(this).also { it.start() }
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
                val models = File(getExternalFilesDir(null), "models")
                val engine = pipeline ?: OfflineSpeechPipeline(models).also { pipeline = it }
                val answer = engine.transcribe(audio)
                Log.i("SIH_METRIC", "mode=AUTO language=${answer.language} backend=${answer.backend} " +
                    "audio_ms=${audio.size * 1000 / 16000} inference_ms=${answer.milliseconds}")
                runOnUiThread {
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

    private fun installEmbeddedModels(target: File) {
        button.isEnabled = false
        status.text = "Installing offline models on first run…"
        worker.execute {
            try {
                require(StatFs(filesDir.path).availableBytes > 850L * 1024 * 1024) {
                    "At least 850 MiB free space is required to install the offline models"
                }
                val temporary = File(target.parentFile, "models.installing")
                temporary.deleteRecursively(); temporary.mkdirs()
                copyAssetDirectory("models", temporary)
                validateModelBundle(temporary)
                if (target.exists()) target.deleteRecursively()
                require(temporary.renameTo(target)) { "Could not finish model installation" }
                runOnUiThread { status.text = "Models ready. Tap Start recording."; button.isEnabled = true }
            } catch (error: Exception) {
                runOnUiThread { status.text = "Model install failed: ${error.message}" }
            }
        }
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        require(children.isNotEmpty()) { "No embedded models found in APK" }
        for (name in children) {
            val childPath = "$assetPath/$name"
            val nested = assets.list(childPath).orEmpty()
            val output = File(destination, name)
            if (nested.isNotEmpty()) {
                output.mkdirs(); copyAssetDirectory(childPath, output)
            } else {
                assets.open(childPath).use { input ->
                    FileOutputStream(output).use { stream -> input.copyTo(stream, 1024 * 1024) }
                }
            }
        }
    }

    override fun onDestroy() {
        recorder?.stop()
        worker.execute { pipeline?.close(); pipeline = null }
        worker.shutdown()
        super.onDestroy()
    }
}

private class MicRecorder(private val context: Context) {
    private val bytes = ByteArrayOutputStream()
    @Volatile private var running = false
    private lateinit var audioRecord: AudioRecord
    private lateinit var thread: Thread

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is not granted")
        }
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
