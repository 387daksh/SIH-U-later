package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity(), SutraTransmissionListener {
    private val sttWorker = Executors.newSingleThreadExecutor()
    private val pendingSegments = AtomicInteger()

    private lateinit var status: TextView
    private lateinit var vadIndicator: TextView
    private lateinit var result: TextView
    private lateinit var startStopButton: Button
    private lateinit var routeButton: Button
    private lateinit var speakButton: Button
    private lateinit var alertButton: Button
    private lateinit var networkStatus: TextView
    private lateinit var peerAddress: EditText
    private lateinit var wifiPeerAdapter: ArrayAdapter<String>
    private lateinit var wifiPeerSpinner: Spinner
    private lateinit var bluetoothPeerAdapter: ArrayAdapter<String>
    private lateinit var bluetoothPeerSpinner: Spinner
    private lateinit var pairingPhrase: EditText

    private lateinit var deviceProfile: DeviceProfile
    private lateinit var modelDir: File
    private var wifiDirect: WifiDirectController? = null
    private var wifiPeers = emptyList<WifiDirectPeer>()
    private var bluetoothPeers = emptyList<BluetoothPeer>()
    private var transmission: SutraTransmissionService? = null
    private var serviceBound = false
    private var audioPipeline: AudioPipeline? = null
    private var vadEngine: SileroVadEngine? = null
    private var pipeline: OfflineSpeechPipeline? = null
    private var tts: TextToSpeech? = null

    private var segmentCount = 0
    private var lastText = ""
    private var lastLanguage = "english"
    private var sourceId = 0L
    private var alertMode = false
    @Volatile private var listening = false
    @Volatile private var routingMode = RoutingMode.AUTO

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            transmission = (binder as SutraTransmissionService.LocalBinder).service.also {
                it.setListener(this@MainActivity)
            }
            serviceBound = true
            refreshBluetoothPeers()
            networkStatus.text = "SUTRA receiver service ready"
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            transmission = null
            networkStatus.text = "SUTRA receiver service stopped"
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        deviceProfile = DeviceProfile.detect(this)
        sourceId = getSharedPreferences("sutra", MODE_PRIVATE).let { preferences ->
            if (preferences.contains("terminal_id")) preferences.getLong("terminal_id", 0)
            else SecureRandom().nextLong().let { generated ->
                preferences.edit().putLong("terminal_id", generated).apply(); generated
            }
        }
        modelDir = resolveModelDir()
        val vadModelFile = File(modelDir, "vad/silero_vad.onnx")
        val vadInstallError = runCatching { installVadIfMissing(vadModelFile) }.exceptionOrNull()

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

        alertButton = Button(this).apply {
            text = "Message: NORMAL"
            setOnClickListener {
                alertMode = !alertMode
                text = if (alertMode) "Message: ALERT" else "Message: NORMAL"
                setTextColor(if (alertMode) Color.RED else Color.BLACK)
                if (alertMode && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                    networkStatus.text = "Alert enabled, but DND access is not granted; playback may be suppressed"
                }
            }
        }

        networkStatus = TextView(this).apply {
            val addresses = TcpPeerTransport.localIpv4Addresses().joinToString().ifBlank { "unavailable" }
            text = "SUTRA Wi-Fi: disconnected\nThis phone: $addresses:${TcpPeerTransport.PORT}"
            setPadding(0, 12, 0, 4)
        }

        peerAddress = EditText(this).apply {
            hint = "Diagnostic fallback: other phone IP"
            setSingleLine()
        }

        val networkButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Manual host"
                setOnClickListener { transmission?.wifiHost() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Manual connect"
                setOnClickListener {
                    runCatching { requireNotNull(transmission).wifiConnect(peerAddress.text.toString()) }
                        .onFailure { networkStatus.text = it.message }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Disconnect"
                setOnClickListener {
                    transmission?.disconnectAll()
                    networkStatus.text = "SUTRA Wi-Fi: disconnected"
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        wifiPeerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf("No nearby SUTRA phones found"),
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        wifiPeerSpinner = Spinner(this).apply { adapter = wifiPeerAdapter }

        wifiDirect = runCatching { WifiDirectController(this, object : WifiDirectListener {
            override fun onWifiDirectStatus(statusText: String) {
                runOnUiThread { networkStatus.text = "Nearby SOS: $statusText" }
            }

            override fun onWifiDirectPeers(peers: List<WifiDirectPeer>) {
                runOnUiThread {
                    wifiPeers = peers
                    wifiPeerAdapter.clear()
                    wifiPeerAdapter.addAll(
                        peers.map { "${it.name} (${it.address})" }
                            .ifEmpty { listOf("No nearby SUTRA phones found") }
                    )
                    wifiPeerAdapter.notifyDataSetChanged()
                }
            }

            override fun onWifiDirectGroup(isOwner: Boolean, ownerAddress: String?) {
                runOnUiThread {
                    networkStatus.text = if (isOwner) {
                        "Nearby SOS group created; waiting for receiver"
                    } else {
                        "Nearby SOS group joined; opening local link"
                    }
                }
                if (isOwner) transmission?.wifiHost()
                else if (ownerAddress != null) transmission?.wifiConnect(ownerAddress)
            }
        }).also { it.start() } }.onFailure {
            networkStatus.text = "Wi-Fi Direct unavailable; use Bluetooth fallback"
        }.getOrNull()

        val wifiDirectButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Find nearby"
                setOnClickListener {
                    if (ensureWifiDirectPermission()) wifiDirect?.discover()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Connect"
                setOnClickListener {
                    val peer = wifiPeers.getOrNull(wifiPeerSpinner.selectedItemPosition)
                    runCatching { requireNotNull(wifiDirect).connect(peer?.address ?: "") }
                        .onFailure { networkStatus.text = it.message }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Disconnect"
                setOnClickListener {
                    wifiDirect?.disconnect()
                    transmission?.wifiDisconnect()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        bluetoothPeerAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, mutableListOf("No paired Bluetooth phones"),
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        bluetoothPeerSpinner = Spinner(this).apply { adapter = bluetoothPeerAdapter }
        val bluetoothButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "BT receive"
                setOnClickListener {
                    if (ensureBluetoothPermission()) {
                        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                        transmission?.bluetoothListen()
                        refreshBluetoothPeers()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "BT connect"
                setOnClickListener {
                    if (ensureBluetoothPermission()) {
                        val peer = bluetoothPeers.getOrNull(bluetoothPeerSpinner.selectedItemPosition)
                        runCatching { requireNotNull(transmission).bluetoothConnect(peer?.address ?: "") }
                            .onFailure { networkStatus.text = it.message }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Refresh"
                setOnClickListener { if (ensureBluetoothPermission()) refreshBluetoothPeers() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        pairingPhrase = EditText(this).apply {
            hint = "Shared pairing phrase (12+ characters)"
            setSingleLine()
        }
        val securityAndMetrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Secure session"
                setOnClickListener {
                    runCatching { requireNotNull(transmission).setPairingPhrase(pairingPhrase.text.toString()) }
                        .onFailure { networkStatus.text = it.message }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Export metrics"
                setOnClickListener {
                    runCatching { requireNotNull(transmission).exportMetrics() }
                        .onSuccess { (file, rows) -> networkStatus.text = "Exported $rows events to ${file.path}" }
                        .onFailure { networkStatus.text = it.message }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(status)
            addView(vadIndicator)
            addView(startStopButton)
            addView(routeButton)
            addView(alertButton)
            addView(speakButton)
            addView(networkStatus)
            addView(TextView(this@MainActivity).apply { text = "Nearby phones (Wi-Fi Direct; no router or internet)" })
            addView(wifiPeerSpinner)
            addView(wifiDirectButtons)
            addView(TextView(this@MainActivity).apply { text = "Bluetooth fallback (pair phones once in Android Settings)" })
            addView(bluetoothPeerSpinner)
            addView(bluetoothButtons)
            addView(pairingPhrase)
            addView(securityAndMetrics)
            addView(TextView(this@MainActivity).apply { text = "Manual IP fallback (diagnostics only)" })
            addView(peerAddress)
            addView(networkButtons)
            addView(result)
        }
        setContentView(ScrollView(this).apply { addView(page) })

        tts = TextToSpeech(this) { code ->
            if (code != TextToSpeech.SUCCESS) {
                runOnUiThread { status.append("\nTTS unavailable") }
            }
        }

        val serviceIntent = Intent(this, SutraTransmissionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        }

        val modelError = runCatching { validateModelBundle(modelDir) }.exceptionOrNull()
        if (modelError != null || vadInstallError != null || !vadModelFile.isFile) {
            startStopButton.isEnabled = false
            status.text = "Models missing or incomplete: ${modelError?.message ?: vadInstallError?.message ?: "Silero VAD missing"}\n" +
                "Copy artifacts/android_models over USB.\n$modelDir"
        } else {
            status.text = "Models ready (${deviceProfile.summary()}). Tap Start listening."
        }
    }

    private fun ensureWifiDirectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(permission), 2)
        networkStatus.text = "Grant Nearby devices permission, then tap Find nearby again"
        return false
    }

    private fun ensureBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 3)
        networkStatus.text = "Grant Nearby devices permission, then retry Bluetooth"
        return false
    }

    private fun refreshBluetoothPeers() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothPeers = runCatching { transmission?.bluetoothPeers().orEmpty() }.getOrDefault(emptyList())
        bluetoothPeerAdapter.clear()
        bluetoothPeerAdapter.addAll(
            bluetoothPeers.map { "${it.name} (${it.address})" }.ifEmpty { listOf("No paired Bluetooth phones") },
        )
        bluetoothPeerAdapter.notifyDataSetChanged()
    }

    private fun resolveModelDir(): File {
        // The original 800 MB APK extracted here. Keep it first so thin APK updates reuse it.
        val bundledApkPath = getExternalFilesDir(null)?.resolve("models")
        val usbStagedPath = File(filesDir, "models")
        return when {
            bundledApkPath?.isDirectory == true -> bundledApkPath
            usbStagedPath.isDirectory -> usbStagedPath
            bundledApkPath != null -> bundledApkPath
            else -> usbStagedPath
        }
    }

    private fun installVadIfMissing(target: File) {
        if (target.isFile) return
        val directory = requireNotNull(target.parentFile)
        require(directory.isDirectory || directory.mkdirs()) { "Could not create VAD model directory" }
        val temporary = File(directory, "${target.name}.installing")
        assets.open("vad/silero_vad.onnx").use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        require(temporary.length() > 1_000_000) { "Bundled Silero VAD is incomplete" }
        if (target.exists()) target.delete()
        require(temporary.renameTo(target)) { "Could not install bundled Silero VAD" }
    }

    private fun toggleListening() {
        val activePipeline = audioPipeline
        if (activePipeline == null) {
            // Start continuous listening
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return
            }

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
                            val transmissionId = SutraId.random()
                            val mode = routingMode
                            val isAlert = alertMode
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

                            transmission?.recordMetric(transmissionId.toString(), "speech_end", "segment=$segId")
                            sttWorker.execute {
                                try {
                                    transmission?.recordMetric(transmissionId.toString(), "asr_start", "segment=$segId,audio_samples=${audio.size}")
                                    val sttEngine = pipeline ?: OfflineSpeechPipeline(modelDir).also { pipeline = it }
                                    val answer = sttEngine.transcribe(audio, mode)
                                    transmission?.recordMetric(transmissionId.toString(), "asr_final", "inference_ms=${answer.milliseconds},language=${answer.language}")
                                    val layers = SutraSemanticEncoder.build(
                                        language = answer.language,
                                        text = answer.text,
                                        confidence = answer.confidence.takeIf(Double::isFinite) ?: 0.5,
                                        alert = isAlert,
                                        sourceId = sourceId,
                                        id = transmissionId,
                                    )
                                    val wireBytes = layers.messages.sumOf { SutraCodec.encode(it).size }
                                    val queued = runCatching { requireNotNull(transmission).send(layers.messages) }.isSuccess
                                    Log.i("SIH_METRIC", "segment=$segId mode=$mode language=${answer.language} " +
                                        "backend=${answer.backend} audio_ms=${audio.size * 1000 / 16000} " +
                                        "inference_ms=${answer.milliseconds} confidence=${answer.confidence} " +
                                        "sutra_bytes=$wireBytes queued=$queued")
                                    runOnUiThread {
                                        lastText = answer.text
                                        lastLanguage = answer.language
                                        speakButton.isEnabled = answer.text.isNotBlank()

                                        val confidence = if (answer.confidence.isFinite()) "%.2f".format(answer.confidence) else "n/a"
                                        val critical = layers.fields.joinToString { "${it.kind}:${it.text}" }.ifBlank { "none" }
                                        val delivery = if (queued) "queued for reliable delivery" else "local preview; receiver service unavailable"
                                        val line = "[#$segId] ${answer.language.uppercase()} (${answer.milliseconds}ms, confidence proxy $confidence)\n" +
                                            "${answer.text}\nSUTRA: $wireBytes bytes, $delivery\n" +
                                            "Critical: $critical\n\n"
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
        speakText(lastText, lastLanguage, "local-${System.nanoTime()}")
    }

    override fun onTransmissionStatus(status: String) {
        runOnUiThread { networkStatus.text = "SUTRA: $status" }
    }

    override fun onTransmissionMessage(message: SutraMessage, elapsedMs: Long?, duplicate: Boolean) {
        runOnUiThread {
            when (message.type) {
                SutraMessageType.ALERT_CORE -> {
                    val fields = runCatching { CriticalFieldCodec.decode(message.payload) }.getOrDefault(emptyList())
                    result.append("ALERT: ${fields.joinToString { "${it.kind}:${it.text}" }.ifBlank { "generic alert" }}\n")
                }
                SutraMessageType.TEXT_FINAL -> {
                    if (duplicate) result.append("Duplicate ${message.id} acknowledged without replay\n")
                    else {
                        val text = runCatching { SutraCodec.utf8(message.payload) }.getOrElse { "[invalid UTF-8]" }
                        lastText = text
                        lastLanguage = message.language.pipelineName
                        speakButton.isEnabled = text.isNotBlank()
                        result.append("Received ${message.language.pipelineName.uppercase()}: $text\n")
                    }
                }
                SutraMessageType.REPAIR_EVIDENCE -> {
                    val fields = runCatching { CriticalFieldCodec.decode(message.payload) }.getOrDefault(emptyList())
                    result.append("Uncertain critical fields: ${fields.joinToString { it.text }}\n")
                }
                SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED,
                SutraMessageType.PLAYBACK_FINISHED, SutraMessageType.REPAIR_REQUEST -> {
                    result.append("${message.type}${elapsedMs?.let { " after ${it}ms" } ?: ""}\n")
                    if (message.type == SutraMessageType.REPAIR_REQUEST) {
                        vadIndicator.text = "Peer requests repetition of uncertain critical fields"
                    }
                }
                SutraMessageType.HELLO -> Unit
            }
        }
    }

    private fun speakText(text: String, language: String, utteranceId: String): Boolean {
        if (text.isBlank()) return false
        val engine = tts ?: return false
        val tag = mapOf(
            "english" to "en-IN", "hindi" to "hi-IN", "gujarati" to "gu-IN",
            "marathi" to "mr-IN", "kannada" to "kn-IN", "malayalam" to "ml-IN",
            "tamil" to "ta-IN", "telugu" to "te-IN", "odia" to "or-IN",
            "bengali" to "bn-IN",
        )[language] ?: "en-IN"

        val locale = Locale.forLanguageTag(tag)
        val voice = engine.voices
            .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            .maxByOrNull { if (it.locale.country == locale.country) 1 else 0 }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            status.text = "No offline TTS voice installed for $language"
            return false
        }
        return engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SutraTransmissionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        transmission?.setListener(null)
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false
        transmission = null
        super.onStop()
    }

    override fun onDestroy() {
        listening = false
        audioPipeline?.stop()
        audioPipeline = null
        vadEngine?.close()
        vadEngine = null
        wifiDirect?.close()
        sttWorker.execute { pipeline?.close(); pipeline = null }
        sttWorker.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }
}
