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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity(), SutraTransmissionListener {
    private val sttWorker = Executors.newSingleThreadExecutor()
    private val pendingSegments = AtomicInteger()
    private val handler = Handler(Looper.getMainLooper())

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
    // Mesh panel
    private lateinit var meshStatus: TextView
    private lateinit var meshPeerList: LinearLayout
    private lateinit var pairingPhrase: EditText
    private lateinit var locationStatus: TextView

    private lateinit var deviceProfile: DeviceProfile
    private lateinit var modelDir: File
    private var wifiDirect: WifiDirectController? = null
    private var wifiPeers = emptyList<WifiDirectPeer>()
    private var transmission: SutraTransmissionService? = null
    private var serviceBound = false
    private var audioPipeline: AudioPipeline? = null
    private var vadEngine: SileroVadEngine? = null
    private var pipeline: OfflineSpeechPipeline? = null
    private var streamingPipeline: OfflineSpeechPipeline? = null
    private var streamingSpeech: StreamingSpeechSession? = null
    private var locationManager: LocationManager? = null
    private var myLat = 0.0
    private var myLng = 0.0

    private var segmentCount = 0
    private var lastText = ""
    private var lastLanguage = "english"
    private var sourceId = 0L
    private var alertMode = false
    @Volatile private var listening = false
    @Volatile private var routingMode = RoutingMode.AUTO
    // Flags to handle service-bind / permission race conditions
    @Volatile private var pendingBluetoothListen = false
    @Volatile private var hasPendingLocation = false

    // Auto-refresh mesh peer list every 2 seconds
    private val meshRefreshRunnable = object : Runnable {
        override fun run() {
            refreshMeshPeers()
            handler.postDelayed(this, 2000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            transmission = (binder as SutraTransmissionService.LocalBinder).service.also {
                it.setListener(this@MainActivity)
            }
            serviceBound = true
            networkStatus.text = "SUTRA receiver service ready"
            // Re-broadcast GPS fix if we already have one from before service was bound
            if (hasPendingLocation && (myLat != 0.0 || myLng != 0.0)) {
                transmission?.setMyLocation(myLat, myLng)
                hasPendingLocation = false
            }
            // Auto-join mesh — retry if a BT permission grant happened before service bound
            if (pendingBluetoothListen || ensureBluetoothPermission()) {
                pendingBluetoothListen = false
                transmission?.bluetoothListen()
                meshStatus.text = "Mesh: scanning for nearby phones…"
            }
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
        sourceId = getSharedPreferences("sutra", MODE_PRIVATE).let { prefs ->
            if (prefs.contains("terminal_id")) prefs.getLong("terminal_id", 0)
            else SecureRandom().nextLong().let { gen -> prefs.edit().putLong("terminal_id", gen).apply(); gen }
        }
        modelDir = resolveModelDir(this)
        val vadModelFile = File(modelDir, "vad/silero_vad.onnx")
        val vadInstallError = runCatching { installVadIfMissing(vadModelFile) }.exceptionOrNull()
        locationManager = getSystemService(LocationManager::class.java)

        status = TextView(this).apply {
            text = "Model folder: $modelDir\n${deviceProfile.summary()}"
            textSize = 13f; setTextColor(Color.DKGRAY)
        }

        vadIndicator = TextView(this).apply {
            text = "VAD: Idle"; textSize = 15f; setTypeface(null, Typeface.BOLD); setPadding(0, 16, 0, 8)
        }

        result = TextView(this).apply {
            textSize = 17f; setPadding(0, 16, 0, 16)
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
            text = "Speak latest result"; isEnabled = false
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

        peerAddress = EditText(this).apply { hint = "Diagnostic fallback: other phone IP"; setSingleLine() }

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
                setOnClickListener { transmission?.disconnectAll(); networkStatus.text = "SUTRA Wi-Fi: disconnected" }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        wifiPeerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            mutableListOf("No nearby SUTRA phones found"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        wifiPeerSpinner = Spinner(this).apply { adapter = wifiPeerAdapter }

        wifiDirect = runCatching { WifiDirectController(this, object : WifiDirectListener {
            override fun onWifiDirectStatus(statusText: String) {
                runOnUiThread { networkStatus.text = "Nearby SOS: $statusText" }
            }
            override fun onWifiDirectPeers(peers: List<WifiDirectPeer>) {
                runOnUiThread {
                    wifiPeers = peers
                    wifiPeerAdapter.clear()
                    wifiPeerAdapter.addAll(peers.map { "${it.name} (${it.address})" }
                        .ifEmpty { listOf("No nearby SUTRA phones found") })
                    wifiPeerAdapter.notifyDataSetChanged()
                }
            }
            override fun onWifiDirectGroup(isOwner: Boolean, ownerAddress: String?) {
                runOnUiThread {
                    networkStatus.text = if (isOwner) "Nearby SOS group created; waiting for receiver"
                    else "Nearby SOS group joined; opening local link"
                }
                if (isOwner) transmission?.wifiHost()
                else if (ownerAddress != null) transmission?.wifiConnect(ownerAddress)
            }
        }).also { it.start() } }.onFailure {
            networkStatus.text = "Wi-Fi Direct unavailable; use Bluetooth mesh"
        }.getOrNull()

        val wifiDirectButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Find nearby"
                setOnClickListener { if (ensureWifiDirectPermission()) wifiDirect?.discover() }
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
                setOnClickListener { wifiDirect?.disconnect(); transmission?.wifiDisconnect() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ── Mesh panel (replaces old Bluetooth spinner) ──────────────────────
        meshStatus = TextView(this).apply {
            text = "Mesh: starting…"; setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 4)
        }
        meshPeerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
        }
        val meshButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Join mesh"
                setOnClickListener {
                    if (ensureBluetoothPermission()) {
                        transmission?.bluetoothListen()
                        meshStatus.text = "Mesh: scanning for nearby phones…"
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Leave mesh"
                setOnClickListener { transmission?.bluetoothDisconnect(); meshStatus.text = "Mesh: disconnected" }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Scan location"
                setOnClickListener { if (ensureLocationPermission()) startLocationUpdates() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // ────────────────────────────────────────────────────────────────────

        pairingPhrase = EditText(this).apply { hint = "Shared pairing phrase (12+ characters)"; setSingleLine() }
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

        locationStatus = TextView(this).apply {
            text = "📍 Location: not yet acquired"
            setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 4)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
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
            addView(TextView(this@MainActivity).apply {
                text = "BLE Mesh — auto-connects without pairing"; setTypeface(null, Typeface.BOLD); setPadding(0, 12, 0, 4)
            })
            addView(meshStatus)
            addView(meshPeerList)
            addView(meshButtons)
            addView(locationStatus)
            addView(pairingPhrase)
            addView(securityAndMetrics)
            addView(TextView(this@MainActivity).apply { text = "Manual IP fallback (diagnostics only)" })
            addView(peerAddress)
            addView(networkButtons)
            addView(result)
        }
        setContentView(ScrollView(this).apply { addView(page) })

        val serviceIntent = Intent(this, SutraTransmissionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        }
        // Request location permission on startup for mesh GPS sharing
        ensureLocationPermission()

        val modelError = runCatching { validateModelBundle(modelDir) }.exceptionOrNull()
        if (modelError != null || vadInstallError != null || !vadModelFile.isFile) {
            startStopButton.isEnabled = false
            status.text = "Models missing or incomplete: ${modelError?.message ?: vadInstallError?.message ?: "Silero VAD missing"}\n" +
                "Copy artifacts/android_models over USB.\n$modelDir"
        } else {
            status.text = "Models ready (${deviceProfile.summary()}). Tap Start listening."
        }
    }

    // ── Location ────────────────────────────────────────────────────────────

    private fun ensureLocationPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 23) Manifest.permission.ACCESS_FINE_LOCATION
                   else return true
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            return true
        }
        requestPermissions(arrayOf(perm), 5)
        return false
    }

    private fun startLocationUpdates() {
        val lm = locationManager ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                myLat = loc.latitude; myLng = loc.longitude
                val svc = transmission
                if (svc != null) svc.setMyLocation(myLat, myLng)
                else hasPendingLocation = true
                runOnUiThread {
                    val provider = if (loc.provider == LocationManager.GPS_PROVIDER) "GPS" else "Network"
                    locationStatus.text = "📍 $provider: %.5f, %.5f (±%.0fm)".format(
                        loc.latitude, loc.longitude, loc.accuracy)
                }
            }
        }
        // Try GPS first, fall back to network
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15_000L, 50f, locListener) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15_000L, 50f, locListener) }
        locationStatus.text = "📍 Waiting for GPS fix…"
        // Use last known immediately
        runCatching {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { loc ->
                myLat = loc.latitude; myLng = loc.longitude
                val svc = transmission
                if (svc != null) svc.setMyLocation(myLat, myLng)
                else hasPendingLocation = true
                val provider = if (loc.provider == LocationManager.GPS_PROVIDER) "GPS" else "Network"
                locationStatus.text = "📍 Last known ($provider): %.5f, %.5f".format(loc.latitude, loc.longitude)
            }
        }
    }

    // ── Mesh peer list ───────────────────────────────────────────────────────

    private fun refreshMeshPeers() {
        val svc = transmission ?: return
        val peers = runCatching { svc.meshPeers() }.getOrDefault(emptyList())
        meshPeerList.removeAllViews()
        if (peers.isEmpty()) {
            meshPeerList.addView(TextView(this).apply {
                text = "  No phones connected yet — tap Join mesh"
                setTextColor(Color.GRAY); setPadding(0, 4, 0, 4)
            })
            meshStatus.text = "Mesh: scanning…"
            return
        }
        meshStatus.text = "Mesh: ${peers.size} phone${if (peers.size == 1) "" else "s"} connected"
        peers.forEach { peer ->
            val locText = when {
                peer.distanceMetres != null -> "%.0f m away".format(peer.distanceMetres)
                peer.lat != null -> "(%.4f, %.4f)".format(peer.lat, peer.lng)
                else -> "location unknown"
            }
            meshPeerList.addView(TextView(this).apply {
                text = "  📱 ${peer.name.take(22)}  |  $locText"
                setTextColor(Color.rgb(0, 100, 0)); setPadding(0, 4, 0, 4)
            })
        }
    }

    // ── Permission helpers ───────────────────────────────────────────────────

    private fun ensureWifiDirectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                   else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(perm), 2)
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
        meshStatus.text = "Grant Nearby devices permission, then retry"
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            3 -> if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val svc = transmission
                if (svc != null) {
                    svc.bluetoothListen()
                    meshStatus.text = "Mesh: scanning for nearby phones…"
                } else {
                    // Service not yet bound — set flag so onServiceConnected picks it up
                    pendingBluetoothListen = true
                    meshStatus.text = "Mesh: will start when service is ready…"
                }
            }
            5 -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            }
        }
    }

    // ── VAD model install ────────────────────────────────────────────────────

    private fun installVadIfMissing(target: File) {
        if (target.isFile) return
        val dir = requireNotNull(target.parentFile)
        require(dir.isDirectory || dir.mkdirs()) { "Could not create VAD model directory" }
        val tmp = File(dir, "${target.name}.installing")
        assets.open("vad/silero_vad.onnx").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        require(tmp.length() > 1_000_000) { "Bundled Silero VAD is incomplete" }
        if (target.exists()) target.delete()
        require(tmp.renameTo(target)) { "Could not install bundled Silero VAD" }
    }

    // ── Listening ────────────────────────────────────────────────────────────

    private fun toggleListening() {
        val activePipeline = audioPipeline
        if (activePipeline == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return
            }
            val vadModel = File(modelDir, "vad/silero_vad.onnx")
            if (!vadModel.isFile) { vadIndicator.text = "Error: Silero VAD model missing at ${vadModel.path}"; return }

            segmentCount = 0; result.text = ""

            val liveSession = StreamingSpeechSession(
                pipelineFactory = { streamingPipeline ?: OfflineSpeechPipeline(modelDir).also { streamingPipeline = it } },
                routingMode = routingMode,
                send = { packet -> transmission?.sendVoiceBurst(packet) },
                onFinished = { streamingPipeline?.close(); streamingPipeline = null },
            )
            streamingSpeech = liveSession; liveSession.start()

            try {
                val engine = SileroVadEngine(
                    modelFile = vadModel,
                    config = deviceProfile.vadConfig,
                    listener = object : VadListener {
                        override fun onSpeechStart() { runOnUiThread { vadIndicator.text = "Speaking detected..." } }
                        override fun onSpeechEnd() { runOnUiThread { vadIndicator.text = "Pause detected, segmenting..." } }
                        override fun onSpeechSegment(audio: FloatArray) {
                            val segId = ++segmentCount
                            val transmissionId = SutraId.random()
                            val mode = routingMode; val isAlert = alertMode
                            if (pendingSegments.incrementAndGet() > 2) { pendingSegments.decrementAndGet(); return }
                            runOnUiThread { vadIndicator.text = "Transcribing segment #$segId (${"%.1f".format(audio.size / 16000.0)}s)..." }
                            transmission?.recordMetric(transmissionId.toString(), "speech_end", "segment=$segId")
                            sttWorker.execute {
                                try {
                                    val sttEngine = pipeline ?: OfflineSpeechPipeline(modelDir).also { pipeline = it }
                                    val answer = sttEngine.transcribe(audio, mode)
                                    val layers = SutraSemanticEncoder.build(
                                        language = answer.language, text = answer.text,
                                        confidence = answer.confidence.takeIf(Double::isFinite) ?: 0.5,
                                        alert = isAlert, sourceId = sourceId, id = transmissionId,
                                    )
                                    val wireBytes = layers.messages.sumOf { SutraCodec.encode(it).size }
                                    val queued = runCatching { requireNotNull(transmission).send(layers.messages) }.isSuccess
                                    Log.i("SIH_METRIC", "segment=$segId mode=$mode language=${answer.language} " +
                                        "backend=${answer.backend} inference_ms=${answer.milliseconds} queued=$queued")
                                    runOnUiThread {
                                        lastText = answer.text; lastLanguage = answer.language
                                        speakButton.isEnabled = answer.text.isNotBlank()
                                        val conf = if (answer.confidence.isFinite()) "%.2f".format(answer.confidence) else "n/a"
                                        val delivery = if (queued) "queued" else "preview (service unavailable)"
                                        val line = "[#$segId] ${answer.language.uppercase()} (${answer.milliseconds}ms, conf $conf)\n" +
                                            "${answer.text}\nSUTRA: $wireBytes bytes, $delivery\n\n"
                                        result.append(line)
                                        vadIndicator.text = if (listening) "Listening (Segment #$segId done)" else "Stopped"
                                    }
                                } catch (error: Exception) {
                                    runOnUiThread { vadIndicator.text = "Segment #$segId error: ${error.message}" }
                                } finally { pendingSegments.decrementAndGet() }
                            }
                        }
                    }
                )
                vadEngine = engine
                val silence = FloatArray(AudioPipeline.CHUNK_SIZE)
                val cap = AudioPipeline(
                    context = this@MainActivity,
                    listener = AudioChunkListener { chunk ->
                        engine.processChunk(if (AudioDuplexGate.isPlaybackActive) silence else chunk)
                    },
                    streamingListener = StreamingAudioChunkListener { chunk, ts ->
                        if (!AudioDuplexGate.isPlaybackActive) liveSession.submit(chunk, ts)
                    },
                )
                cap.start(); audioPipeline = cap; listening = true
                startStopButton.text = "Stop listening"
                vadIndicator.text = "Listening (${cap.sampleRate} Hz mic, VAD active)"
            } catch (error: Exception) { vadIndicator.text = "Failed to start: ${error.message}" }
        } else {
            listening = false
            try { activePipeline.stop() } catch (_: Exception) {}
            audioPipeline = null
            streamingSpeech?.finish(); streamingSpeech = null
            vadEngine?.flush(); vadEngine?.close(); vadEngine = null
            startStopButton.text = "Start continuous listening"
            vadIndicator.text = "Stopped listening"
        }
    }

    private fun speakResult() {
        if (transmission?.speakLocal(lastText, lastLanguage) != true) status.text = "TTS service is not ready"
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
                        lastText = text; lastLanguage = message.language.pipelineName
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
                    if (message.type == SutraMessageType.REPAIR_REQUEST)
                        vadIndicator.text = "Peer requests repetition of uncertain critical fields"
                }
                SutraMessageType.HELLO -> Unit
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SutraTransmissionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        handler.postDelayed(meshRefreshRunnable, 2000)
    }

    override fun onStop() {
        handler.removeCallbacks(meshRefreshRunnable)
        transmission?.setListener(null)
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false; transmission = null
        super.onStop()
    }

    override fun onDestroy() {
        listening = false
        audioPipeline?.stop(); audioPipeline = null
        streamingSpeech?.finish(canceled = true); streamingSpeech = null
        vadEngine?.close(); vadEngine = null
        wifiDirect?.close()
        sttWorker.execute { pipeline?.close(); pipeline = null }
        sttWorker.shutdown()
        super.onDestroy()
    }
}
