package org.sih.offlinespeech

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity(), SutraTransmissionListener {
    // Body content (cards, text, backgrounds) adapts to the system theme; the header/nav chrome stays a fixed dark navy.
    private val theme by lazy { SutraPalette.of(this) }
    private val ink by lazy { theme.ink }
    private val inkPressed by lazy { theme.inkPressed }
    private val mutedInk by lazy { theme.muted }
    private val accent by lazy { theme.accent }
    private val success by lazy { theme.teal }
    private val blue by lazy { theme.blue }
    private val amber by lazy { theme.amber }
    private val surface by lazy { theme.surface }
    private val canvas by lazy { theme.paper }
    private val mint by lazy { theme.mint }
    private val divider by lazy { theme.line }
    private val sttWorker = Executors.newSingleThreadExecutor()
    /** Warm-up timing recorded before the service bound, flushed in [serviceConnection]. */
    private val pendingWarmMetric = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val pendingSegments = AtomicInteger()

    private lateinit var status: TextView
    private lateinit var discoveryStatus: TextView
    private lateinit var connectionTitle: TextView
    private lateinit var linkCounts: TextView
    private lateinit var nearbyList: LinearLayout
    private lateinit var searchButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var connectionHint: TextView
    private lateinit var conversationView: ConversationView
    private lateinit var conversationScroll: ScrollView
    private lateinit var emptyConversation: View
    private lateinit var tapRecordButton: Button
    private lateinit var cancelRecordingButton: Button
    private lateinit var emergencyBanner: View
    private lateinit var conversationHeading: View
    private lateinit var conversationStack: LinearLayout
    private lateinit var heroArt: ImageView
    private var renderedPeerRows: List<String>? = null
    private var recordWithTap = false
    private var latestWifiStatus = ""
    private var lastConversationKey: String? = null
    private var conversationLoaded = false
    private val heardMessages = mutableSetOf<String>()
    private lateinit var vadIndicator: TextView
    private lateinit var result: TextView
    private lateinit var latestTranscript: TextView
    private lateinit var translatedTranscript: TextView
    private lateinit var transcriptMeta: TextView
    private lateinit var pushToTalkHint: TextView
    private lateinit var mapController: MapPageController
    private var preferredLanguage: String = "english"
    private lateinit var startStopButton: Button
    private lateinit var pushToTalkButton: RecordingButton
    private lateinit var normalButton: Button
    private lateinit var talkReplayButton: Button
    private lateinit var talkCopyButton: Button
    private lateinit var talkQuickActionsRow: LinearLayout
    private lateinit var routeButton: Button
    private lateinit var speakButton: Button
    private lateinit var emphasisWord: EditText
    private lateinit var alertButton: Button
    private lateinit var networkStatus: TextView
    private lateinit var peerAddress: EditText
    private lateinit var wifiPeerAdapter: ArrayAdapter<String>
    private lateinit var wifiPeerSpinner: Spinner
    private lateinit var bluetoothPeerAdapter: ArrayAdapter<String>
    private lateinit var bluetoothPeerSpinner: Spinner
    private lateinit var proximityMap: ProximityMapView
    private lateinit var proximityStatus: TextView
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
    private val pushToTalkLock = Any()
    private val pushToTalkChunks = ArrayList<FloatArray>()

    private var segmentCount = 0
    private var lastText = ""
    private var lastLanguage = "english"
    private var sourceId = 0L
    private var nearbyPermissionRequested = false
    private var bluetoothSetupRequested = false
    private var alertMode = false
    private var alertCategory: String? = null
    private var pushToTalkStartedAt = 0L
    @Volatile private var listening = false
    @Volatile private var pushToTalkRecording = false
    @Volatile private var routingMode = RoutingMode.AUTO

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            transmission = (binder as SutraTransmissionService.LocalBinder).service.also {
                it.setListener(this@MainActivity)
            }
            serviceBound = true
            // Warm-up finishes before the service binds, so its timing is buffered until there is
            // a metrics store to put it in; without this the row never reaches the exported CSV.
            pendingWarmMetric.getAndSet(null)?.let { detail ->
                transmission?.recordMetric(null, "stt_warm", detail)
            }
            wifiDirect = transmission?.wifiDirect
            refreshBluetoothPeers()
            val active = transmission?.isSecurityActive() == true
            networkStatus.text = "SUTRA receiver service ready • ${securityStatusLabel(active)}"
            updateConnectionLine(transmission?.connectionSummary().orEmpty())
            onConversationChanged()
            prepareAutomaticConnections()
            mapController.syncSharing()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            transmission = null
            updateConnectionLine("Connection service stopped")
        }
    }

    /**
     * Builds the ONNX speech sessions during startup rather than on the first utterance.
     *
     * Runs on [sttWorker] — the same single thread that later transcribes — so warming can never
     * overlap a real transcription, and the UI thread is untouched. Everything is wrapped: a missing
     * or invalid model bundle leaves [pipeline] null and the first transcription takes the original
     * cold path, exactly as before, so this cannot turn a working install into a broken one.
     */
    private fun warmSpeechPipeline() {
        val mode = routingMode
        val target = modelDir
        // Only pre-load the large Indic session where there is memory for it. On a low-RAM device
        // AUTO keeps reaching it lazily, as it did before warming existed, so warming can never be
        // the reason a small phone runs out of memory.
        val activityManager = getSystemService(ActivityManager::class.java)
        val roomForIndic = activityManager?.let { manager ->
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            !manager.isLowRamDevice && info.totalMem >= 3L * 1024 * 1024 * 1024
        } ?: false
        sttWorker.execute {
            runCatching {
                val engine = pipeline ?: OfflineSpeechPipeline(target).also { pipeline = it }
                engine.warmUp(mode, includeIndic = roomForIndic)
            }.onSuccess { millis ->
                val detail = "ms=$millis,mode=$mode,indic=$roomForIndic"
                val store = transmission
                if (store != null) store.recordMetric(null, "stt_warm", detail) else pendingWarmMetric.set(detail)
                Log.i("SIH_METRIC", "stt_warm ms=$millis mode=$mode indic=$roomForIndic")
            }.onFailure {
                // Not fatal: the first transcription will retry and surface a real error there.
                Log.w("SIH_METRIC", "stt_warm skipped: ${it.message}")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = SutraPalette.chromeBackground
        window.navigationBarColor = surface
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        deviceProfile = DeviceProfile.detect(this)
        preferredLanguage = getSharedPreferences("sutra", MODE_PRIVATE).getString("preferred_language", "english") ?: "english"
        mapController = MapPageController(this) { fix -> transmission?.updateSharedFix(fix) }
        sourceId = getSharedPreferences("sutra", MODE_PRIVATE).let { preferences ->
            if (preferences.contains("terminal_id")) preferences.getLong("terminal_id", 0)
            else SecureRandom().nextLong().let { generated ->
                preferences.edit().putLong("terminal_id", generated).apply(); generated
            }
        }
        modelDir = resolveModelDir(this)
        routingMode = getSharedPreferences("sutra", MODE_PRIVATE)
            .getString("routing_mode", RoutingMode.AUTO.name)
            ?.let { saved -> RoutingMode.entries.firstOrNull { it.name == saved } }
            ?: RoutingMode.AUTO
        warmSpeechPipeline()
        val vadModelFile = File(modelDir, "vad/silero_vad.onnx")
        val vadInstallError = runCatching { installVadIfMissing(vadModelFile) }.exceptionOrNull()

        status = TextView(this).apply {
            text = "Model folder: $modelDir\n${deviceProfile.summary()}"
            textSize = 10.5f
            setTextColor(mutedInk)
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }

        vadIndicator = TextView(this).apply {
            text = "Ready to talk"
            textSize = 13.5f
            setTextColor(mutedInk)
            gravity = Gravity.CENTER
            minHeight = dp(24)
            setPadding(0, dp(4), 0, 0)
        }

        result = TextView(this).apply {
            textSize = 15.5f
            setTextColor(ink)
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
            text = "Your sent and received messages will appear here."
        }

        transcriptMeta = TextView(this).apply {
            text = "Every voice. Closer."
            textSize = 30f
            letterSpacing = -0.025f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
        }

        latestTranscript = TextView(this).apply {
            text = "Speak in your language.\nStart a conversation with your nearby team."
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setTextColor(mutedInk)
            setPadding(0, dp(9), 0, dp(4))
        }

        translatedTranscript = TextView(this).apply {
            text = ""
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setTextColor(blue)
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(4))
        }

        pushToTalkHint = TextView(this).apply {
            text = "Recording starts the instant you touch"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(mutedInk)
            minHeight = dp(24)
        }

        startStopButton = Button(this).apply {
            text = "Hands-free"
            contentDescription = "Start hands-free listening"
            styleTextButton(success)
            setOnClickListener { toggleListening() }
        }

        pushToTalkButton = RecordingButton(this).apply {
            text = "Hold to talk"
            contentDescription = "Hold to record a message, then release to send"
            textSize = 16f
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_sutra_mic, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            styleTalkButton(ink)
            setOnTouchListener { _, event ->
                if (recordWithTap && pushToTalkRecording) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { recordWithTap = false; beginPushToTalk() }
                    MotionEvent.ACTION_UP -> endPushToTalk()
                    MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 ||
                        event.x > pushToTalkButton.width || event.y > pushToTalkButton.height) endPushToTalk(cancel = true)
                    MotionEvent.ACTION_CANCEL -> endPushToTalk(cancel = true)
                }
                true
            }
            // Accessibility click actions use tap-to-start/tap-to-send without a held gesture.
            setOnClickListener {
                if (pushToTalkRecording) endPushToTalk() else { recordWithTap = true; beginPushToTalk() }
            }
        }
        tapRecordButton = Button(this).apply {
            text = "Tap to record"
            styleTextButton(success)
            setOnClickListener {
                if (pushToTalkRecording) endPushToTalk() else { recordWithTap = true; beginPushToTalk() }
            }
        }
        cancelRecordingButton = Button(this).apply {
            text = "Cancel recording"
            styleTextButton(accent)
            visibility = View.GONE
            setOnClickListener { endPushToTalk(cancel = true) }
        }

        routeButton = Button(this).apply {
            text = "Routing: $routingMode"
            contentDescription = "Speech language routing mode: $routingMode"
            styleSecondaryButton()
            setOnClickListener {
                routingMode = RoutingMode.entries[(routingMode.ordinal + 1) % RoutingMode.entries.size]
                text = "Routing: $routingMode"
                contentDescription = "Speech language routing mode: $routingMode"
                saveRoutingMode()
            }
        }

        speakButton = Button(this).apply {
            text = "Replay"
            isEnabled = false
            styleSecondaryButton()
            setOnClickListener { speakResult() }
        }

        emphasisWord = EditText(this).apply {
            hint = "e.g. आपातकाल"
            contentDescription = "Optional Hindi word to emphasize during replay"
            isSingleLine = true
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(mutedInk)
        }

        talkReplayButton = Button(this).apply {
            text = "Replay"
            isEnabled = false
            styleSecondaryButton()
            setOnClickListener { speakResult() }
        }

        talkCopyButton = Button(this).apply {
            text = "Copy"
            isEnabled = false
            styleSecondaryButton()
            setOnClickListener { copyLatestTranscript() }
        }

        normalButton = Button(this).apply {
            text = "Normal message"
            contentDescription = "Normal message, selected"
            styleSegmentButton(selected = true, alert = false)
            setOnClickListener { setAlertMode(false) }
        }

        alertButton = Button(this).apply {
            text = "Emergency alert"
            contentDescription = "Tap to arm emergency alert mode"
            styleSegmentButton(selected = false, alert = true)
            // Single tap toggles alert mode on/off (no category picker needed)
            setOnClickListener { confirmAlertMode() }
        }

        networkStatus = TextView(this).apply {
            text = "No device connected  ·  Not connected"
            textSize = 12.5f
            setTextColor(Color.rgb(201, 208, 218))
            setPadding(0, dp(5), 0, 0)
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

        // Discovery belongs to the foreground service, not this screen.

        val wifiDirectButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Search now"
                setOnClickListener {
                    transmission?.resumeAutomaticConnections()
                    prepareAutomaticConnections()
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
                    transmission?.disconnectAll()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        bluetoothPeerAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, mutableListOf("No paired Bluetooth phones"),
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        bluetoothPeerSpinner = Spinner(this).apply { adapter = bluetoothPeerAdapter }
        proximityMap = ProximityMapView(this)
        proximityStatus = TextView(this).apply {
            text = "Scan to estimate the range of paired, discoverable phones"
            textSize = 12.5f
            setTextColor(mutedInk)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        val bluetoothButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Be visible"
                setOnClickListener { requestBluetoothVisibility() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "BT connect"
                setOnClickListener {
                    if (ensureBluetoothPermission()) {
                        val peer = bluetoothPeers.getOrNull(bluetoothPeerSpinner.selectedItemPosition)
                        proximityMap.setTarget(peer?.address)
                        runCatching { requireNotNull(transmission).bluetoothConnect(peer?.address ?: "") }
                            .onFailure { networkStatus.text = it.message }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Scan range"
                setOnClickListener {
                    if (ensureBluetoothPermission()) {
                        refreshBluetoothPeers()
                        transmission?.bluetoothScan()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val savedPhrase = getSharedPreferences("sutra", MODE_PRIVATE).getString("pairing_phrase", null)
            ?: SutraTransmissionService.DEFAULT_PAIRING_PHRASE
        pairingPhrase = EditText(this).apply {
            hint = "Shared pairing phrase (12+ characters)"
            if (savedPhrase.isNotBlank()) setText(savedPhrase)
            setSingleLine()
        }
        val securityAndMetrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Secure session"
                setOnClickListener {
                    val phrase = pairingPhrase.text.toString()
                    runCatching {
                        requireNotNull(transmission).setPairingPhrase(phrase)
                    }.onSuccess {
                        val active = transmission?.isSecurityActive() == true
                        networkStatus.text = "SUTRA ready • ${securityStatusLabel(active)}"
                    }.onFailure { networkStatus.text = it.message }
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

        styleButtonRow(wifiDirectButtons)
        styleButtonRow(bluetoothButtons)
        styleButtonRow(securityAndMetrics)
        styleButtonRow(networkButtons)

        val talkTranscriptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(24))
            heroArt = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.sutra_voice_art)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(heroArt, LinearLayout.LayoutParams(dp(180), dp(104)).apply { bottomMargin = dp(16) })
            addView(transcriptMeta)
            addView(latestTranscript)
            addView(translatedTranscript)
        }
        emptyConversation = talkTranscriptCard

        val messageTypeSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBackground(canvas, 16)
            addView(normalButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(alertButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        }

        val talkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(pushToTalkButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            })
            addView(pushToTalkHint)
            addView(vadIndicator)
        }

        val handsFreeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tapRecordButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(startStopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelRecordingButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        talkQuickActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(talkReplayButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(talkCopyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Emphasize a word on replay (optional, Hindi)"
                textSize = 12f
                setTextColor(mutedInk)
                setPadding(0, dp(10), 0, dp(4))
            })
            addView(emphasisWord)
        }
        styleButtonRow(talkQuickActionsRow.getChildAt(0) as LinearLayout)
        emergencyBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(20), dp(4), dp(12), dp(4))
            setBackgroundColor(accent)
            addView(TextView(this@MainActivity).apply {
                text = "Emergency mode\nYour next message will be an alert"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Cancel"
                styleTextButton(Color.WHITE)
                contentDescription = "Cancel emergency mode and discard any active recording"
                setOnClickListener {
                    if (pushToTalkRecording) endPushToTalk(cancel = true)
                    setAlertMode(false)
                }
            })
        }

        discoveryStatus = TextView(this).apply {
            text = "Automatic discovery is starting"
            textSize = 13f
            setTextColor(mutedInk)
            setPadding(0, dp(8), 0, dp(8))
        }
        connectionTitle = TextView(this).apply {
            text = "Finding your nearby team"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
            setPadding(0, dp(10), 0, dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        linkCounts = TextView(this).apply {
            text = "Wi-Fi  0 links     ·     Bluetooth  0 links"
            textSize = 14f
            setTextColor(success)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = roundedBackground(Color.rgb(231, 242, 240), 12)
        }
        searchButton = Button(this).apply {
            text = "Search again"
            stylePrimaryButton(success)
            setOnClickListener {
                nearbyPermissionRequested = false
                transmission?.resumeAutomaticConnections()
                prepareAutomaticConnections()
                updateConnectionLine("")
            }
        }
        disconnectButton = Button(this).apply {
            text = "Pause & disconnect"
            styleTextButton(mutedInk)
            setOnClickListener {
                transmission?.disconnectAll()
                updateConnectionLine("")
            }
        }
        val connectionCard = card().apply {
            background = roundedBackground(mint, 26)
            addView(sectionTitle("Connect naturally"))
            addView(connectionTitle)
            connectionHint = TextView(this@MainActivity).apply {
                text = "Open SUTRA on another phone. Connections happen automatically."
                textSize = 15f
                setTextColor(mutedInk)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(14), 0, dp(12))
            }
            addView(connectionHint)
            addView(searchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            addView(Button(this@MainActivity).apply {
                text = "Make this phone visible"
                styleTextButton(success)
                setOnClickListener { requestBluetoothVisibility() }
            })
        }

        nearbyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nearbyCard = card().apply {
            background = null
            addView(sectionTitle("Nearby devices"))
            addView(nearbyList)
            addView(TextView(this@MainActivity).apply {
                text = "Names are saved on this phone. A device may appear over both Wi-Fi and Bluetooth."
                textSize = 12f
                setTextColor(mutedInk)
                setPadding(0, dp(12), 0, 0)
            })
        }
        renderNearbyPeers()

        val proximityCard = card().apply {
            visibility = View.GONE
            addView(sectionTitle("Bluetooth signal map"))
            addView(TextView(this@MainActivity).apply {
                text = "Signal-based distance estimates, not actual device positions."
                textSize = 13f
                setTextColor(mutedInk)
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(proximityMap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            addView(proximityStatus)
            addView(TextView(this@MainActivity).apply {
                text = "Approximation only: walls, pockets and antenna direction affect RSSI. The last scan is retained while audio is transferred."
                textSize = 11f
                setTextColor(mutedInk)
                setPadding(0, dp(7), 0, 0)
            })
        }
        val proximityToggle = Button(this).apply {
            text = "Show signal map"
            styleSecondaryButton()
            setOnClickListener {
                proximityCard.visibility = if (proximityCard.visibility == View.GONE) View.VISIBLE else View.GONE
                text = if (proximityCard.visibility == View.VISIBLE) "Hide signal map" else "Show signal map"
            }
        }

        val transcriptActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(speakButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Copy"
                styleSecondaryButton()
                setOnClickListener { copyLatestTranscript() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Clear"
                styleSecondaryButton()
                setOnClickListener {
                    result.text = "Your sent and received messages will appear here."
                    resetLatestTranscript()
                    transmission?.conversation?.clear()
                    onConversationChanged()
                    lastText = ""
                    emphasisWord.text.clear()
                    speakButton.isEnabled = false
                    talkReplayButton.isEnabled = false
                    talkCopyButton.isEnabled = false
                    talkQuickActionsRow.visibility = View.GONE
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))
        }
        val transcriptCard = card().apply {
            addView(sectionTitle("Today"))
            addView(result)
            addView(transcriptActions)
        }
        styleButtonRow(transcriptActions)

        val advancedPanel = card().apply {
            visibility = View.GONE
            addView(sectionTitle("Connection diagnostics"))
            addView(linkCounts)
            addView(discoveryStatus)
            addView(disconnectButton)
            addView(sectionTitle("Manual Wi-Fi Direct"))
            addView(wifiPeerSpinner)
            addView(wifiDirectButtons)
            addView(sectionTitle("Manual Bluetooth"))
            addView(bluetoothPeerSpinner)
            addView(bluetoothButtons)
            addView(TextView(this@MainActivity).apply {
                text = "Speech routing"
                setTextColor(mutedInk)
            })
            addView(routeButton)
            addView(TextView(this@MainActivity).apply {
                text = "Pairing phrase"
                setTextColor(mutedInk)
                setPadding(0, dp(12), 0, 0)
            })
            addView(pairingPhrase)
            addView(securityAndMetrics)
            addView(TextView(this@MainActivity).apply {
                text = "Manual IP fallback"
                setTextColor(mutedInk)
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                val addresses = TcpPeerTransport.localIpv4Addresses().joinToString().ifBlank { "unavailable" }
                text = "This phone: $addresses:${TcpPeerTransport.PORT}"
                textSize = 12f
                setTextColor(mutedInk)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Other phone's IP address"
                textSize = 12f
                setTextColor(mutedInk)
                setPadding(0, dp(8), 0, dp(2))
            })
            addView(peerAddress)
            addView(networkButtons)
            addView(status)
        }
        val advancedToggle = Button(this).apply {
            text = "Show advanced connection tools"
            styleSecondaryButton()
            setOnClickListener {
                advancedPanel.visibility = if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (advancedPanel.visibility == View.VISIBLE) {
                    "Hide advanced connection tools"
                } else {
                    "Show advanced connection tools"
                }
            }
        }

        conversationView = ConversationView(this) { entry, word ->
            if (transmission?.speakLocal(entry.text, entry.language, word) != true) {
                Toast.makeText(this, "Offline voice is not ready. Your message text is still available.", Toast.LENGTH_LONG).show()
            }
        }
        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            conversationStack = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                conversationHeading = TextView(this@MainActivity).apply {
                    text = "Your conversation"
                    textSize = 24f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ink)
                    setPadding(dp(22), dp(20), dp(22), dp(4))
                    visibility = View.GONE
                }
                addView(conversationHeading)
                addView(talkTranscriptCard)
                addView(conversationView)
            }
            addView(conversationStack)
        }
        val talkPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(conversationScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(4))
                background = roundedBackground(surface, 28)
                elevation = dp(3).toFloat()
                addView(messageTypeSelector)
                addView(talkCard)
                addView(handsFreeRow)
                addView(talkQuickActionsRow)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(8)
            })
        }
        val devicesPage = screenPage(
            "Stay close.",
            "Your nearby team, even without internet.",
            connectionCard,
            nearbyCard,
            proximityToggle,
            proximityCard,
            advancedToggle,
            advancedPanel,
        )
        val historyPage = screenPage(
            "Conversation",
            "Sent, received, and playback-confirmed messages.",
            transcriptCard,
        )
        val mapPage = mapController.createPageView()
        val pages = arrayOf(talkPage, devicesPage, historyPage, mapPage)
        val content = FrameLayout(this).apply {
            pages.forEach { addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        val navButtons = arrayOf(
            TextView(this).apply {
                text = "Talk"
                setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_sutra_mic, 0, 0)
            },
            TextView(this).apply {
                text = "Devices"
                setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_sutra_team, 0, 0)
            },
            TextView(this).apply {
                text = "History"
                setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_sutra_history, 0, 0)
            },
            TextView(this).apply {
                text = "Map"
                setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_sutra_map, 0, 0)
            },
        )
        val showPage: (Int) -> Unit = { selected ->
            if (selected != 0 && pushToTalkRecording) endPushToTalk(cancel = true)
            if (selected == 3 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            }
            pages.forEachIndexed { index, page ->
                val appearing = index == selected && page.visibility != View.VISIBLE
                page.visibility = if (index == selected) View.VISIBLE else View.GONE
                if (appearing && page.isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
                    page.alpha = 0f
                    page.animate().alpha(1f).setDuration(160).start()
                }
            }
            navButtons.forEachIndexed { index, button ->
                button.background = rippleBackground(if (index == selected) mint else canvas, 18)
                button.setTextColor(if (index == selected) ink else mutedInk)
                button.compoundDrawableTintList = ColorStateList.valueOf(if (index == selected) ink else mutedInk)
                button.setTypeface(null, if (index == selected) Typeface.BOLD else Typeface.NORMAL)
                button.contentDescription = "${button.text}${if (index == selected) ", selected" else ""}"
                button.isSelected = index == selected
            }
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(6))
            setBackgroundColor(canvas)
            navButtons.forEachIndexed { index, button ->
                button.gravity = Gravity.CENTER
                button.textSize = 13f
                button.compoundDrawablePadding = dp(2)
                button.isClickable = true
                button.isFocusable = true
                button.setOnClickListener { showPage(index) }
                button.minHeight = dp(58)
                addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(11))
            setBackgroundColor(SutraPalette.chromeBackground)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "SUTRA"
                    textSize = 20f
                    letterSpacing = 0.12f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "Voice, together"
                    visibility = if (resources.configuration.fontScale > 1.3f) View.GONE else View.VISIBLE
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.rgb(143, 230, 214))
                    background = roundedBackground(Color.rgb(22, 55, 70), 20)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                })
                addView(Button(this@MainActivity).apply {
                    text = "Settings"
                    textSize = 11f
                    contentDescription = "Open Settings"
                    styleTextButton(Color.WHITE)
                    minWidth = dp(44)
                    setPadding(dp(12), paddingTop, dp(12), paddingBottom)
                    setOnClickListener { showSettings() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            })
            addView(networkStatus)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            addView(header)
            addView(emergencyBanner)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(navigation)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                view.setPadding(bars.left, 0, bars.right, keyboard)
                header.setPadding(dp(16), dp(12) + bars.top, dp(12), dp(11))
                navigation.setPadding(dp(8), dp(5), dp(8), dp(6) + bars.bottom)
                navigation.visibility = if (keyboard > 0) View.GONE else View.VISIBLE
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) root.post {
            // The window's decor does not exist on every device until content is attached.
            root.windowInsetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        root.requestApplyInsets()
        showPage(0)

        val serviceIntent = Intent(this, SutraTransmissionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        val modelError = runCatching { validateModelBundle(modelDir) }.exceptionOrNull()
        if (modelError != null || vadInstallError != null || !vadModelFile.isFile) {
            startStopButton.setEnabledAlpha(false)
            tapRecordButton.setEnabledAlpha(false)
            pushToTalkButton.setEnabledAlpha(false)
            val reason = modelError?.message ?: vadInstallError?.message ?: "Silero VAD missing"
            status.text = "Models missing or incomplete: $reason\n" +
                "Copy artifacts/android_models over USB.\n$modelDir"
            heroArt.visibility = View.GONE
            transcriptMeta.text = "SPEECH MODELS NEED ATTENTION"
            transcriptMeta.setTextColor(accent)
            latestTranscript.text = "$reason\nRecheck the offline model files before recording."
            latestTranscript.setTextColor(accent)
            vadIndicator.text = "Push-to-talk unavailable"
        } else {
            status.text = "On-device models ready - ${deviceProfile.summary()}\n$modelDir"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun securityStatusLabel(active: Boolean) =
        if (active) "Secured (AES-GCM)" else "Unsecured (demo mode)"

    private fun roundedBackground(color: Int, radiusDp: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun outlinedBackground(fill: Int, stroke: Int, radiusDp: Int) =
        roundedBackground(fill, radiusDp).apply { setStroke(dp(1), stroke) }

    private fun rippleBackground(color: Int, radiusDp: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
        roundedBackground(color, radiusDp),
        roundedBackground(Color.WHITE, radiusDp),
    )

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(16))
        background = roundedBackground(surface, 24)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(11)
        }
    }

    private fun screenPage(title: String, subtitle: String, vararg content: View) = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(canvas)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(32))
            if (title.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 28f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 15f
                setTextColor(mutedInk)
                setPadding(0, dp(3), 0, dp(13))
            })
            content.forEach { addView(it) }
        })
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
        letterSpacing = 0.01f
        setTextColor(ink)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun Button.stylePrimaryButton(color: Int, radiusDp: Int = 16) {
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        backgroundTintList = null
        background = rippleBackground(color, radiusDp)
        elevation = 0f
        minHeight = dp(50)
    }

    private fun Button.styleSecondaryButton() {
        isAllCaps = false
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(mutedInk, ink),
        ))
        backgroundTintList = null
        background = outlinedBackground(surface, divider, 12)
        elevation = 0f
        minHeight = dp(48)
    }

    private fun Button.styleTextButton(color: Int) {
        isAllCaps = false
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        backgroundTintList = null
        background = rippleBackground(Color.TRANSPARENT, 12)
        elevation = 0f
        minHeight = dp(48)
    }

    /** stylePrimaryButton/styleTalkButton/styleTextButton paint flat colors, so isEnabled alone
     * leaves a disabled button looking identical to an active one; dim it explicitly. */
    private fun Button.setEnabledAlpha(enabled: Boolean, dimmedAlpha: Float = 0.45f) {
        isEnabled = enabled
        alpha = if (enabled) 1f else dimmedAlpha
    }

    private fun Button.styleSegmentButton(selected: Boolean, alert: Boolean) {
        isAllCaps = false
        setTypeface(null, Typeface.BOLD)
        isSelected = selected
        textSize = 13f
        setPadding(dp(6), 0, dp(6), 0)
        setTextColor(if (selected && alert) Color.WHITE else if (alert) accent else ink)
        backgroundTintList = null
        background = rippleBackground(
            if (selected) if (alert) accent else surface else Color.TRANSPARENT,
            11,
        )
        elevation = 0f
    }

    private fun Button.styleTalkButton(color: Int) {
        stylePrimaryButton(color, 24)
        setPadding(dp(24), dp(8), dp(20), dp(8))
        textSize = 18f
    }

    private fun resetLatestTranscript() {
        transcriptMeta.text = "Every voice. Closer."
        latestTranscript.text = "Speak in your language.\nStart a conversation with your nearby team."
        latestTranscript.textSize = 16f
        latestTranscript.setTextColor(mutedInk)
        if (::translatedTranscript.isInitialized) {
            translatedTranscript.text = ""
            translatedTranscript.visibility = View.GONE
        }
        pushToTalkButton.text = "Hold to talk"
        pushToTalkButton.styleTalkButton(if (alertMode) accent else ink)
        pushToTalkHint.text = if (alertMode) "Alert armed · ${alertCategory.orEmpty()}" else "Recording starts the instant you touch"
    }

    private fun setAlertMode(enabled: Boolean, category: String? = null) {
        alertMode = enabled
        emergencyBanner.visibility = if (enabled) View.VISIBLE else View.GONE
        alertCategory = category.takeIf { enabled }
        normalButton.styleSegmentButton(selected = !enabled, alert = false)
        alertButton.styleSegmentButton(selected = enabled, alert = true)
        normalButton.contentDescription = "Normal message${if (!enabled) ", selected" else ""}"
        alertButton.text = if (enabled) "Emergency · $category" else "Emergency alert"
        alertButton.contentDescription = if (enabled) "Emergency alert, $category, selected" else "Choose emergency alert mode"
        pushToTalkButton.styleTalkButton(if (enabled) accent else ink)
        pushToTalkHint.text = if (enabled) "Alert armed · $category" else "Recording starts the instant you touch"
        if (enabled && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Alert playback may be limited by Do Not Disturb", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmAlertMode() {
        // If already armed, disarm with one tap
        if (alertMode) {
            setAlertMode(false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send an emergency message?")
            .setMessage("Your next recording will be marked as an emergency. Choose Normal message to cancel before recording.")
            .setPositiveButton("Use emergency mode") { _, _ -> setAlertMode(true, "EMERGENCY") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSettings() {
        val langInfo = IndicTranslator.supportedLanguages.find { it.key == preferredLanguage }
        val currentLangLabel = "${langInfo?.nativeName ?: ""} (${langInfo?.englishName ?: preferredLanguage})"
        val choices = arrayOf(
            "Change preferred language: $currentLangLabel",
            "Automatic language detection: ${if (routingMode == RoutingMode.AUTO) "On" else "Off"}",
            "Forced ASR routing: $routingMode",
            "Emergency playback access",
            "Show developer diagnostics",
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(choices) { _, index ->
                when (index) {
                    0 -> {
                        startActivity(Intent(this, LanguagePreferenceActivity::class.java).apply {
                            putExtra("force_change", true)
                        })
                    }
                    1 -> {
                        routingMode = RoutingMode.AUTO
                        routeButton.text = "Routing: AUTO"
                        saveRoutingMode()
                    }
                    2 -> {
                        routingMode = RoutingMode.entries[(routingMode.ordinal + 1) % RoutingMode.entries.size]
                        routeButton.text = "Routing: $routingMode"
                        saveRoutingMode()
                    }
                    3 -> startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    4 -> status.visibility = if (status.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateConnectionLine(state: String) {
        if (!::networkStatus.isInitialized) return
        val service = transmission
        val wifi = service?.wifiLinkCount ?: 0
        val bluetooth = service?.bluetoothLinkCount ?: 0
        val total = wifi + bluetooth
        val automatic = service?.isAutoConnecting == true
        val nearbyPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        val needsPermission = checkSelfPermission(nearbyPermission) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 31 && listOf(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).any {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            })
        networkStatus.text = when {
            service == null -> state.ifBlank { "Starting connections…" }
            total > 0 -> "Ready · connected nearby"
            automatic && needsPermission -> "Nearby access needed · open Devices"
            automatic && latestWifiStatus.startsWith("Connecting") -> "Connecting to your nearby team…"
            automatic -> "Searching nearby · connections are automatic"
            else -> "Connections paused"
        }
        networkStatus.contentDescription = networkStatus.text
        if (!::connectionTitle.isInitialized) return
        connectionTitle.text = when {
            service == null -> "Connection service unavailable"
            total > 0 -> "Ready to talk"
            automatic && needsPermission -> "Allow nearby connections"
            automatic && latestWifiStatus.startsWith("Connecting") -> "Connecting to your team"
            automatic -> "Finding your nearby team"
            else -> "Connections paused"
        }
        linkCounts.text = "Wi-Fi  $wifi ${if (wifi == 1) "link" else "links"}     ·     Bluetooth  $bluetooth ${if (bluetooth == 1) "link" else "links"}"
        searchButton.text = if (needsPermission) "Allow nearby devices" else if (automatic) "Search again" else "Resume automatic connections"
        searchButton.isEnabled = service != null
        searchButton.styleSecondaryButton()
        searchButton.visibility = if (needsPermission || !automatic || latestWifiStatus.contains("failed", true) ||
            latestWifiStatus.contains("busy", true) || latestWifiStatus.contains("disabled", true)) View.VISIBLE else View.GONE
        connectionHint.text = when {
            service == null -> "Starting the connection service…"
            needsPermission -> "Allow Nearby devices so SUTRA can find and connect to your team."
            !automatic -> "Automatic connections are paused. Resume when you are ready to talk."
            latestWifiStatus.contains("busy", true) -> "Wi-Fi is temporarily busy. Retrying automatically." +
                if (bluetooth > 0) " Your Bluetooth connection is still available." else " Keep SUTRA open on the other phone."
            latestWifiStatus.contains("disabled", true) -> "Turn on Wi-Fi to find nearby phones. If a hotspot is active, turn it off and retry."
            latestWifiStatus.contains("permission", true) -> "Allow Nearby devices so SUTRA can find your team."
            total > 0 -> "Your connection is ready. Each message shows when a nearby phone receives and plays it."
            else -> "Open SUTRA on another phone. Allow Nearby devices and approve Android's connection prompts."
        }
        disconnectButton.visibility = if (automatic || total > 0) View.VISIBLE else View.GONE
        renderNearbyPeers()
    }

    private fun renderNearbyPeers() {
        if (!::nearbyList.isInitialized) return
        val preferences = getSharedPreferences("sutra_device_names", MODE_PRIVATE)
        fun name(key: String, fallback: String) = preferences.getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback
        val snapshot = bluetoothPeers.map { "${it.address}|${name("bt:${it.address}", it.name)}|${it.connected}" } +
            wifiPeers.map { "${it.address}|${name("wifi:${it.address}", it.name)}" }
        if (snapshot == renderedPeerRows) return
        renderedPeerRows = snapshot
        nearbyList.removeAllViews()
        fun addPeer(name: String, detail: String, connected: Boolean, key: String? = null) {
            nearbyList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                addView(TextView(this@MainActivity).apply {
                    text = name.take(1).uppercase()
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(if (connected) success else ink)
                    setTypeface(null, Typeface.BOLD)
                    background = roundedBackground(if (connected) mint else divider, 16)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(44), dp(48)).apply { marginEnd = dp(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = name; textSize = 17f; setTextColor(ink)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = detail; textSize = 13f; setTextColor(if (connected) success else mutedInk)
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (key != null) addView(Button(this@MainActivity).apply {
                    text = "Name"; textSize = 12f; styleTextButton(success)
                    contentDescription = "Edit local name for $name"
                    setOnClickListener {
                        val input = EditText(this@MainActivity).apply {
                            setText(preferences.getString(key, "")); hint = name
                            isSingleLine = true
                            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
                            contentDescription = "Local device name, up to 40 characters"
                        }
                        AlertDialog.Builder(this@MainActivity).setTitle("Name this device")
                            .setMessage("Only saved on this phone. Leave blank to restore its device name.")
                            .setView(input).setPositiveButton("Save") { _, _ ->
                                preferences.edit().putString(key, input.text.toString().trim()).apply()
                                renderNearbyPeers()
                            }.setNegativeButton("Cancel", null).show()
                    }
                }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            nearbyList.addView(View(this).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        bluetoothPeers.sortedByDescending { it.connected }.forEach {
            val key = "bt:${it.address}"
            addPeer(name(key, it.name), "Bluetooth · ${if (it.connected) "Connected" else "Candidate"}", it.connected, key)
        }
        wifiPeers.forEach {
            val key = "wifi:${it.address}"
            addPeer(name(key, it.name), "Wi-Fi Direct · Discovered", false, key)
        }
        if (wifiPeers.isEmpty() && bluetoothPeers.isEmpty()) {
            addPeer("No nearby devices listed yet", "Open SUTRA on another phone and make it visible.", false)
        }
    }

    private fun styleButtonRow(row: LinearLayout) {
        for (index in 0 until row.childCount) {
            (row.getChildAt(index) as? Button)?.apply {
                styleSecondaryButton()
                (layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.marginStart = dp(3)
                    it.marginEnd = dp(3)
                    layoutParams = it
                }
            }
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

    private fun prepareAutomaticConnections() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) permissions.addAll(listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE))
        val missing = permissions.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !nearbyPermissionRequested) {
            nearbyPermissionRequested = true
            requestPermissions(missing.toTypedArray(), 40)
            return
        }
        if (!bluetoothSetupRequested && (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) {
            bluetoothSetupRequested = true
            val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
            @Suppress("DEPRECATION")
            if (!adapter.isEnabled) startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 41)
            else requestBluetoothVisibility()
        }
    }

    private fun requestBluetoothVisibility() {
        if (!ensureBluetoothPermission()) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        if (adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 42)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 51) {
            mapController.startLocationUpdates()
            mapController.syncSharing()
        }
        if (requestCode in listOf(2, 3, 40)) {
            transmission?.refreshAutomaticConnections()
            prepareAutomaticConnections()
        }
    }

    @Deprecated("Uses the platform discoverability consent activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) requestBluetoothVisibility()
    }

    private fun ensureBluetoothPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 3)
        networkStatus.text = "Grant Nearby devices permission, then retry Bluetooth"
        return false
    }

    private fun refreshBluetoothPeers() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        updateBluetoothPeers(runCatching { transmission?.bluetoothPeers().orEmpty() }.getOrDefault(emptyList()), false)
    }

    private fun updateBluetoothPeers(peers: List<BluetoothPeer>, scanning: Boolean) {
        val selectedAddress = bluetoothPeers.getOrNull(bluetoothPeerSpinner.selectedItemPosition)?.address
        bluetoothPeers = peers
        bluetoothPeerAdapter.clear()
        bluetoothPeerAdapter.addAll(
            peers.map { peer ->
                val range = peer.rssi?.let { " · signal $it dBm" }.orEmpty()
                "${if (peer.connected) "Connected: " else ""}${peer.name}$range"
            }.ifEmpty { listOf("No paired Bluetooth phones") },
        )
        bluetoothPeerAdapter.notifyDataSetChanged()
        selectedAddress?.let { address ->
            peers.indexOfFirst { it.address == address }.takeIf { it >= 0 }?.let(bluetoothPeerSpinner::setSelection)
        }
        proximityMap.updatePeers(peers)
        val connected = peers.firstOrNull { it.connected }
        proximityStatus.text = when {
            scanning -> "Scanning nearby phones… RSSI updates as devices respond"
            connected != null -> "${peers.count { it.connected }} Bluetooth peers connected"
            peers.any { it.distanceMetres != null } -> "Nearby devices found · SUTRA services connect automatically"
            else -> "Use Make this phone visible on new phones"
        }
        renderNearbyPeers()
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

            try {
                val engine = SileroVadEngine(
                    modelFile = vadModel,
                    config = deviceProfile.vadConfig,
                    listener = object : VadListener {
                        override fun onSpeechStart() {
                            runOnUiThread {
                                vadIndicator.text = "Speech detected"
                            }
                        }

                        override fun onSpeechEnd() {
                            runOnUiThread {
                                vadIndicator.text = "Pause detected; preparing message"
                            }
                        }

                        override fun onSpeechSegment(audio: FloatArray) {
                            processSpeechSegment(audio, "Hands-free")
                        }
                    }
                )
                vadEngine = engine

                val playbackSilence = FloatArray(AudioPipeline.CHUNK_SIZE)
                val pipelineCapture = AudioPipeline(this@MainActivity) { chunk ->
                    engine.processChunk(if (AudioDuplexGate.isPlaybackActive) playbackSilence else chunk)
                }
                pipelineCapture.start()
                audioPipeline = pipelineCapture
                listening = true

                startStopButton.text = "Stop hands-free"
                startStopButton.contentDescription = "Stop hands-free listening"
                startStopButton.styleTextButton(accent)
                vadIndicator.text = "Listening for speech"
                pushToTalkButton.alpha = 0.45f
                pushToTalkButton.text = "Hands-free"
                pushToTalkHint.text = "Messages send after a pause · no button needed"
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

            startStopButton.text = "Hands-free"
            startStopButton.contentDescription = "Start hands-free listening"
            startStopButton.styleTextButton(success)
            vadIndicator.text = "Ready to talk"
            pushToTalkButton.alpha = 1f
            pushToTalkButton.text = "Hold to talk"
            pushToTalkHint.text = "Recording starts the instant you touch"
        }
    }

    private fun beginPushToTalk() {
        if (pushToTalkRecording || pendingSegments.get() > 0 || !ensureMicrophonePermission()) return
        if (AudioDuplexGate.isPlaybackActive) {
            vadIndicator.text = "Wait for playback to finish"
            return
        }
        if (listening) toggleListening()
        synchronized(pushToTalkLock) { pushToTalkChunks.clear() }
        pushToTalkRecording = true
        vadEngine?.pushToTalkHeld = true
        pushToTalkStartedAt = SystemClock.elapsedRealtime()
        try {
            val capture = AudioPipeline(this) { chunk ->
                if (pushToTalkRecording && !AudioDuplexGate.isPlaybackActive) {
                    synchronized(pushToTalkLock) { pushToTalkChunks.add(chunk) }
                }
            }
            capture.start()
            audioPipeline = capture
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pushToTalkButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startStopButton.setEnabledAlpha(false)
            pushToTalkButton.text = "Listening…"
            pushToTalkButton.contentDescription = "Recording. Release to send"
            pushToTalkButton.styleTalkButton(if (alertMode) Color.rgb(142, 33, 26) else inkPressed)
            pushToTalkHint.text = if (recordWithTap) "Tap Send when you are finished" else "Release to send · move away to cancel"
            pushToTalkButton.text = if (recordWithTap) "Tap to send" else "Recording…"
            pushToTalkButton.contentDescription = if (recordWithTap) "Recording. Tap to send, or choose Cancel recording" else "Recording. Release to send"
            tapRecordButton.text = "Send recording"
            cancelRecordingButton.visibility = View.VISIBLE
            startStopButton.visibility = View.GONE
            pushToTalkButton.showRecording(true)
            normalButton.isEnabled = false
            alertButton.isEnabled = false
            vadIndicator.text = "Recording"
            vadIndicator.announceForAccessibility("Recording started")
        } catch (error: Exception) {
            pushToTalkRecording = false
            vadEngine?.pushToTalkHeld = false
            pushToTalkButton.showRecording(false)
            normalButton.isEnabled = true
            alertButton.isEnabled = true
            pushToTalkButton.alpha = 1f
            tapRecordButton.text = "Tap to record"
            cancelRecordingButton.visibility = View.GONE
            startStopButton.visibility = View.VISIBLE
            audioPipeline = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startStopButton.setEnabledAlpha(true)
            pushToTalkButton.text = "Hold to talk"
            pushToTalkButton.contentDescription = "Hold to record a message, then release to send"
            pushToTalkButton.styleTalkButton(if (alertMode) accent else ink)
            pushToTalkHint.text = if (alertMode) "Alert armed · ${alertCategory.orEmpty()}" else "Recording starts the instant you touch"
            vadIndicator.text = "Could not record: ${error.message}"
        }
    }

    private fun endPushToTalk(cancel: Boolean = false) {
        if (!pushToTalkRecording) return
        pushToTalkRecording = false
        vadEngine?.pushToTalkHeld = false
        runCatching { audioPipeline?.stop() }
        audioPipeline = null
        pushToTalkButton.showRecording(false)
        normalButton.isEnabled = true
        alertButton.isEnabled = true
        pushToTalkButton.alpha = 1f
        tapRecordButton.text = "Tap to record"
        cancelRecordingButton.visibility = View.GONE
        startStopButton.visibility = View.VISIBLE
        recordWithTap = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pushToTalkButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startStopButton.setEnabledAlpha(true)
        pushToTalkButton.text = "Hold to talk"
        pushToTalkButton.contentDescription = "Hold to record a message, then release to send"
        pushToTalkButton.styleTalkButton(if (alertMode) accent else ink)
        pushToTalkHint.text = if (alertMode) "Alert armed · ${alertCategory.orEmpty()}" else "Recording starts the instant you touch"
        if (cancel) {
            synchronized(pushToTalkLock) { pushToTalkChunks.clear() }
            vadIndicator.text = "Recording cancelled · nothing sent"
            return
        }
        val audio = synchronized(pushToTalkLock) {
            mergeAudioChunks(pushToTalkChunks).also { pushToTalkChunks.clear() }
        }
        if (SystemClock.elapsedRealtime() - pushToTalkStartedAt < 450 || audio.size < AudioPipeline.TARGET_SAMPLE_RATE / 5) {
            vadIndicator.text = "That was too short · hold, speak, then release"
            pushToTalkHint.text = "Recording starts the moment you touch down"
            val attempt = pushToTalkStartedAt
            pushToTalkHint.postDelayed({
                if (!pushToTalkRecording && pushToTalkStartedAt == attempt) {
                    vadIndicator.text = "Ready to talk"
                    pushToTalkHint.text = if (alertMode) {
                        "Alert armed · ${alertCategory.orEmpty()}"
                    } else {
                        "Recording starts the instant you touch"
                    }
                }
            }, 2_600)
            return
        }
        processSpeechSegment(audio, "Push-to-talk")
    }

    private fun ensureMicrophonePermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        vadIndicator.text = "Grant microphone permission, then try again"
        return false
    }

    private fun processSpeechSegment(audio: FloatArray, captureMode: String) {
        val segId = ++segmentCount
        val transmissionId = SutraId.random()
        val mode = routingMode
        val isAlert = alertMode
        val category = alertCategory
        // ponytail: one running + one queued; persist audio only if device tests show lost speech.
        if (pendingSegments.incrementAndGet() > 2) {
            pendingSegments.decrementAndGet()
            runOnUiThread { vadIndicator.text = "Transcription busy; message #$segId skipped" }
            return
        }
        runOnUiThread {
            pushToTalkButton.setEnabledAlpha(false, dimmedAlpha = 0.72f)
            tapRecordButton.setEnabledAlpha(false)
            pushToTalkButton.text = "Transcribing…"
            pushToTalkHint.text = "Detecting language on device"
            vadIndicator.text = "${"%.1f".format(audio.size / 16000.0)} second message"
        }
        val speechEndNanos = System.nanoTime()
        transmission?.recordMetric(transmissionId.toString(), "speech_end", "segment=$segId,capture=$captureMode")
        // Stamp the cut on the wall clock too, so the peer's playback stamp can be compared to it.
        transmission?.noteSpeechEnd(transmissionId.toString(), SutraTelemetry.nsToEpochMs(speechEndNanos))
        sttWorker.execute {
            try {
                transmission?.recordMetric(transmissionId.toString(), "asr_start", "segment=$segId,audio_samples=${audio.size}")
                val sttEngine = pipeline ?: OfflineSpeechPipeline(modelDir).also { pipeline = it }
                val answer = sttEngine.transcribe(audio, mode)
                val audioMs = audio.size * 1000L / 16000
                val loadMs = sttEngine.lastModelLoadMillis
                // Metric 1 is speech end -> STT complete, which includes any queue wait; metric 3
                // (RTF) must not, so it comes from processing time alone.
                val sttLatencyMs = (System.nanoTime() - speechEndNanos) / 1_000_000
                val rtf = SutraTelemetry.realTimeFactor(answer.milliseconds, audioMs)
                transmission?.recordMetric(transmissionId.toString(), "asr_final",
                    "inference_ms=${answer.milliseconds},language=${answer.language}," +
                        "audio_ms=$audioMs,rtf=${"%.4f".format(rtf)},model_load_ms=$loadMs,stt_latency_ms=$sttLatencyMs")

                // Alerts are never rate-limited or downgraded: arming Emergency mode always sends an alert.
                val finalAlert = isAlert
                val coords = mapController.getCurrentCoordinates()
                if (finalAlert) {
                    transmission?.recordAlert(transmissionId.toString(), category ?: "EMERGENCY")
                    mapController.addAlertPin(category ?: "EMERGENCY", answer.text, coords?.first, coords?.second, sourceId)
                }

                val layers = SutraSemanticEncoder.build(
                    language = answer.language,
                    text = answer.text,
                    confidence = answer.confidence.takeIf(Double::isFinite) ?: 0.5,
                    alert = finalAlert,
                    alertCategory = category,
                    sourceId = sourceId,
                    id = transmissionId,
                    coordinates = coords,
                )
                val wireBytes = layers.messages.sumOf { SutraCodec.encode(it).size }
                val connected = transmission?.isConnected() == true
                runCatching { requireNotNull(transmission).send(layers.messages) }.getOrThrow()
                val queued = !connected
                Log.i("SIH_METRIC", "segment=$segId capture=$captureMode mode=$mode language=${answer.language} " +
                    "backend=${answer.backend} audio_ms=${audio.size * 1000 / 16000} " +
                    "inference_ms=${answer.milliseconds} confidence=${answer.confidence} " +
                    "sutra_bytes=$wireBytes queued=$queued")

                val translated = IndicTranslator.translate(answer.text, answer.language, preferredLanguage)
                val targetLangInfo = IndicTranslator.supportedLanguages.find { it.key == preferredLanguage }
                val targetLabel = targetLangInfo?.nativeName ?: preferredLanguage.uppercase()

                runOnUiThread {
                    lastText = answer.text
                    lastLanguage = answer.language
                    speakButton.isEnabled = answer.text.isNotBlank()
                    talkReplayButton.isEnabled = answer.text.isNotBlank()
                    talkCopyButton.isEnabled = answer.text.isNotBlank()
                    talkQuickActionsRow.visibility = if (answer.text.isNotBlank()) View.VISIBLE else View.GONE
                    transcriptMeta.text = "You · ${answer.language}"
                    latestTranscript.text = answer.text
                    latestTranscript.textSize = 18f
                    latestTranscript.setTextColor(ink)

                    if (translated.isNotBlank() && answer.language.lowercase() != preferredLanguage.lowercase()) {
                        translatedTranscript.text = "$targetLabel: $translated"
                        translatedTranscript.visibility = View.VISIBLE
                    } else {
                        translatedTranscript.visibility = View.GONE
                    }

                    val delivery = if (queued) "Queued for delivery" else "Sent"
                    val subRow = if (translated.isNotBlank() && answer.language.lowercase() != preferredLanguage.lowercase()) {
                        "$targetLabel: $translated\n"
                    } else ""
                    appendConversation("You · ${answer.language}\n${answer.text}\n$subRow$delivery\n\n")
                    onConversationChanged()
                    if (isAlert) setAlertMode(false)
                    vadIndicator.text = if (listening) "Listening for speech" else "Ready to talk"
                }
            } catch (error: Exception) {
                runOnUiThread { vadIndicator.text = "Message #$segId failed: ${error.message}" }
            } finally {
                pendingSegments.decrementAndGet()
                runOnUiThread {
                    pushToTalkButton.setEnabledAlpha(true)
                    tapRecordButton.setEnabledAlpha(true)
                    if (!pushToTalkRecording) {
                        pushToTalkButton.text = if (listening) "Hands-free" else "Hold to talk"
                        pushToTalkHint.text = if (listening) "Messages send after a pause" else "Hold to speak, release to send"
                    }
                }
            }
        }
    }

    private fun appendConversation(text: String) {
        if (result.text.startsWith("Your sent")) result.text = ""
        result.append(text)
    }

    private fun speakResult() {
        val emphasized = emphasisWord.text.toString().trim().takeIf(String::isNotEmpty)
        if (emphasized != null && lastText.split(Regex("\\s+")).none {
                it.trimEnd('.', '!', '?', ',', ';', ':', '।').equals(emphasized, ignoreCase = true)
            }) {
            Toast.makeText(this, "That word is not in the latest transcript", Toast.LENGTH_SHORT).show()
            return
        }
        if (transmission?.speakLocal(lastText, lastLanguage, emphasized) != true) {
            status.text = "TTS service is not ready"
            Toast.makeText(this, "Offline voice is not ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLatestTranscript() {
        if (lastText.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("SUTRA transcript", lastText))
        Toast.makeText(this, "Transcript copied", Toast.LENGTH_SHORT).show()
    }

    private fun saveRoutingMode() {
        getSharedPreferences("sutra", MODE_PRIVATE).edit()
            .putString("routing_mode", routingMode.name)
            .apply()
        routeButton.contentDescription = "Speech language routing mode: $routingMode"
    }

    override fun onTransmissionStatus(status: String) {
        runOnUiThread {
            this.status.text = status
            if (status.contains("Wi-Fi", true) || status.startsWith("Connecting")) latestWifiStatus = status
            if (status.contains("Wi-Fi", true) || status.contains("Bluetooth", true) ||
                status.contains("SUTRA phones", true) || status.contains("Nearby devices", true)) {
                discoveryStatus.text = status
            }
            updateConnectionLine(transmission?.connectionSummary() ?: status)
        }
    }

    override fun onTransmissionMessage(message: SutraMessage, elapsedMs: Long?, duplicate: Boolean) {
        runOnUiThread {
            when (message.type) {
                SutraMessageType.ALERT_CORE -> {
                    val fields = runCatching { CriticalFieldCodec.decode(message.payload) }.getOrDefault(emptyList())
                    val alertText = fields.filter { it.kind == CriticalKind.EMERGENCY }.joinToString { it.text }.ifBlank { "Emergency alert received" }
                    val category = fields.firstOrNull { it.kind == CriticalKind.EMERGENCY }?.text ?: "EMERGENCY"
                    val geoField = fields.firstOrNull { it.kind == CriticalKind.LOCATION_HINT && it.text.startsWith("geo:") }
                    var rxLat: Double? = null
                    var rxLon: Double? = null
                    if (geoField != null) {
                        val parts = geoField.text.removePrefix("geo:").split(",")
                        if (parts.size == 2) {
                            rxLat = parts[0].toDoubleOrNull()
                            rxLon = parts[1].toDoubleOrNull()
                        }
                    }
                    appendConversation("EMERGENCY ALERT\n$alertText\n\n")
                    mapController.addAlertPin(category, alertText, rxLat, rxLon, message.sourceId)
                }
                SutraMessageType.TEXT_FINAL -> {
                    if (duplicate) networkStatus.text = "Duplicate safely ignored"
                    else {
                        val text = runCatching { SutraCodec.utf8(message.payload) }.getOrElse { "[invalid UTF-8]" }
                        lastText = text
                        val srcLang = message.language.pipelineName
                        lastLanguage = srcLang
                        speakButton.isEnabled = text.isNotBlank()
                        talkReplayButton.isEnabled = text.isNotBlank()
                        talkCopyButton.isEnabled = text.isNotBlank()
                        talkQuickActionsRow.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
                        transcriptMeta.text = "RECEIVED · ${srcLang.uppercase()}"
                        latestTranscript.text = text
                        latestTranscript.textSize = 18f
                        latestTranscript.setTextColor(ink)

                        val translated = IndicTranslator.translate(text, srcLang, preferredLanguage)
                        val targetLangInfo = IndicTranslator.supportedLanguages.find { it.key == preferredLanguage }
                        val targetLabel = targetLangInfo?.nativeName ?: preferredLanguage.uppercase()
                        if (translated.isNotBlank() && srcLang.lowercase() != preferredLanguage.lowercase()) {
                            translatedTranscript.text = "$targetLabel: $translated"
                            translatedTranscript.visibility = View.VISIBLE
                        } else {
                            translatedTranscript.visibility = View.GONE
                        }

                        val subRow = if (translated.isNotBlank() && srcLang.lowercase() != preferredLanguage.lowercase()) {
                            "$targetLabel: $translated\n"
                        } else ""
                        appendConversation("Nearby teammate · $srcLang\n$text\n${subRow}Received\n\n")

                        if (message.flags and SutraFlags.ALERT != 0) {
                            mapController.addAlertPin("EMERGENCY", text, null, null, message.sourceId)
                        }
                    }
                }
                SutraMessageType.REPAIR_EVIDENCE -> {
                    val fields = runCatching { CriticalFieldCodec.decode(message.payload) }.getOrDefault(emptyList())
                    appendConversation("Please confirm: ${fields.joinToString { it.text }}\n\n")
                }
                SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED,
                SutraMessageType.PLAYBACK_FINISHED, SutraMessageType.REPAIR_REQUEST -> {
                    val label = when (message.type) {
                        SutraMessageType.RECEIVED_ACK -> "Received by peer"
                        SutraMessageType.PLAYBACK_STARTED -> "Playing on peer"
                        SutraMessageType.PLAYBACK_FINISHED -> "Playback completed"
                        SutraMessageType.REPAIR_REQUEST -> "Peer requested clarification"
                        else -> ""
                    }
                    appendConversation("$label\n")
                    if (message.type == SutraMessageType.REPAIR_REQUEST) {
                        vadIndicator.text = "Peer requests repetition of uncertain critical fields"
                    }
                }
                SutraMessageType.HELLO -> Unit
            }
        }
    }

    override fun onConversationChanged() {
        runOnUiThread {
            if (!::conversationView.isInitialized) return@runOnUiThread
            val entries = transmission?.conversation?.snapshot() ?: return@runOnUiThread
            val atBottom = !conversationScroll.canScrollVertically(1)
            val completed = entries.filter { it.outgoing && it.progress == MessageProgress.HEARD }.map { it.key }.toSet()
            if (conversationLoaded && completed.any { it !in heardMessages }) {
                conversationView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            heardMessages.addAll(completed)
            conversationLoaded = true
            emptyConversation.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            conversationHeading.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            conversationStack.gravity = if (entries.isEmpty()) Gravity.CENTER_VERTICAL else Gravity.TOP
            conversationView.render(entries, preferredLanguage)
            if (entries.lastOrNull()?.key != lastConversationKey && atBottom) {
                conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
            }
            lastConversationKey = entries.lastOrNull()?.key
        }
    }

    override fun onBluetoothPeers(peers: List<BluetoothPeer>, scanning: Boolean) {
        runOnUiThread {
            updateBluetoothPeers(peers, scanning)
            updateConnectionLine(transmission?.connectionSummary().orEmpty())
        }
    }

    override fun onTeamChanged(peers: List<TeamPeer>) {
        runOnUiThread { if (::mapController.isInitialized) mapController.updateTeam(peers) }
    }

    override fun onWifiPeers(peers: List<WifiDirectPeer>) {
        runOnUiThread {
            wifiPeers = peers
            wifiPeerAdapter.clear()
            wifiPeerAdapter.addAll(peers.map { "${it.name} (${it.address})" }
                .ifEmpty { listOf("Searching for nearby SUTRA phones") })
            wifiPeerAdapter.notifyDataSetChanged()
            renderNearbyPeers()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SutraTransmissionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        preferredLanguage = getSharedPreferences("sutra", MODE_PRIVATE).getString("preferred_language", "english") ?: "english"
        onConversationChanged()
        if (::mapController.isInitialized) mapController.onResume()
    }

    override fun onPause() {
        if (pushToTalkRecording) endPushToTalk(cancel = true)
        if (::mapController.isInitialized) mapController.onPause()
        super.onPause()
    }

    override fun onStop() {
        transmission?.setListener(null)
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false
        transmission = null
        super.onStop()
    }

    override fun onDestroy() {
        pushToTalkButton.showRecording(false)
        listening = false
        pushToTalkRecording = false
        vadEngine?.pushToTalkHeld = false
        audioPipeline?.stop()
        audioPipeline = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vadEngine?.close()
        vadEngine = null
        if (::mapController.isInitialized) mapController.onDestroy()
        sttWorker.execute { pipeline?.close(); pipeline = null }
        sttWorker.shutdown()
        super.onDestroy()
    }
}
