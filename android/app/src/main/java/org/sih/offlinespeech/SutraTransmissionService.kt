package org.sih.offlinespeech

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal object AudioDuplexGate {
    private const val PLAYBACK_DRAIN_NANOS = 750_000_000L
    private val activePlaybacks = AtomicInteger()
    private val muteUntilNanos = AtomicLong()
    val isPlaybackActive: Boolean
        get() = activePlaybacks.get() > 0 || System.nanoTime() < muteUntilNanos.get()
    fun beginPlayback() { activePlaybacks.incrementAndGet() }
    fun endPlayback() {
        if (activePlaybacks.getAndUpdate { max(0, it - 1) } == 1) {
            muteUntilNanos.set(System.nanoTime() + PLAYBACK_DRAIN_NANOS)
        }
    }
    fun reset() {
        activePlaybacks.set(0)
        muteUntilNanos.set(0)
    }
}

internal fun pcmFloatBufferBytes(minimumBytes: Int, sampleRate: Int): Int {
    val requested = max(minimumBytes, sampleRate)
    return (requested + Float.SIZE_BYTES - 1) / Float.SIZE_BYTES * Float.SIZE_BYTES
}

interface SutraTransmissionListener {
    fun onTeamChanged(peers: List<TeamPeer>) = Unit
    fun onConversationChanged() = Unit
    fun onTransmissionStatus(status: String)
    fun onTransmissionMessage(message: SutraMessage, elapsedMs: Long?, duplicate: Boolean)
    fun onBluetoothPeers(peers: List<BluetoothPeer>, scanning: Boolean) = Unit
    fun onWifiPeers(peers: List<WifiDirectPeer>) = Unit
}

class SutraTransmissionService : Service(), PeerTransportListener {
    internal val conversation = ConversationTimeline()
    companion object {
        private const val CHANNEL = "sutra_connection"
        const val NOTIFICATION_ID = 4102
        const val ACTION_STOP = "org.sih.offlinespeech.STOP_TRANSMISSION"
        const val DEFAULT_PAIRING_PHRASE = "sutra-mesh-offline-2026"
    }

    inner class LocalBinder : Binder() { val service: SutraTransmissionService get() = this@SutraTransmissionService }

    private val binder = LocalBinder()
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val team = TeamDirectory()
    private val teamSession = SecureRandom().nextLong()
    private var teamSequence = 0L
    @Volatile private var sharedFix: TeamFix? = null
    private val ttsWorker = Executors.newSingleThreadExecutor()
    private val sentAt = ConcurrentHashMap<String, Long>()
    /** Wall-clock send time per message id — t0 of the NTP-style exchange (see [SutraTelemetry]). */
    private val sentAtEpoch = ConcurrentHashMap<String, Long>()
    /** Wall-clock speech-end per message id, reported by the capture side, for metric 4. */
    private val speechEndEpoch = ConcurrentHashMap<String, Long>()
    /** Monotonic receive time per inbound message id — start of metric 2 (received -> playing). */
    private val receivedAt = ConcurrentHashMap<String, Long>()
    private val pendingSpeech = ConcurrentHashMap<String, SutraMessage>()
    private val speechQueue = ArrayDeque<SutraMessage>()
    private lateinit var wifi: TcpPeerTransport
    private lateinit var bluetooth: BluetoothPeerTransport
    var wifiDirect: WifiDirectController? = null
        private set
    private var wifiPeers = emptyList<WifiDirectPeer>()
    private var automaticConnections = true

