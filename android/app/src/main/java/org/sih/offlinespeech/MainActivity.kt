package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
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
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
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
    private val ink = Color.rgb(15, 29, 51)
    private val inkPressed = Color.rgb(22, 48, 79)
    private val mutedInk = Color.rgb(91, 103, 121)
    private val accent = Color.rgb(179, 38, 30)
    private val success = Color.rgb(11, 97, 86)
    private val blue = Color.rgb(40, 81, 127)
    private val amber = Color.rgb(138, 100, 18)
    private val surface = Color.WHITE
    private val canvas = Color.rgb(241, 242, 244)
    private val divider = Color.rgb(226, 230, 235)
    private val sttWorker = Executors.newSingleThreadExecutor()
    private val pendingSegments = AtomicInteger()

    private lateinit var status: TextView
    private lateinit var vadIndicator: TextView
    private lateinit var result: TextView
    private lateinit var latestTranscript: TextView
    private lateinit var transcriptMeta: TextView
    private lateinit var pushToTalkHint: TextView
    private lateinit var deliveryLadder: LinearLayout
    private lateinit var startStopButton: Button
    private lateinit var pushToTalkButton: Button
    private lateinit var normalButton: Button
    private lateinit var talkReplayButton: Button
    private lateinit var talkCopyButton: Button
    private lateinit var routeButton: Button
    private lateinit var speakButton: Button
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
    private val deliverySteps = ArrayList<Pair<View, TextView>>()

    private var segmentCount = 0
    private var lastText = ""
    private var lastLanguage = "english"
    private var sourceId = 0L
    private var alertMode = false
    private var alertCategory: String? = null
    private var pushToTalkStartedAt = 0L
    private var latestOutgoingId: SutraId? = null
    @Volatile private var listening = false
    @Volatile private var pushToTalkRecording = false
    @Volatile private var routingMode = RoutingMode.AUTO

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            transmission = (binder as SutraTransmissionService.LocalBinder).service.also {
                it.setListener(this@MainActivity)
            }
            serviceBound = true
            refreshBluetoothPeers()
            updateConnectionLine("No device connected", "Not connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            transmission = null
            networkStatus.text = "SUTRA receiver service stopped"
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = ink
        window.navigationBarColor = surface

        deviceProfile = DeviceProfile.detect(this)
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
            text = "DETECT LANGUAGE AUTOMATICALLY"
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(success)
        }

        latestTranscript = TextView(this).apply {
            text = "Speak in any of the 10 supported languages. The detected language is shown after transcription."
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            setTextColor(mutedInk)
            setPadding(0, dp(9), 0, dp(4))
        }

        pushToTalkHint = TextView(this).apply {
            text = "Recording starts the instant you touch"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(mutedInk)
            minHeight = dp(24)
        }

        startStopButton = Button(this).apply {
            text = "Start"
            contentDescription = "Start hands-free listening"
            styleTextButton(success)
            setOnClickListener { toggleListening() }
        }

        pushToTalkButton = Button(this).apply {
            text = "Hold to talk"
            contentDescription = "Hold to record a message, then release to send"
            textSize = 16f
            setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.ic_btn_speak_now, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            styleTalkButton(ink)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> beginPushToTalk()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPushToTalk()
                }
                true
            }
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
            contentDescription = "Choose emergency alert mode"
            styleSegmentButton(selected = false, alert = true)
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

        val savedPhrase = getSharedPreferences("sutra", MODE_PRIVATE).getString("pairing_phrase", "").orEmpty()
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
                        val secStatus = if (active) "🔒 Authenticated AES-GCM active" else "⚠️ Plaintext (unencrypted demo mode)"
                        networkStatus.text = "SUTRA ready • $secStatus"
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

        val talkTranscriptCard = card().apply {
            addView(transcriptMeta)
            addView(latestTranscript)
            addView(deliveryLadderView())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(talkReplayButton, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(talkCopyButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(7) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        val messageTypeSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBackground(Color.rgb(229, 232, 237), 14)
            addView(normalButton, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(alertButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        }

        val talkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(pushToTalkButton, LinearLayout.LayoutParams(dp(190), dp(190)).apply {
                topMargin = dp(13)
                bottomMargin = dp(8)
            })
            addView(pushToTalkHint)
            addView(vadIndicator)
        }

        val handsFreeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(8), dp(10))
            background = outlinedBackground(surface, divider, 16)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Start hands-free listening"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ink)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "For when you cannot keep holding a button"
                    textSize = 12f
                    setTextColor(mutedInk)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(startStopButton, LinearLayout.LayoutParams(dp(72), dp(48)))
        }

        val connectionCard = card().apply {
            addView(sectionTitle("Wi-Fi Direct"))
            addView(TextView(this@MainActivity).apply {
                text = "Recommended · fastest, longest range\nConnect directly to another SUTRA phone. Internet and a Wi-Fi router are not required."
                textSize = 13f
                setTextColor(mutedInk)
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(wifiPeerSpinner)
            addView(wifiDirectButtons)
        }

        val proximityCard = card().apply {
            addView(sectionTitle("Bluetooth proximity map"))
            addView(TextView(this@MainActivity).apply {
                text = "Shows the paired phone receiving your speech and estimates distance from Bluetooth RSSI. Keep the other phone discoverable while scanning."
                textSize = 13f
                setTextColor(mutedInk)
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(proximityMap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
            addView(proximityStatus)
            addView(bluetoothPeerSpinner)
            addView(bluetoothButtons)
            addView(TextView(this@MainActivity).apply {
                text = "Approximation only: walls, pockets and antenna direction affect RSSI. The last scan is retained while audio is transferred."
                textSize = 11f
                setTextColor(mutedInk)
                setPadding(0, dp(7), 0, 0)
            })
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
                    lastText = ""
                    speakButton.isEnabled = false
                    talkReplayButton.isEnabled = false
                    talkCopyButton.isEnabled = false
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
            addView(sectionTitle("Bluetooth and diagnostics"))
            addView(TextView(this@MainActivity).apply {
                text = "Speech routing"
                setTextColor(mutedInk)
            })
            addView(routeButton)
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

        val talkPage = screenPage(
            "",
            "",
            talkTranscriptCard,
            messageTypeSelector,
            talkCard,
            handsFreeRow,
        )
        val devicesPage = screenPage(
            "Devices",
            "Connect directly over Wi-Fi Direct or Bluetooth.",
            connectionCard,
            proximityCard,
            advancedToggle,
            advancedPanel,
        )
        val historyPage = screenPage(
            "Conversation",
            "Sent, received, and playback-confirmed messages.",
            transcriptCard,
        )
        val pages = arrayOf(talkPage, devicesPage, historyPage)
        val content = FrameLayout(this).apply {
            pages.forEach { addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        val navButtons = arrayOf(
            TextView(this).apply {
                text = "Talk"
                setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.ic_btn_speak_now, 0, 0)
            },
            TextView(this).apply {
                text = "Devices"
                setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.ic_menu_share, 0, 0)
            },
            TextView(this).apply {
                text = "History"
                setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.ic_menu_recent_history, 0, 0)
            },
        )
        val showPage: (Int) -> Unit = { selected ->
            pages.forEachIndexed { index, page -> page.visibility = if (index == selected) View.VISIBLE else View.GONE }
            navButtons.forEachIndexed { index, button ->
                button.background = rippleBackground(if (index == selected) Color.rgb(231, 242, 240) else surface, 12)
                button.setTextColor(if (index == selected) ink else Color.rgb(119, 128, 142))
                button.compoundDrawableTintList = ColorStateList.valueOf(if (index == selected) ink else Color.rgb(119, 128, 142))
                button.setTypeface(null, if (index == selected) Typeface.BOLD else Typeface.NORMAL)
                button.contentDescription = "${button.text}${if (index == selected) ", selected" else ""}"
            }
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(6))
            setBackgroundColor(surface)
            navButtons.forEachIndexed { index, button ->
                button.gravity = Gravity.CENTER
                button.textSize = 11.5f
                button.compoundDrawablePadding = dp(2)
                button.isClickable = true
                button.isFocusable = true
                button.setOnClickListener { showPage(index) }
                addView(button, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(11))
            setBackgroundColor(ink)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "SUTRA"
                    textSize = 15f
                    letterSpacing = 0.20f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "  Offline"
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
                    setOnClickListener { showSettings() }
                }, LinearLayout.LayoutParams(dp(86), dp(44)).apply { marginStart = dp(4) })
            })
            addView(networkStatus)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            addView(header)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(navigation)
        })
        showPage(0)

        val serviceIntent = Intent(this, SutraTransmissionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        val modelError = runCatching { validateModelBundle(modelDir) }.exceptionOrNull()
        if (modelError != null || vadInstallError != null || !vadModelFile.isFile) {
            startStopButton.isEnabled = false
            pushToTalkButton.isEnabled = false
            val reason = modelError?.message ?: vadInstallError?.message ?: "Silero VAD missing"
            status.text = "Models missing or incomplete: $reason\n" +
                "Copy artifacts/android_models over USB.\n$modelDir"
            transcriptMeta.text = "SPEECH MODELS NEED ATTENTION"
            transcriptMeta.setTextColor(accent)
            latestTranscript.text = "$reason\nRecheck the offline model files before recording."
            latestTranscript.setTextColor(Color.rgb(142, 33, 26))
            vadIndicator.text = "Push-to-talk unavailable"
        } else {
            status.text = "On-device models ready - ${deviceProfile.summary()}\n$modelDir"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = outlinedBackground(surface, divider, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(11)
        }
    }

    private fun screenPage(title: String, subtitle: String, vararg content: View) = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(canvas)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(18))
            if (title.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(mutedInk)
                setPadding(0, dp(3), 0, dp(13))
            })
            content.forEach { addView(it) }
        })
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue.uppercase()
        textSize = 11.5f
        letterSpacing = 0.12f
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
        setTextColor(ink)
        backgroundTintList = null
        background = outlinedBackground(surface, Color.rgb(201, 208, 218), 12)
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

    private fun Button.styleSegmentButton(selected: Boolean, alert: Boolean) {
        isAllCaps = false
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (selected) Color.WHITE else if (alert) Color.rgb(142, 33, 26) else mutedInk)
        backgroundTintList = null
        background = rippleBackground(
            if (selected) if (alert) accent else surface else Color.TRANSPARENT,
            11,
        )
        elevation = 0f
    }

    private fun Button.styleTalkButton(color: Int) {
        stylePrimaryButton(color, 120)
        textSize = 16f
    }

    private enum class DeliveryStep { QUEUED, SENT, RECEIVED, SPEAKING, DELIVERED }

    private fun deliveryLadderView(): View = LinearLayout(this).also { row ->
        deliveryLadder = row
        row.orientation = LinearLayout.HORIZONTAL
        row.visibility = View.GONE
        row.setPadding(0, dp(10), 0, 0)
        DeliveryStep.entries.forEach { step ->
            val bar = View(this).apply { background = roundedBackground(divider, 2) }
            val label = TextView(this).apply {
                text = step.name.lowercase().replaceFirstChar(Char::uppercase)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(179, 186, 197))
                setPadding(0, dp(4), 0, 0)
            }
            deliverySteps += bar to label
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
                addView(label)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun showDelivery(step: DeliveryStep) {
        if (!::deliveryLadder.isInitialized) return
        deliveryLadder.visibility = View.VISIBLE
        deliverySteps.forEachIndexed { index, (bar, label) ->
            val current = index == step.ordinal
            val complete = index < step.ordinal
            bar.background = roundedBackground(
                when { current -> success; complete -> Color.rgb(156, 207, 198); else -> divider },
                2,
            )
            label.setTextColor(if (current) ink else if (complete) mutedInk else Color.rgb(179, 186, 197))
            label.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
        }
        val (buttonLabel, hint, color) = when (step) {
            DeliveryStep.QUEUED -> Triple("Queued", "Saved in outbox — will send on reconnect", amber)
            DeliveryStep.SENT -> Triple("Sent", "Waiting for receiver", ink)
            DeliveryStep.RECEIVED -> Triple("Received", "Nearby phone validated the message", ink)
            DeliveryStep.SPEAKING -> Triple("Speaking", "Playing aloud on the nearby phone", blue)
            DeliveryStep.DELIVERED -> Triple("Delivered", "Playback completed · hold again to reply", success)
        }
        pushToTalkButton.text = buttonLabel
        pushToTalkButton.styleTalkButton(color)
        pushToTalkHint.text = hint
        pushToTalkButton.contentDescription = "$buttonLabel. $hint"
    }

    private fun resetLatestTranscript() {
        transcriptMeta.text = "DETECT LANGUAGE AUTOMATICALLY"
        latestTranscript.text = "Speak in any of the 10 supported languages. The detected language is shown after transcription."
        latestTranscript.setTextColor(mutedInk)
        if (::deliveryLadder.isInitialized) deliveryLadder.visibility = View.GONE
        pushToTalkButton.text = "Hold to talk"
        pushToTalkButton.styleTalkButton(if (alertMode) accent else ink)
        pushToTalkHint.text = if (alertMode) "Alert armed · ${alertCategory.orEmpty()}" else "Recording starts the instant you touch"
    }

    private fun setAlertMode(enabled: Boolean, category: String? = null) {
        alertMode = enabled
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
        val categories = arrayOf("Medical", "Trapped", "Fire", "Danger", "Evacuate", "Supplies required", "Safe")
        AlertDialog.Builder(this)
            .setTitle("Emergency alert")
            .setMessage("Pick the category first. It is sent ahead of your words as a tiny language-independent code.")
            .setItems(categories) { _, index -> setAlertMode(true, categories[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val choices = arrayOf(
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
                        routingMode = RoutingMode.AUTO
                        routeButton.text = "Routing: AUTO"
                        saveRoutingMode()
                    }
                    1 -> {
                        routingMode = RoutingMode.entries[(routingMode.ordinal + 1) % RoutingMode.entries.size]
                        routeButton.text = "Routing: $routingMode"
                        saveRoutingMode()
                    }
                    2 -> startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    3 -> status.visibility = if (status.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateConnectionLine(state: String, transport: String = "") {
        if (!::networkStatus.isInitialized) return
        networkStatus.text = if (transport.isBlank()) state else "$state  ·  $transport"
        networkStatus.contentDescription = networkStatus.text
    }

    private fun friendlyConnectionStatus(raw: String): Pair<String, String> = when {
        raw.contains("queued", ignoreCase = true) -> "Link lost · reconnecting" to "Outbox active"
        raw.contains("search", ignoreCase = true) || raw.contains("discover", ignoreCase = true) ->
            "Searching for phones" to ""
        raw.contains("connected", ignoreCase = true) && !raw.contains("disconnected", ignoreCase = true) ->
            "Connected to nearby SUTRA phone" to when {
                raw.contains("bluetooth", ignoreCase = true) -> "Bluetooth"
                else -> "Wi-Fi Direct"
            }
        raw.contains("disconnect", ignoreCase = true) || raw.contains("lost", ignoreCase = true) ->
            "No device connected" to "Not connected"
        else -> raw to ""
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
                val range = peer.distanceMetres?.let { " · ~${"%.1f".format(it)} m · ${peer.rssi} dBm" }.orEmpty()
                "${if (peer.connected) "Receiving: " else ""}${peer.name}$range"
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
            connected != null -> "Audio route: ${connected.name}${connected.distanceMetres?.let { " · last estimate ~${"%.1f".format(it)} m" }.orEmpty()}"
            peers.any { it.distanceMetres != null } -> "Scan complete · select a phone and tap BT connect"
            else -> "No range yet · make the paired phone discoverable, then scan"
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

                startStopButton.text = "Stop"
                startStopButton.contentDescription = "Stop hands-free listening"
                startStopButton.styleTextButton(Color.rgb(142, 33, 26))
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

            startStopButton.text = "Start"
            startStopButton.contentDescription = "Start hands-free listening"
            startStopButton.styleTextButton(success)
            vadIndicator.text = "Ready to talk"
            pushToTalkButton.alpha = 1f
            pushToTalkButton.text = "Hold to talk"
            pushToTalkHint.text = "Recording starts the instant you touch"
        }
    }

    private fun beginPushToTalk() {
        if (pushToTalkRecording || !ensureMicrophonePermission()) return
        if (AudioDuplexGate.isPlaybackActive) {
            vadIndicator.text = "Wait for playback to finish"
            return
        }
        if (listening) toggleListening()
        synchronized(pushToTalkLock) { pushToTalkChunks.clear() }
        pushToTalkRecording = true
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
            startStopButton.isEnabled = false
            pushToTalkButton.text = "Listening…"
            pushToTalkButton.contentDescription = "Recording. Release to send"
            pushToTalkButton.styleTalkButton(if (alertMode) Color.rgb(142, 33, 26) else inkPressed)
            pushToTalkHint.text = "Release to send"
            vadIndicator.text = "Recording"
            vadIndicator.announceForAccessibility("Recording started")
        } catch (error: Exception) {
            pushToTalkRecording = false
            audioPipeline = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startStopButton.isEnabled = true
            pushToTalkButton.text = "Hold to talk"
            pushToTalkButton.contentDescription = "Hold to record a message, then release to send"
            pushToTalkButton.styleTalkButton(if (alertMode) accent else ink)
            pushToTalkHint.text = if (alertMode) "Alert armed · ${alertCategory.orEmpty()}" else "Recording starts the instant you touch"
            vadIndicator.text = "Could not record: ${error.message}"
        }
    }

    private fun endPushToTalk() {
        if (!pushToTalkRecording) return
        pushToTalkRecording = false
        runCatching { audioPipeline?.stop() }
        audioPipeline = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pushToTalkButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startStopButton.isEnabled = true
        pushToTalkButton.text = "Hold to talk"
        pushToTalkButton.contentDescription = "Hold to record a message, then release to send"
        pushToTalkButton.styleTalkButton(if (alertMode) accent else ink)
        pushToTalkHint.text = if (alertMode) "Alert armed · ${alertCategory.orEmpty()}" else "Recording starts the instant you touch"
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
            pushToTalkButton.isEnabled = false
            pushToTalkButton.alpha = 0.72f
            pushToTalkButton.text = "Transcribing…"
            pushToTalkHint.text = "Detecting language on device"
            vadIndicator.text = "${"%.1f".format(audio.size / 16000.0)} second message"
        }
        transmission?.recordMetric(transmissionId.toString(), "speech_end", "segment=$segId,capture=$captureMode")
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
                    alertCategory = category,
                    sourceId = sourceId,
                    id = transmissionId,
                )
                val wireBytes = layers.messages.sumOf { SutraCodec.encode(it).size }
                val connected = transmission?.isConnected() == true
                runCatching { requireNotNull(transmission).send(layers.messages) }.getOrThrow()
                val queued = !connected
                Log.i("SIH_METRIC", "segment=$segId capture=$captureMode mode=$mode language=${answer.language} " +
                    "backend=${answer.backend} audio_ms=${audio.size * 1000 / 16000} " +
                    "inference_ms=${answer.milliseconds} confidence=${answer.confidence} " +
                    "sutra_bytes=$wireBytes queued=$queued")
                runOnUiThread {
                    lastText = answer.text
                    lastLanguage = answer.language
                    latestOutgoingId = transmissionId
                    speakButton.isEnabled = answer.text.isNotBlank()
                    talkReplayButton.isEnabled = answer.text.isNotBlank()
                    talkCopyButton.isEnabled = answer.text.isNotBlank()
                    transcriptMeta.text = "${answer.language.uppercase()} · $wireBytes B · ${answer.milliseconds} MS ON-DEVICE"
                    latestTranscript.text = answer.text
                    latestTranscript.textSize = 18f
                    latestTranscript.setTextColor(ink)
                    val delivery = if (queued) "Queued for delivery" else "Sent"
                    appendConversation("You · ${answer.language.uppercase()} · ${answer.milliseconds} ms\n${answer.text}\n$delivery · $wireBytes bytes\n\n")
                    showDelivery(if (queued) DeliveryStep.QUEUED else DeliveryStep.SENT)
                    vadIndicator.text = if (listening) "Listening for speech" else "Ready to talk"
                }
            } catch (error: Exception) {
                runOnUiThread { vadIndicator.text = "Message #$segId failed: ${error.message}" }
            } finally {
                pendingSegments.decrementAndGet()
                runOnUiThread {
                    pushToTalkButton.isEnabled = true
                    pushToTalkButton.alpha = 1f
                }
            }
        }
    }

    private fun appendConversation(text: String) {
        if (result.text.startsWith("Your sent")) result.text = ""
        result.append(text)
    }

    private fun speakResult() {
        if (transmission?.speakLocal(lastText, lastLanguage) != true) {
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
            friendlyConnectionStatus(status).let { (state, transport) -> updateConnectionLine(state, transport) }
        }
    }

    override fun onTransmissionMessage(message: SutraMessage, elapsedMs: Long?, duplicate: Boolean) {
        runOnUiThread {
            when (message.type) {
                SutraMessageType.ALERT_CORE -> {
                    val fields = runCatching { CriticalFieldCodec.decode(message.payload) }.getOrDefault(emptyList())
                    appendConversation("EMERGENCY ALERT\n${fields.joinToString { it.text }.ifBlank { "Emergency message" }}\n\n")
                }
                SutraMessageType.TEXT_FINAL -> {
                    if (duplicate) networkStatus.text = "Duplicate safely ignored"
                    else {
                        val text = runCatching { SutraCodec.utf8(message.payload) }.getOrElse { "[invalid UTF-8]" }
                        lastText = text
                        lastLanguage = message.language.pipelineName
                        speakButton.isEnabled = text.isNotBlank()
                        talkReplayButton.isEnabled = text.isNotBlank()
                        talkCopyButton.isEnabled = text.isNotBlank()
                        transcriptMeta.text = "RECEIVED · ${message.language.pipelineName.uppercase()}"
                        latestTranscript.text = text
                        latestTranscript.textSize = 18f
                        latestTranscript.setTextColor(ink)
                        if (::deliveryLadder.isInitialized) deliveryLadder.visibility = View.GONE
                        appendConversation("Peer · ${message.language.pipelineName.uppercase()}\n$text\nReceived · Playing automatically\n\n")
                    }
                }
                SutraMessageType.REPAIR_EVIDENCE -> {
                    val fields = runCatching { CriticalFieldCodec.decode(message.payload) }.getOrDefault(emptyList())
                    appendConversation("Please confirm: ${fields.joinToString { it.text }}\n\n")
                }
                SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED,
                SutraMessageType.PLAYBACK_FINISHED, SutraMessageType.REPAIR_REQUEST -> {
                    val timing = elapsedMs?.let { " · ${it} ms" }.orEmpty()
                    val label = when (message.type) {
                        SutraMessageType.RECEIVED_ACK -> "Received by peer"
                        SutraMessageType.PLAYBACK_STARTED -> "Playing on peer"
                        SutraMessageType.PLAYBACK_FINISHED -> "Playback completed"
                        SutraMessageType.REPAIR_REQUEST -> "Peer requested clarification"
                        else -> ""
                    }
                    appendConversation("$label$timing\n")
                    if (message.id == latestOutgoingId) {
                        when (message.type) {
                            SutraMessageType.RECEIVED_ACK -> showDelivery(DeliveryStep.RECEIVED)
                            SutraMessageType.PLAYBACK_STARTED -> showDelivery(DeliveryStep.SPEAKING)
                            SutraMessageType.PLAYBACK_FINISHED -> {
                                showDelivery(DeliveryStep.DELIVERED)
                                pushToTalkButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                pushToTalkButton.announceForAccessibility("Message delivered and playback completed")
                            }
                            else -> Unit
                        }
                    }
                    if (message.type == SutraMessageType.REPAIR_REQUEST) {
                        vadIndicator.text = "Peer requests repetition of uncertain critical fields"
                    }
                }
                SutraMessageType.HELLO -> Unit
            }
        }
    }

    override fun onBluetoothPeers(peers: List<BluetoothPeer>, scanning: Boolean) {
        runOnUiThread { updateBluetoothPeers(peers, scanning) }
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
        pushToTalkRecording = false
        audioPipeline?.stop()
        audioPipeline = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vadEngine?.close()
        vadEngine = null
        wifiDirect?.close()
        sttWorker.execute { pipeline?.close(); pipeline = null }
        sttWorker.shutdown()
        super.onDestroy()
    }
}
