package com.sih.speechtest

import ai.onnxruntime.OrtEnvironment
import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Debug
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private val sampleRate = 16000
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var recordButton: Button

    private var env: OrtEnvironment? = null
    private var lid: Lid? = null
    private var indic: IndicAsr? = null
    private var whisper: WhisperAsr? = null

    private var recording = false
    private var audioRecord: AudioRecord? = null
    private val recordedChunks = ArrayList<ShortArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        recordButton = findViewById(R.id.recordButton)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        recordButton.setOnClickListener { onRecordClicked() }
        loadModels()
    }

    private fun memoryLine(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val pid = intArrayOf(android.os.Process.myPid())
        val procMemInfo = am.getProcessMemoryInfo(pid)[0]
        val appPssMb = procMemInfo.totalPss / 1024
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val deviceAvailMb = info.availMem / (1024 * 1024)
        val deviceTotalMb = info.totalMem / (1024 * 1024)
        return "App PSS: ${appPssMb}MB | native heap: ${nativeHeapMb}MB | device avail: ${deviceAvailMb}/${deviceTotalMb}MB"
    }

    private fun loadModels() {
        statusText.text = "Loading models..."
        recordButton.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            val base = File(filesDir, "models").absolutePath
            val startMs = System.currentTimeMillis()
            try {
                val ortEnv = OrtEnvironment.getEnvironment()
                env = ortEnv
                val lidModel = Lid(ortEnv, "$base/lid.onnx")
                val indicModel = IndicAsr(ortEnv, "$base/indic")
                val whisperModel = WhisperAsr(ortEnv, "$base/whisper")
                lid = lidModel; indic = indicModel; whisper = whisperModel
                val elapsed = System.currentTimeMillis() - startMs
                withContext(Dispatchers.Main) {
                    statusText.text = "All 3 models loaded in ${elapsed}ms\n${memoryLine()}"
                    recordButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "MODEL LOAD FAILED: ${e.javaClass.simpleName}: ${e.message}"
                    resultText.text = android.util.Log.getStackTraceString(e)
                }
            }
        }
    }

    private fun onRecordClicked() {
        if (!recording) {
            startRecording()
        } else {
            stopRecordingAndRun()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Microphone permission not granted."
            return
        }
        recordedChunks.clear()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        audioRecord = record
        record.startRecording()
        recording = true
        recordButton.text = "Stop"
        statusText.text = "Recording..."

        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(1600)
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(recordedChunks) { recordedChunks.add(buffer.copyOf(read)) }
                }
            }
        }
    }

    private fun stopRecordingAndRun() {
        recording = false
        recordButton.isEnabled = false
        recordButton.text = "Record"
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val totalSamples = recordedChunks.sumOf { it.size }
        val waveform = FloatArray(totalSamples)
        var offset = 0
        for (chunk in recordedChunks) {
            for (s in chunk) waveform[offset++] = s / 32768f
        }
        val savedPath = saveWav(waveform)
        statusText.text = "Captured ${"%.2f".format(totalSamples / sampleRate.toFloat())}s, saved to $savedPath, running inference..."

        CoroutineScope(Dispatchers.Default).launch {
            val sb = StringBuilder()
            try {
                val lidModel = lid!!; val indicModel = indic!!; val whisperModel = whisper!!
                val overallStart = System.currentTimeMillis()

                val lidResult = lidModel.predict(waveform)
                sb.append("LID: ${lidResult.label} (english=${"%.3f".format(lidResult.scores[0])}) in ${lidResult.latencyMs}ms\n")
                sb.append("Scores: ${Lid.LABELS.zip(lidResult.scores.toList()).joinToString(", ") { "${it.first}=%.3f".format(it.second) }}\n\n")

                if (lidResult.isEnglish) {
                    val whisperStart = System.currentTimeMillis()
                    val result = whisperModel.transcribe(waveform)
                    val ms = System.currentTimeMillis() - whisperStart
                    sb.append("Route: ENGLISH -> Whisper (${ms}ms)\n")
                    sb.append("Text: ${result.text}\n")
                } else {
                    val result = indicModel.transcribeAll(waveform)
                    sb.append("Route: INDIC -> IndicConformer\n")
                    sb.append("Encoder: ${result.encoderMs}ms, CTC decode: ${result.decoderMs}ms\n")
                    sb.append("Winner: ${result.winner.language} (confidence=${"%.4f".format(result.winner.confidence)})\n")
                    sb.append("Text: ${result.winner.text}\n\n")
                    sb.append("All candidates:\n")
                    for (c in result.candidates.sortedByDescending { it.confidence }) {
                        sb.append("  ${c.language}: conf=${"%.4f".format(c.confidence)} text=${c.text}\n")
                    }
                }
                val totalMs = System.currentTimeMillis() - overallStart
                sb.append("\nTotal inference: ${totalMs}ms\n${memoryLine()}")
            } catch (e: Exception) {
                sb.append("INFERENCE FAILED: ${e.javaClass.simpleName}: ${e.message}\n")
                sb.append(android.util.Log.getStackTraceString(e))
            }
            withContext(Dispatchers.Main) {
                statusText.text = "Done. ${memoryLine()}"
                resultText.text = sb.toString()
                recordButton.isEnabled = true
            }
        }
    }

    /** 16-bit PCM mono WAV, so failing real recordings can be pulled with `adb pull` and
     * diagnosed directly against the Python reference pipeline -- same approach used to find
     * the InputNormalization export bug. Returns the path actually written to. */
    private fun saveWav(waveform: FloatArray): String {
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val name = "rec_${System.currentTimeMillis()}.wav"
        val file = File(dir, name)
        val pcm = ByteArray(waveform.size * 2)
        for (i in waveform.indices) {
            val sample = (waveform[i].coerceIn(-1f, 1f) * 32767).toInt()
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val dataSize = pcm.size
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(pcm)
        }
        return file.absolutePath
    }
}