    private lateinit var store: SutraStore
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onnxTts: IndicOnnxTts? = null
    private var onnxTtsAttempted = false
    private var security: SecureFrameCodec? = null
    private val defaultSecurity by lazy {
        runCatching { SecureFrameCodec.fromPhrase(DEFAULT_PAIRING_PHRASE) }.getOrNull()
    }
    private var focusRequest: AudioFocusRequest? = null
    private var originalAlarmVolume: Int? = null
    @Volatile private var listener: SutraTransmissionListener? = null
    private var sourceId = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Ready for offline peer messages"))
        store = SutraStore(this)
        audioManager = getSystemService(AudioManager::class.java)
        sourceId = getSharedPreferences("sutra", MODE_PRIVATE).let { preferences ->
            if (preferences.contains("terminal_id")) preferences.getLong("terminal_id", 0)
            else SecureRandom().nextLong().also { preferences.edit().putLong("terminal_id", it).apply() }
        }
        val savedPhrase = getSharedPreferences("sutra", MODE_PRIVATE).getString("pairing_phrase", null)
        val activePhrase = if (savedPhrase != null) {
            savedPhrase.takeIf { it.isNotBlank() }
        } else {
            // Out of the box, all SUTRA devices share the default authenticated encryption mesh key
            DEFAULT_PAIRING_PHRASE
        }
        activePhrase?.takeIf { it.length >= 12 }?.let {
            security = runCatching { SecureFrameCodec.fromPhrase(it) }.getOrNull()
        }
        wifi = TcpPeerTransport(this)
        bluetooth = BluetoothPeerTransport(this, this) { peers, scanning ->
            listener?.onBluetoothPeers(peers, scanning)
        }
        // Bluetooth is the fallback, so it stands down only while Wi-Fi genuinely carries data --
        // not merely because a Wi-Fi Direct group exists but its TCP link failed to come up.
        bluetooth.wifiPrimaryActive = { wifi.isConnected }
        wifiDirect = runCatching {
            WifiDirectController(this, object : WifiDirectListener {
                override fun onWifiDirectStatus(status: String) = this@SutraTransmissionService.status(status)
                override fun onWifiDirectPeers(peers: List<WifiDirectPeer>) {
                    wifiPeers = peers
                    listener?.onWifiPeers(peers)
                }
                override fun onWifiDirectGroup(isOwner: Boolean, ownerAddress: String?) {
                    if (isOwner) wifi.host() else ownerAddress?.let(wifi::connect)
                }
                override fun onWifiDirectGroupLost() {
                    wifi.disconnect()
                    // Wi-Fi is gone; bring the Bluetooth fallback up now rather than waiting out the 45s cycle.
                    if (automaticConnections) bluetooth.startAutomatic()
                }
            }, java.lang.Long.toUnsignedString(sourceId, 16).padStart(16, '0')).also { it.startAutomatic() }
        }.onFailure { status("Wi-Fi Direct unavailable; Bluetooth discovery remains active") }.getOrNull()
        bluetooth.startAutomatic()
        initialiseTts()
        warmTts()
        store.metric(null, "service_started")
        io.scheduleWithFixedDelay({ runCatching { publishTeamPosition() } }, 2, 10, TimeUnit.SECONDS)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun setListener(value: SutraTransmissionListener?) {
        listener = value
        value?.onWifiPeers(wifiPeers)
        value?.onConversationChanged()
        value?.onTeamChanged(team.snapshot(android.os.SystemClock.elapsedRealtime()))
    }

    // Called by the visible activity only; pausing or opting out withdraws the shared fix.
    fun updateSharedFix(fix: TeamFix?) {
        val changedAvailability = (sharedFix == null) != (fix == null)
        sharedFix = fix
        if (changedAvailability) io.execute { runCatching { publishTeamPosition() } }
    }

