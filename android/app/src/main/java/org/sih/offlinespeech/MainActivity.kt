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
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
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
    // ── Modern Dark Palette ──────────────────────────────────────────────────
    private object Theme {
        val BG = 0xFF0B0F19.toInt()
        val CARD_BG = 0xFF161E2E.toInt()
        val CARD_BORDER = 0xFF25334D.toInt()
        val INNER_SURFACE = 0xFF0F172A.toInt()
        val INNER_BORDER = 0xFF1E293B.toInt()
        val TEXT_PRIMARY = 0xFFF8FAFC.toInt()
        val TEXT_SECONDARY = 0xFF94A3B8.toInt()
        val TEXT_MUTED = 0xFF64748B.toInt()
        val EMERALD = 0xFF10B981.toInt()
        val EMERALD_DARK = 0xFF064E3B.toInt()
        val EMERALD_LIGHT = 0xFF34D399.toInt()
        val CRIMSON = 0xFFEF4444.toInt()
        val CRIMSON_DARK = 0xFF7F1D1D.toInt()
        val AMBER = 0xFFF59E0B.toInt()
        val AMBER_DARK = 0xFF78350F.toInt()
        val AMBER_LIGHT = 0xFFFDE68A.toInt()
        val BLUE = 0xFF3B82F6.toInt()
        val BLUE_DARK = 0xFF1E3A8A.toInt()
        val BLUE_LIGHT = 0xFFBFDBFE.toInt()
        val CYAN = 0xFF38BDF8.toInt()
        val INDIGO = 0xFF4F46E5.toInt()
        val VIOLET = 0xFF8B5CF6.toInt()
        val SLATE_BTN = 0xFF1E293B.toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(
        bgColor: Int,
        cornerRadiusDp: Float = 12f,
        borderColor: Int? = null,
        borderWidthDp: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = dp(cornerRadiusDp.toInt()).toFloat()
            if (borderColor != null) {
                setStroke(dp(borderWidthDp.toInt()), borderColor)
            }
        }
    }

    private fun createCard(paddingDp: Int = 14, bottomMarginDp: Int = 12): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Theme.CARD_BG, 16f, Theme.CARD_BORDER, 1f)
            val p = dp(paddingDp)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(bottomMarginDp)
            }
        }
    }

    private fun createPillButton(
        title: String,
        bgColor: Int,
        textColor: Int = Color.WHITE,
        heightDp: Int = 40,
        cornerRadiusDp: Float = 10f
    ): Button {
        return Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = roundedDrawable(bgColor, cornerRadiusDp)
            minHeight = dp(heightDp)
            minimumHeight = dp(heightDp)
            val padH = dp(10)
            val padV = dp(6)
            setPadding(padH, padV, padH, padV)
            stateListAnimator = null
        }
    }

    private fun createSectionHeader(icon: String, title: String, subtitle: String? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = icon
                    textSize = 15f
                    setPadding(0, 0, dp(8), 0)
                })
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.TEXT_PRIMARY)
                    letterSpacing = 0.04f
                })
            }
            addView(titleRow)
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(Theme.TEXT_SECONDARY)
                    setPadding(dp(22), dp(2), 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun createInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Theme.TEXT_MUTED)
            setTextColor(Theme.TEXT_PRIMARY)
            textSize = 13f
            setSingleLine()
            background = roundedDrawable(Theme.BG, 10f, Theme.INNER_BORDER, 1f)
            val p = dp(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

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
                meshStatus.setTextColor(Theme.CYAN)
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

        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG

        deviceProfile = DeviceProfile.detect(this)
        sourceId = getSharedPreferences("sutra", MODE_PRIVATE).let { prefs ->
            if (prefs.contains("terminal_id")) prefs.getLong("terminal_id", 0)
            else SecureRandom().nextLong().let { gen -> prefs.edit().putLong("terminal_id", gen).apply(); gen }
        }
        modelDir = resolveModelDir(this)
        val vadModelFile = File(modelDir, "vad/silero_vad.onnx")
        val vadInstallError = runCatching { installVadIfMissing(vadModelFile) }.exceptionOrNull()
        locationManager = getSystemService(LocationManager::class.java)

        // ── Top Header Bar ──────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            addView(TextView(this@MainActivity).apply {
                text = "⚡"
                textSize = 22f
                setPadding(0, 0, dp(10), 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "SUTRA VOICE MESH"
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.TEXT_PRIMARY)
                    letterSpacing = 0.05f
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Offline Speech & Emergency SOS Network"
                    textSize = 11.5f
                    setTextColor(Theme.TEXT_SECONDARY)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        locationStatus = TextView(this).apply {
            text = "📍 Waiting for GPS fix…"
            textSize = 12f
            setTextColor(Theme.CYAN)
            background = roundedDrawable(Theme.CARD_BG, 10f, Theme.CARD_BORDER, 1f)
            val p = dp(8)
            setPadding(p, dp(6), p, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        status = TextView(this).apply {
            text = "Model: $modelDir\n${deviceProfile.summary()}"
            textSize = 11f
            setTextColor(Theme.TEXT_MUTED)
            setPadding(0, 0, 0, dp(8))
        }

        // ── Voice Hero Section ──────────────────────────────────────────────
        startStopButton = Button(this).apply {
            text = "🎙️  START LISTENING"
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedDrawable(Theme.EMERALD, 24f)
            minHeight = dp(50)
            minimumHeight = dp(50)
            stateListAnimator = null
            setOnClickListener { toggleListening() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        vadIndicator = TextView(this).apply {
            text = "⚪  VAD: Idle"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.TEXT_SECONDARY)
            background = roundedDrawable(Theme.INNER_SURFACE, 10f, Theme.INNER_BORDER, 1f)
            val pH = dp(12)
            val pV = dp(7)
            setPadding(pH, pV, pH, pV)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        routeButton = createPillButton("Routing: AUTO", Theme.SLATE_BTN, Theme.TEXT_PRIMARY, heightDp = 38).apply {
            setOnClickListener {
                routingMode = RoutingMode.entries[(routingMode.ordinal + 1) % RoutingMode.entries.size]
                text = "Routing: $routingMode"
            }
        }

        alertButton = createPillButton("🚨 SOS: OFF", Theme.SLATE_BTN, Theme.TEXT_SECONDARY, heightDp = 38).apply {
            setOnClickListener {
                alertMode = !alertMode
                if (alertMode) {
                    text = "🚨 SOS: ACTIVE"
                    background = roundedDrawable(Theme.CRIMSON, 10f)
                    setTextColor(Color.WHITE)
                } else {
                    text = "🚨 SOS: OFF"
                    background = roundedDrawable(Theme.SLATE_BTN, 10f)
                    setTextColor(Theme.TEXT_SECONDARY)
                }
                if (alertMode && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                    networkStatus.text = "Alert enabled, but DND access is not granted; playback may be suppressed"
                }
            }
        }

        speakButton = createPillButton("🔊 Speak", Theme.SLATE_BTN, Theme.TEXT_MUTED, heightDp = 38).apply {
            isEnabled = false
            setOnClickListener { speakResult() }
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            val lpLast = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(routeButton, lp)
            addView(alertButton, lp)
            addView(speakButton, lpLast)
        }

        val voiceCard = createCard().apply {
            addView(createSectionHeader("🎙️", "VOICE COMMUNICATOR", "Push-to-listen offline speech translator"))
            addView(startStopButton)
            addView(vadIndicator)
            addView(modeRow)
        }

        // ── Transcripts & Logs ──────────────────────────────────────────────
        result = TextView(this).apply {
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Theme.TEXT_PRIMARY)
            setLineSpacing(dp(3).toFloat(), 1f)
            text = "Transcribed speech and mesh packets appear here.\n"
        }

        networkStatus = TextView(this).apply {
            val addresses = TcpPeerTransport.localIpv4Addresses().joinToString().ifBlank { "unavailable" }
            text = "SUTRA Wi-Fi: disconnected | IP: $addresses:${TcpPeerTransport.PORT}"
            textSize = 11.5f
            setTextColor(Theme.TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        }

        val transcriptCard = createCard().apply {
            addView(createSectionHeader("💬", "TRANSCRIPTS & MESH FEED", "Local translations and mesh packets"))
            val terminalBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(Theme.INNER_SURFACE, 10f, Theme.INNER_BORDER, 1f)
                val p = dp(10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                addView(result)
            }
            addView(terminalBox)
            addView(networkStatus)
        }

        // ── Mesh Network Section ────────────────────────────────────────────
        meshStatus = TextView(this).apply {
            text = "Mesh: starting…"
            textSize = 12f
            setTextColor(Theme.CYAN)
            setPadding(0, dp(2), 0, dp(6))
        }

        meshPeerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }

        val meshButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            val lpLast = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(createPillButton("Join mesh", Theme.BLUE, heightDp = 38).apply {
                setOnClickListener {
                    if (ensureBluetoothPermission()) {
                        transmission?.bluetoothListen()
                        meshStatus.text = "Mesh: scanning for nearby phones…"
                        meshStatus.setTextColor(Theme.CYAN)
                    }
                }
            }, lp)
            addView(createPillButton("Leave mesh", Theme.SLATE_BTN, heightDp = 38).apply {
                setOnClickListener {
                    transmission?.bluetoothDisconnect()
                    meshStatus.text = "Mesh: disconnected"
                    meshStatus.setTextColor(Theme.TEXT_MUTED)
                }
            }, lp)
            addView(createPillButton("Scan GPS", Theme.INDIGO, heightDp = 38).apply {
                setOnClickListener { if (ensureLocationPermission()) startLocationUpdates() }
            }, lpLast)
        }

        val meshCard = createCard().apply {
            addView(createSectionHeader("📡", "BLE MESH NETWORK", "Decentralized offline peer-to-peer"))
            addView(meshStatus)
            addView(meshPeerList)
            addView(meshButtons)
        }

        // ── Wi-Fi Direct Section ────────────────────────────────────────────
        wifiPeerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            mutableListOf("No nearby SUTRA phones found"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        wifiPeerSpinner = Spinner(this).apply {
            adapter = wifiPeerAdapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
        }

        val spinnerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedDrawable(Theme.INNER_SURFACE, 10f, Theme.INNER_BORDER, 1f)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            addView(wifiPeerSpinner)
        }

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
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            val lpLast = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(createPillButton("Find nearby", Theme.INDIGO, heightDp = 38).apply {
                setOnClickListener { if (ensureWifiDirectPermission()) wifiDirect?.discover() }
            }, lp)
            addView(createPillButton("Connect", Theme.BLUE, heightDp = 38).apply {
                setOnClickListener {
                    val peer = wifiPeers.getOrNull(wifiPeerSpinner.selectedItemPosition)
                    runCatching { requireNotNull(wifiDirect).connect(peer?.address ?: "") }
                        .onFailure { networkStatus.text = it.message }
                }
            }, lp)
            addView(createPillButton("Disconnect", Theme.SLATE_BTN, heightDp = 38).apply {
                setOnClickListener { wifiDirect?.disconnect(); transmission?.wifiDisconnect() }
            }, lpLast)
        }

        val wifiDirectCard = createCard().apply {
            addView(createSectionHeader("📶", "WI-FI DIRECT SOS", "High-speed local link without router"))
            addView(spinnerBox)
            addView(wifiDirectButtons)
        }

        // ── Security & Diagnostics Section ──────────────────────────────────
        pairingPhrase = createInput("Shared pairing phrase (12+ characters)")
        val securityAndMetrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            val lpLast = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(createPillButton("Secure session", Theme.INDIGO, heightDp = 38).apply {
                setOnClickListener {
                    runCatching { requireNotNull(transmission).setPairingPhrase(pairingPhrase.text.toString()) }
                        .onFailure { networkStatus.text = it.message }
                }
            }, lp)
            addView(createPillButton("Export metrics", Theme.SLATE_BTN, heightDp = 38).apply {
                setOnClickListener {
                    runCatching { requireNotNull(transmission).exportMetrics() }
                        .onSuccess { (file, rows) -> networkStatus.text = "Exported $rows events to ${file.path}" }
                        .onFailure { networkStatus.text = it.message }
                }
            }, lpLast)
        }

        peerAddress = createInput("Diagnostic fallback: other phone IP")
        val networkButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            val lpLast = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(createPillButton("Manual host", Theme.SLATE_BTN, heightDp = 38).apply {
                setOnClickListener { transmission?.wifiHost() }
            }, lp)
            addView(createPillButton("Connect IP", Theme.SLATE_BTN, heightDp = 38).apply {
                setOnClickListener {
                    runCatching { requireNotNull(transmission).wifiConnect(peerAddress.text.toString()) }
                        .onFailure { networkStatus.text = it.message }
                }
            }, lp)
            addView(createPillButton("Disconnect", Theme.SLATE_BTN, heightDp = 38).apply {
                setOnClickListener { transmission?.disconnectAll(); networkStatus.text = "SUTRA Wi-Fi: disconnected" }
            }, lpLast)
        }

        val diagnosticsCard = createCard().apply {
            addView(createSectionHeader("🔒", "SECURITY & DIAGNOSTICS", "Shared AES key, metrics & IP fallback"))
            addView(pairingPhrase)
            addView(securityAndMetrics)
            addView(TextView(this@MainActivity).apply {
                text = "Diagnostic IP fallback:"
                textSize = 11.5f
                setTextColor(Theme.TEXT_MUTED)
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(peerAddress)
            addView(networkButtons)
        }

        // ── Main Scroll View Assembly ───────────────────────────────────────
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, dp(16), p, dp(24))
            addView(header)
            addView(locationStatus)
            addView(status)
            addView(voiceCard)
            addView(transcriptCard)
            addView(meshCard)
            addView(wifiDirectCard)
            addView(diagnosticsCard)
        }

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            addView(page)
        }
        setContentView(rootScroll)

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
            startStopButton.background = roundedDrawable(Theme.SLATE_BTN, 24f)
            status.text = "Models missing or incomplete: ${modelError?.message ?: vadInstallError?.message ?: "Silero VAD missing"}\n" +
                "Copy artifacts/android_models over USB.\n$modelDir"
            status.setTextColor(Theme.AMBER)
        } else {
            status.text = "Models ready (${deviceProfile.summary()}). Tap Start listening."
            status.setTextColor(Theme.EMERALD_LIGHT)
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
                    locationStatus.setTextColor(Theme.CYAN)
                }
            }
        }
        // Try GPS first, fall back to network
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15_000L, 50f, locListener) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15_000L, 50f, locListener) }
        locationStatus.text = "📍 Waiting for GPS fix…"
        locationStatus.setTextColor(Theme.AMBER)
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
                locationStatus.setTextColor(Theme.CYAN)
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
                text = "📡  No mesh peers detected nearby — tap Join Mesh"
                setTextColor(Theme.TEXT_MUTED)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
            })
            meshStatus.text = "Mesh: scanning for nearby phones…"
            meshStatus.setTextColor(Theme.CYAN)
            return
        }
        meshStatus.text = "🟢  ${peers.size} Node${if (peers.size == 1) "" else "s"} Active in Mesh"
        meshStatus.setTextColor(Theme.EMERALD_LIGHT)
        peers.forEach { peer ->
            val locText = when {
                peer.distanceMetres != null -> "%.0f m away".format(peer.distanceMetres)
                peer.lat != null -> "(%.4f, %.4f)".format(peer.lat, peer.lng)
                else -> "location unknown"
            }
            val peerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(Theme.INNER_SURFACE, 10f, Theme.INNER_BORDER, 1f)
                val p = dp(10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                addView(TextView(this@MainActivity).apply {
                    text = "📱"
                    textSize = 16f
                    setPadding(0, 0, dp(8), 0)
                })
                addView(TextView(this@MainActivity).apply {
                    text = peer.name.take(22)
                    setTextColor(Theme.TEXT_PRIMARY)
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = locText
                    setTextColor(Theme.EMERALD_LIGHT)
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    background = roundedDrawable(Theme.EMERALD_DARK, 6f)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                })
            }
            meshPeerList.addView(peerRow)
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
                        override fun onSpeechStart() {
                            runOnUiThread {
                                vadIndicator.text = "🔊  Speaking detected..."
                                vadIndicator.setTextColor(Theme.AMBER_LIGHT)
                                vadIndicator.background = roundedDrawable(Theme.AMBER_DARK, 10f, Theme.AMBER, 1f)
                            }
                        }
                        override fun onSpeechEnd() {
                            runOnUiThread {
                                vadIndicator.text = "⏳  Pause detected, segmenting..."
                                vadIndicator.setTextColor(Theme.AMBER_LIGHT)
                                vadIndicator.background = roundedDrawable(Theme.AMBER_DARK, 10f, Theme.AMBER, 1f)
                            }
                        }
                        override fun onSpeechSegment(audio: FloatArray) {
                            val segId = ++segmentCount
                            val transmissionId = SutraId.random()
                            val mode = routingMode; val isAlert = alertMode
                            if (pendingSegments.incrementAndGet() > 2) { pendingSegments.decrementAndGet(); return }
                            runOnUiThread {
                                vadIndicator.text = "⚡  Transcribing segment #$segId (${"%.1f".format(audio.size / 16000.0)}s)..."
                                vadIndicator.setTextColor(Theme.BLUE_LIGHT)
                                vadIndicator.background = roundedDrawable(Theme.BLUE_DARK, 10f, Theme.BLUE, 1f)
                            }
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
                                        if (speakButton.isEnabled) {
                                            speakButton.background = roundedDrawable(Theme.VIOLET, 10f)
                                            speakButton.setTextColor(Color.WHITE)
                                        }
                                        val conf = if (answer.confidence.isFinite()) "%.2f".format(answer.confidence) else "n/a"
                                        val delivery = if (queued) "queued" else "preview (service unavailable)"
                                        val line = "[#$segId] ${answer.language.uppercase()} (${answer.milliseconds}ms, conf $conf)\n" +
                                            "${answer.text}\nSUTRA: $wireBytes bytes, $delivery\n\n"
                                        result.append(line)
                                        vadIndicator.text = if (listening) "Listening (Segment #$segId done)" else "Stopped"
                                        if (listening) {
                                            vadIndicator.setTextColor(Theme.EMERALD_LIGHT)
                                            vadIndicator.background = roundedDrawable(Theme.EMERALD_DARK, 10f, Theme.EMERALD, 1f)
                                        }
                                    }
                                } catch (error: Exception) {
                                    runOnUiThread {
                                        vadIndicator.text = "Segment #$segId error: ${error.message}"
                                        vadIndicator.setTextColor(Theme.CRIMSON)
                                    }
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
                startStopButton.text = "⏹️  STOP LISTENING"
                startStopButton.background = roundedDrawable(Theme.CRIMSON, 24f)
                vadIndicator.text = "Listening (${cap.sampleRate} Hz mic, VAD active)"
                vadIndicator.setTextColor(Theme.EMERALD_LIGHT)
                vadIndicator.background = roundedDrawable(Theme.EMERALD_DARK, 10f, Theme.EMERALD, 1f)
            } catch (error: Exception) {
                vadIndicator.text = "Failed to start: ${error.message}"
                vadIndicator.setTextColor(Theme.CRIMSON)
            }
        } else {
            listening = false
            try { activePipeline.stop() } catch (_: Exception) {}
            audioPipeline = null
            streamingSpeech?.finish(); streamingSpeech = null
            vadEngine?.flush(); vadEngine?.close(); vadEngine = null
            startStopButton.text = "🎙️  START LISTENING"
            startStopButton.background = roundedDrawable(Theme.EMERALD, 24f)
            vadIndicator.text = "⚪  VAD: Idle"
            vadIndicator.setTextColor(Theme.TEXT_SECONDARY)
            vadIndicator.background = roundedDrawable(Theme.INNER_SURFACE, 10f, Theme.INNER_BORDER, 1f)
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
                        if (speakButton.isEnabled) {
                            speakButton.background = roundedDrawable(Theme.VIOLET, 10f)
                            speakButton.setTextColor(Color.WHITE)
                        }
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