    private fun publishTeamPosition() {
        if (!isConnected()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val name = android.os.Build.MODEL.take(30).ifBlank { "SUTRA phone" }
        val beacon = TeamBeacon(teamSession, ++teamSequence, name, fix = sharedFix?.takeIf { it.fresh(now) })
        val message = SutraMessage(SutraMessageType.HELLO, SutraLanguage.UNDETERMINED,
            TeamPositionCodec.encode(beacon, now), sourceId = sourceId)
        val bytes = SutraCodec.encode(message)
        sendFrame(security?.seal(bytes) ?: bytes)
    }

    fun resumeAutomaticConnections() {
        automaticConnections = true
        wifiDirect?.startAutomatic()
        bluetooth.startAutomatic()
    }

    fun refreshAutomaticConnections() {
        if (automaticConnections) resumeAutomaticConnections()
    }

    fun connectionSummary(): String =
        "Wi-Fi: ${wifi.peerCount} links · Bluetooth: ${bluetooth.peerCount} links" +
            if (automaticConnections) " · Auto-connect on" else " · Auto-connect paused"

    val wifiLinkCount get() = wifi.peerCount
    val bluetoothLinkCount get() = bluetooth.peerCount
    val isAutoConnecting get() = automaticConnections

    fun wifiHost() = wifi.host()
    fun wifiConnect(host: String) = wifi.connect(host)
    fun wifiDisconnect() = wifi.disconnect()
    fun bluetoothListen() = bluetooth.listen()
    fun bluetoothConnect(address: String) = bluetooth.connect(address)
    fun bluetoothPeers(): List<BluetoothPeer> = bluetooth.bondedPeers()
    fun fetchSutraPeers(onResult: (List<BluetoothPeer>) -> Unit) = bluetooth.fetchSutraPeers(onResult)
    fun bluetoothScan() = bluetooth.scan()
    fun disconnectAll() {
        automaticConnections = false
        wifiDirect?.disconnect()
        wifi.disconnect()
        bluetooth.disconnect()
        status(connectionSummary())
    }
    fun isConnected(): Boolean = wifi.isConnected || bluetooth.isConnected
    fun isSecurityActive(): Boolean = security != null

    fun setPairingPhrase(phrase: String) {
        if (phrase.isBlank()) {
            security = null
            getSharedPreferences("sutra", MODE_PRIVATE).edit().putString("pairing_phrase", "").apply()
            status("Encryption disabled (open plaintext mode)")
            return
        }
        security = SecureFrameCodec.fromPhrase(phrase)
        getSharedPreferences("sutra", MODE_PRIVATE).edit().putString("pairing_phrase", phrase).apply()
        store.metric(null, "security_enabled")
        status("Authenticated encryption enabled")
    }

    fun send(messages: List<SutraMessage>) {
        messages.forEach { message ->
            val plain = SutraCodec.encode(message)
            store.enqueue(message, plain)
            if (message.type == SutraMessageType.TEXT_FINAL) {
                conversation.add(ConversationEntry(message.id.toString(), SutraCodec.utf8(message.payload),
                    message.language.pipelineName, true, message.flags and SutraFlags.ALERT != 0, MessageProgress.QUEUED))
                listener?.onConversationChanged()
            }
            sentAt.putIfAbsent(message.id.toString(), System.nanoTime())
            // Epoch copy of the same instant: the NTP-style exchange needs t0 on the wall clock,
            // because the peer can only report its own wall clock back to us.
            sentAtEpoch.putIfAbsent(message.id.toString(), System.currentTimeMillis())
            store.metric(message.id.toString(), "tx_queued", "bytes=${plain.size},type=${message.type}")
        }
        flushOutbox()
    }

    fun exportMetrics(): Pair<File, Int> {
        val target = File(requireNotNull(getExternalFilesDir(null)), "metrics/sutra_metrics.csv")
        return target to store.exportMetrics(target)
    }

    fun recordMetric(messageId: String?, event: String, detail: String = "") =
        store.metric(messageId, event, detail)

    fun recordAlert(messageId: String = "", detail: String = "") =
        store.recordAlert(messageId, detail)

    fun monthlyAlertCount(): Int =
        store.monthlyAlertCount()

    fun speakLocal(text: String, language: String, emphasizedWord: String? = null): Boolean {
        if (text.isBlank()) return false
        if (hasOnnxTts(language)) {
            playOnnx(text, language, alert = false, message = null, emphasizedWord = emphasizedWord)
            return true
        }
        return speakSystem(text, language, alert = false, utteranceId = "local-${System.nanoTime()}")
    }

    override fun onTransportStatus(status: String) {
        status(status)
        if (isConnected()) {
            store.clearStalePendingFrames()
            flushOutbox()
        }
    }

    override fun onTransportMessage(bytes: ByteArray) {
        val receivedMs = android.os.SystemClock.elapsedRealtime()
        io.execute {
            val isEncryptedFrame = bytes.size >= 3 && bytes[0] == 0x53.toByte() && bytes[1] == 0x47.toByte()
            val isPlaintextFrame = bytes.size >= 2 && bytes[0] == 0x53.toByte() && bytes[1] == 0x54.toByte()

            val plain = if (isEncryptedFrame) {
                // Try local phrase first, then fall back to default shared mesh key
                val decrypted = runCatching { security?.open(bytes) }.getOrNull()
                    ?: runCatching { defaultSecurity?.open(bytes) }.getOrNull()

                if (decrypted == null) {
                    store.metric(null, "rx_security_rejected", "Decryption failed with local and default keys")
                    status("⚠️ Encrypted frame received: Pairing phrase mismatch. Set matching phrase on both phones.")
                    return@execute
                }
                decrypted
            } else {
                if (security != null) {
                    store.metric(null, "rx_unencrypted_warn", "Peer sent plaintext; decoding anyway (encryption asymmetry)")
                    status("ℹ️ Peer connected in open mode — messages accepted")
                }
                bytes
            }
            val message = runCatching { SutraCodec.decode(plain) }.getOrElse { error ->
                store.metric(null, "rx_invalid", error.message.orEmpty())
                status("Rejected invalid SUTRA frame: ${error.message}")
                return@execute
            }
            if (message.sourceId == sourceId) return@execute
            if (message.destinationId != 0L && message.destinationId != sourceId) return@execute
            if (message.type == SutraMessageType.HELLO) {
                val now = android.os.SystemClock.elapsedRealtime()
                val beacon = team.accept(message, receivedMs) ?: return@execute
                listener?.onTeamChanged(team.snapshot(now))
                if (beacon.hops < TeamPositionCodec.MAX_HOPS) {
                    val forwarded = message.copy(payload = TeamPositionCodec.encode(beacon.copy(hops = beacon.hops + 1), now))
                    val frame = SutraCodec.encode(forwarded)
                    runCatching { sendFrame(security?.seal(frame) ?: frame) }
                }
                return@execute
            }
            val id = message.id.toString()
            store.metric(id, "rx_validated", "type=${message.type},bytes=${bytes.size}")
            when (message.type) {
                SutraMessageType.TEXT_FINAL -> {
                    // Start of metric 2 (text received -> audio playing), on the monotonic clock.
                    receivedAt.putIfAbsent(id, System.nanoTime())
                    sendAck(message, SutraMessageType.RECEIVED_ACK)
                    val fresh = store.recordReceipt(id)
                    if (fresh) {
                        conversation.add(ConversationEntry(id, SutraCodec.utf8(message.payload),
                            message.language.pipelineName, false, message.flags and SutraFlags.ALERT != 0, MessageProgress.RECEIVED))
                        listener?.onConversationChanged()
                    }
                    listener?.onTransmissionMessage(message, null, !fresh)
                    if (message.shouldRelayLive(fresh)) relayLive(message, plain)
                    if (fresh) {
                        if (message.flags and SutraFlags.ALERT != 0) {
                            triggerEmergencyAlertFeedback()
                        }
                        speakOrQueue(message)
                    }
                }
                SutraMessageType.ALERT_CORE -> {
                    triggerEmergencyAlertFeedback()
                    listener?.onTransmissionMessage(message, null, false)
                }
                SutraMessageType.REPAIR_EVIDENCE -> {
                    sendAck(message, SutraMessageType.REPAIR_REQUEST)
                    listener?.onTransmissionMessage(message, null, false)
                }
                SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED,
                SutraMessageType.PLAYBACK_FINISHED, SutraMessageType.REPAIR_REQUEST -> {
                    val elapsed = sentAt[id]?.let { (System.nanoTime() - it) / 1_000_000 }
                    if (message.type == SutraMessageType.RECEIVED_ACK) store.acknowledge(id)
                    if (message.type == SutraMessageType.PLAYBACK_FINISHED) {
                        sentAt.remove(id); sentAtEpoch.remove(id); speechEndEpoch.remove(id)
                    }
                    store.metric(id, message.type.name.lowercase(), "sender_elapsed_ms=$elapsed")
                    recordPeerClock(id, message)
                    val progress = when (message.type) {
                        SutraMessageType.RECEIVED_ACK -> MessageProgress.RECEIVED
                        SutraMessageType.PLAYBACK_STARTED -> MessageProgress.PLAYING
                        SutraMessageType.PLAYBACK_FINISHED -> MessageProgress.HEARD
                        else -> null
                    }
                    if (progress != null && conversation.advance(id, true, progress, message.sourceId)) {
                        listener?.onConversationChanged()
                    }
                    listener?.onTransmissionMessage(message, elapsed, false)
                }
                SutraMessageType.HELLO -> listener?.onTransmissionMessage(message, null, false)
            }
        }
    }

    private fun relayLive(message: SutraMessage, plain: ByteArray) {
        val alert = message.flags and SutraFlags.ALERT != 0
        if (alert) {
            // ponytail: one bounded epidemic copy; add per-peer copy budgets only after a larger trace needs them.
            store.enqueue(message, plain)
            store.metric(message.id.toString(), "relay_stored", "alert=true")
        }
        runCatching { sendFrame(security?.seal(plain) ?: plain) }
            .onSuccess { store.metric(message.id.toString(), "relay_forwarded", "alert=$alert") }
            .onFailure { store.metric(message.id.toString(), "relay_unavailable", it.message.orEmpty()) }
    }

    private fun flushOutbox() {
        io.execute {
            if (!isConnected()) {
                status("Message queued; waiting for Wi-Fi Direct or Bluetooth")
                return@execute
            }
            store.pending().forEach { pending ->
                val wire = security?.seal(pending.bytes) ?: pending.bytes
                val sent = runCatching { sendFrame(wire) }.isSuccess
                if (sent) {
                    store.markAttempt(pending.key)
                    store.metric(pending.messageId, "tx_complete", "wire_bytes=${wire.size}")
                    if (conversation.advance(pending.messageId, true, MessageProgress.SENDING)) listener?.onConversationChanged()
                } else return@forEach
            }
        }
    }

    private fun sendFrame(bytes: ByteArray) {
        var delivered = false
        if (wifi.isConnected) {
            runCatching { wifi.send(bytes) }.onSuccess { delivered = true }
        }
        if (bluetooth.isConnected) {
            runCatching { bluetooth.send(bytes) }.onSuccess { delivered = true }
        }
        check(delivered) { "No peer is connected" }
    }

    /**
     * Folds a peer's clock reading out of an acknowledgement payload into the latency record.
     *
     * A RECEIVED_ACK gives the NTP-style exchange (t0 = our send, t1 = the peer's stamp,
     * t2 = now) and so the clock offset. A PLAYBACK_STARTED gives metric 4 once that offset is
     * known: our speech-end converted onto the peer's clock, subtracted from when the peer's first
     * audio frame actually played. Peers on older builds send an empty payload, so every step here
     * is a no-op for them rather than an error.
     */
    private fun recordPeerClock(id: String, message: SutraMessage) {
        val peerEpoch = SutraTelemetry.decodeEpoch(message.payload) ?: return
        when (message.type) {
            SutraMessageType.RECEIVED_ACK -> {
                val sent = sentAtEpoch[id] ?: return
                val measured = SutraTelemetry.observeExchange(sent, peerEpoch, System.currentTimeMillis()) ?: return
                store.metric(id, "peer_clock_sync", "offset_ms=${measured.first},rtt_ms=${measured.second}")
            }
            SutraMessageType.PLAYBACK_STARTED -> {
                val speechEnd = speechEndEpoch[id] ?: sentAtEpoch[id] ?: return
                val latency = SutraTelemetry.peerPlaybackLatencyMs(speechEnd, peerEpoch)
                if (latency == null) {
                    store.metric(id, "peer_playback_unsynced", "no clock exchange yet; cross-device latency not reported")
                } else {
                    store.metric(id, "peer_playback_latency", "ms=$latency,offset_ms=${SutraTelemetry.clockOffsetMs},rtt_ms=${SutraTelemetry.roundTripMs}")
                }
            }
            else -> Unit
        }
    }

    /**
     * Records when a phrase was cut, on the wall clock, so metric 4 can be expressed against the
     * peer's playback stamp. Called by the capture side before [send].
     */
    fun noteSpeechEnd(messageId: String, epochMs: Long = System.currentTimeMillis()) {
        speechEndEpoch[messageId] = epochMs
    }

    private fun sendAck(original: SutraMessage, type: SutraMessageType) {
        val progress = when (type) {
            SutraMessageType.PLAYBACK_STARTED -> MessageProgress.PLAYING
            SutraMessageType.PLAYBACK_FINISHED -> MessageProgress.HEARD
            else -> null
        }
        if (progress != null && conversation.advance(original.id.toString(), false, progress)) listener?.onConversationChanged()
        // Carry our clock in the two acknowledgements the sender times against: RECEIVED_ACK drives
        // the offset estimate, PLAYBACK_STARTED is the receive-side event for metric 4. Older peers
        // send an empty payload and simply get no cross-device number.
        val stamp = when (type) {
            SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED ->
                SutraTelemetry.encodeEpoch(System.currentTimeMillis())
            else -> ByteArray(0)
        }
        val ack = SutraSemanticEncoder.acknowledgement(original, type, sourceId, stamp)
        runCatching { sendFrame(security?.seal(SutraCodec.encode(ack)) ?: SutraCodec.encode(ack)) }
            .onFailure { store.metric(original.id.toString(), "ack_failed", type.name) }
    }

    private fun initialiseTts() {
        tts = TextToSpeech(this) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            if (!ttsReady) status("Offline TTS unavailable")
            else synchronized(speechQueue) {
                while (speechQueue.isNotEmpty()) speakIncoming(speechQueue.removeFirst())
            }
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    pendingSpeech[utteranceId]?.let {
                        store.metric(it.id.toString(), "playback_started")
                        sendAck(it, SutraMessageType.PLAYBACK_STARTED)
                    }
                }

                override fun onDone(utteranceId: String) {
                    pendingSpeech.remove(utteranceId)?.let {
                        store.markPlayed(it.id.toString())
                        store.metric(it.id.toString(), "playback_finished")
                        sendAck(it, SutraMessageType.PLAYBACK_FINISHED)
                    }
                    AudioDuplexGate.endPlayback()
                    abandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    pendingSpeech.remove(utteranceId)?.let {
                        conversation.playbackFailed(it.id.toString())
                        listener?.onConversationChanged()
                    }
                    AudioDuplexGate.endPlayback()
                    abandonAudioFocus()
                }
            })
        }
    }

    private fun speakOrQueue(message: SutraMessage) {
        if (ttsReady) speakIncoming(message)
        else synchronized(speechQueue) { speechQueue.addLast(message) }
    }

    private fun speakIncoming(message: SutraMessage) {
        val text = runCatching { SutraCodec.utf8(message.payload) }.getOrElse { return }
        val language = message.language.pipelineName
        val alert = message.flags and SutraFlags.ALERT != 0
        if (hasOnnxTts(language)) {
            playOnnx(text, language, alert, message)
            return
        }
        val utteranceId = "sutra-${message.id}"
        pendingSpeech[utteranceId] = message
        if (!speakSystem(text, language, alert, utteranceId)) {
            pendingSpeech.remove(utteranceId)
            conversation.playbackFailed(message.id.toString())
            listener?.onConversationChanged()
            store.metric(message.id.toString(), "tts_no_voice",
                "language=$language,onnx=false,system=false")
            Log.e("SUTRA_TTS", "No TTS voice available for language='$language'. " +
                "Install Indic-TTS ONNX models (run scripts/download_indic_tts.py) or " +
                "install a '$language' offline voice in Android Settings → Text-to-Speech.")
            status("⚠ No offline voice for $language — install Indic-TTS ONNX models")
        }
    }

    private fun speakSystem(text: String, language: String, alert: Boolean, utteranceId: String): Boolean {
        val engine = tts ?: return false
        val tag = mapOf(
            "english" to "en-IN", "hindi" to "hi-IN", "gujarati" to "gu-IN",
            "marathi" to "mr-IN", "kannada" to "kn-IN", "malayalam" to "ml-IN",
            "tamil" to "ta-IN", "telugu" to "te-IN", "odia" to "or-IN", "bengali" to "bn-IN",
        )[language] ?: "en-IN"
        val locale = Locale.forLanguageTag(tag)

        val availableVoices = runCatching { engine.voices }.getOrNull().orEmpty()
        Log.i("SUTRA_TTS", "speakSystem: language=$language tag=$tag availableVoices=${availableVoices.size}")

        // Match on 2-letter (e.g. kn) or 3-letter (e.g. kan) ISO language code
        val langCode2 = locale.language.lowercase(Locale.ROOT)
        val langCode3 = runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrDefault("")
        // Select the highest quality offline voice available for this locale
        val voice = availableVoices.filter { v ->
            val vLang = v.locale.language.lowercase(Locale.ROOT)
            val vIso3 = runCatching { v.locale.isO3Language.lowercase(Locale.ROOT) }.getOrDefault("")
            val langMatch = vLang == langCode2 || (langCode3.isNotEmpty() && vLang == langCode3) ||
                (vIso3.isNotEmpty() && (vIso3 == langCode3 || vIso3 == langCode2))
            langMatch && (!v.isNetworkConnectionRequired || engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE)
        }.sortedWith(
            compareByDescending<Voice> { if (it.locale.country.equals(locale.country, ignoreCase = true)) 1 else 0 }
                .thenByDescending { if (!it.isNetworkConnectionRequired) 1 else 0 }
                .thenByDescending { it.quality }
        ).firstOrNull()

        val voiceConfigured = if (voice != null) {
            val res = engine.setVoice(voice)
            Log.i("SUTRA_TTS", "Selected voice: ${voice.name} (quality=${voice.quality}, latency=${voice.latency}) result=$res")
            res == TextToSpeech.SUCCESS
        } else false

        if (!voiceConfigured) {
            val langRes = engine.setLanguage(locale)
            Log.i("SUTRA_TTS", "setLanguage($locale) result=$langRes")
            if (langRes < TextToSpeech.LANG_AVAILABLE) {
                val reason = when (langRes) {
                    TextToSpeech.LANG_MISSING_DATA -> "voice pack missing"
                    TextToSpeech.LANG_NOT_SUPPORTED -> "not supported by TTS engine"
                    else -> "unavailable (code $langRes)"
                }
                status("No offline voice for $language ($reason). Install voice in Android TTS settings.")
                return false
            }
        }

        val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        engine.setSpeechRate(0.95f)
        engine.setPitch(if (alert) 1.08f else 1.0f)
        requestAudioFocus(alert)
        AudioDuplexGate.beginPlayback()
        val accepted = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
        if (!accepted) {
            AudioDuplexGate.endPlayback()
            status("TTS rejected the received message")
        }
        return accepted
    }

    private fun hasOnnxTts(language: String): Boolean {
        val modelDir = resolveModelDir(this)
        val multiDir = File(modelDir, "tts")
        val legacyDir = File(modelDir, "tts/en-hi")
        if (!multiDir.isDirectory && !legacyDir.isDirectory) return false
        val tts = loadOnnxTts() ?: return false
        return tts.supportsLanguage(language)
    }

    /**
     * Loads the Indic-TTS session during startup instead of on the first received message.
     *
     * Runs on [ttsWorker] — the same single thread that later synthesises — so it can never race a
     * real playback, and never touches the main thread. Failure is already handled inside
     * [loadOnnxTts] (it records a metric and falls back to the system voice), so a missing or
     * broken model bundle leaves behaviour exactly as it was before warming existed.
     */
    private fun warmTts() {
        ttsWorker.execute {
            val started = System.nanoTime()
            val runtime = runCatching { loadOnnxTts() }.getOrNull()
            store.metric(null, "tts_warm", "loaded=${runtime != null},ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    @Synchronized
    private fun loadOnnxTts(): IndicOnnxTts? {
        onnxTts?.let { return it }
        val modelDir = resolveModelDir(this)
        val multiDir = File(modelDir, "tts")
        val legacyDir = File(modelDir, "tts/en-hi")
        Log.i("SUTRA_TTS", "TTS model root=${modelDir.path}, multi=${multiDir.path}, exists=${multiDir.isDirectory}")
        // Only permanently skip if neither directory is present.
        // If models were pushed mid-session, allow a retry.
        val dirPresent = multiDir.isDirectory || legacyDir.isDirectory
        if (!dirPresent) {
            if (!onnxTtsAttempted) {
                onnxTtsAttempted = true
                Log.w("SUTRA_TTS", "TTS model directory not found at ${multiDir.path}. " +
                    "Run scripts/download_indic_tts.py then scripts/stage_android_models.py " +
                    "and push models to device.")
            }
            return null
        }
        // Directory exists — attempt load (don't lock out retries on transient failures)

        val root = when {
            multiDir.isDirectory && (File(multiDir, "shared/hifigan.onnx").isFile || File(multiDir, "hifigan.onnx").isFile) -> multiDir
            legacyDir.isDirectory -> legacyDir
            else -> multiDir
        }
        return runCatching { IndicOnnxTts(root) }
            .onSuccess {
                onnxTts = it
                val count = it.supportedLanguages().size
                // Loading a voice session can fail later than the vocoder session (for example
                // when a model uses an operator unavailable in the installed ONNX Runtime).
                // Keep that diagnostic from escaping the Result callback and crashing the UI.
                val pitch = runCatching { it.supportsPitchConditioning }.getOrDefault(false)
                store.metric(null, "tts_model_loaded", "ai4bharat-indic-tts-multilang,voices=$count,prosody=$pitch")
                status("AI4Bharat Indic-TTS ready (${count} voices${if (pitch) ", pitch control" else ""})")
            }
            .onFailure {
                store.metric(null, "tts_model_failed", it.message.orEmpty())
                Log.e("SUTRA_TTS", "Indic-TTS ONNX load failed", it)
                status("Indic-TTS failed; using installed system voice")
            }
            .getOrNull()
    }

    private fun playOnnx(
        text: String,
        language: String,
        alert: Boolean,
        message: SutraMessage?,
        emphasizedWord: String? = null,
    ) {
        ttsWorker.execute {
            AudioDuplexGate.beginPlayback()
            val runtime = loadOnnxTts()
            if (runtime == null) {
                AudioDuplexGate.endPlayback()
                val id = message?.let { "sutra-${it.id}" } ?: "local-${System.nanoTime()}"
                if (message != null) pendingSpeech[id] = message
                if (!speakSystem(text, language, alert, id)) pendingSpeech.remove(id)
                return@execute
            }
            if (emphasizedWord != null && !runtime.supportsPitchConditioning) {
                status("Pitch-control model not installed; replaying neutral voice")
            }
            val started = System.nanoTime()
            var track: AudioTrack? = null
            try {
                val chunks = runtime.chunks(text, language)
                require(chunks.isNotEmpty()) { "TTS text has no supported characters" }
                val first = runtime.synthesize(chunks.first(), emphasizedWord, language)
                val format = AudioFormat.Builder()
                    .setSampleRate(runtime.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                val bufferBytes = pcmFloatBufferBytes(
                    AudioTrack.getMinBufferSize(runtime.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
                    runtime.sampleRate,
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialise" }
                requestAudioFocus(alert)
                track.setVolume(1f)
                val prebuffer = minOf(first.size, bufferBytes / Float.SIZE_BYTES)
                writeAll(track, first, 0, prebuffer)
                track.play()
                message?.let {
                    store.metric(it.id.toString(), "playback_started", "backend=indic-tts")
                    // Metric 2: received -> first audio frame actually written, one clock, no sync needed.
                    receivedAt.remove(it.id.toString())?.let { rx ->
                        store.metric(it.id.toString(), "rx_to_first_audio", "ms=${(System.nanoTime() - rx) / 1_000_000}")
                    }
                    sendAck(it, SutraMessageType.PLAYBACK_STARTED)
                }
                store.metric(message?.id?.toString(), "tts_first_audio", "ms=${(System.nanoTime() - started) / 1_000_000}")
                writeAll(track, first, prebuffer, first.size - prebuffer)
                var frames = first.size
                val gap = FloatArray(runtime.chunkGapFrames)
                chunks.drop(1).forEach { chunk ->
                    writeAll(track, gap, 0, gap.size)
                    val audio = runtime.synthesize(chunk, emphasizedWord, language)
                    writeAll(track, audio, 0, audio.size)
                    frames += gap.size + audio.size
                }
                val deadline = System.nanoTime() + (frames * 1_000_000_000L / runtime.sampleRate) + 2_000_000_000L
                while (track.playbackHeadPosition < frames && System.nanoTime() < deadline) Thread.sleep(10)
                check(track.playbackHeadPosition >= frames) { "Playback did not finish before the audio deadline" }
                message?.let {
                    store.markPlayed(it.id.toString())
                    store.metric(it.id.toString(), "playback_finished", "backend=indic-tts")
                    sendAck(it, SutraMessageType.PLAYBACK_FINISHED)
                }
            } catch (error: Exception) {
                message?.let {
                    conversation.playbackFailed(it.id.toString())
                    listener?.onConversationChanged()
                }
                store.metric(message?.id?.toString(), "tts_failed", error.message.orEmpty())
                status("Indic-TTS playback failed: ${error.message}")
            } finally {
                runCatching { track?.stop() }
                track?.release()
                AudioDuplexGate.endPlayback()
                abandonAudioFocus()
            }
        }
    }

    private fun writeAll(track: AudioTrack, audio: FloatArray, start: Int, length: Int) {
        var offset = start
        val end = start + length
        while (offset < end) {
            val written = track.write(audio, offset, end - offset, AudioTrack.WRITE_BLOCKING)
            require(written > 0) { "AudioTrack write failed: $written" }
            offset += written
        }
    }

    private var originalMusicVolume: Int? = null

    fun triggerEmergencyAlertFeedback() {
        runCatching {
            // 1. Maximize STREAM_MUSIC (used by TTS and audio engines)
            if (originalMusicVolume == null) {
                originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, AudioManager.FLAG_SHOW_UI)

            // 2. Maximize STREAM_ALARM
            if (originalAlarmVolume == null) {
                originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)

            // 3. Maximize STREAM_NOTIFICATION
            val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)

            // 4. Play emergency alert siren / tone immediately using ToneGenerator
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1200)

            // 5. Heavy vibration pattern (unmissable alert)
            val vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
            }
        }
    }

    private fun requestAudioFocus(alert: Boolean) {
        if (alert) {
            triggerEmergencyAlertFeedback()
        }
        val gain = if (alert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        focusRequest = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
            .setOnAudioFocusChangeListener { }
            .build().also(audioManager::requestAudioFocus)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        originalAlarmVolume?.let { savedVol ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0) }
            originalAlarmVolume = null
        }
        originalMusicVolume?.let { savedVol ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0) }
            originalMusicVolume = null
        }
    }

    private fun status(value: String) {
        Log.i("SUTRA", value)
        listener?.onTransmissionStatus(value)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Offline peer connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SutraTransmissionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SUTRA offline receiver")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        listener = null
        wifiDirect?.close()
        wifi.close()
        bluetooth.close()
        tts?.shutdown()
        ttsWorker.shutdownNow()
        runCatching { ttsWorker.awaitTermination(2, TimeUnit.SECONDS) }
        onnxTts?.close()
        AudioDuplexGate.reset()
        store.metric(null, "service_stopped")
        store.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
