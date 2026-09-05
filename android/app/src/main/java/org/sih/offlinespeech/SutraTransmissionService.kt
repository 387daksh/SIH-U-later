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
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

interface SutraTransmissionListener {
    fun onTransmissionStatus(status: String)
    fun onTransmissionMessage(message: SutraMessage, elapsedMs: Long?, duplicate: Boolean)
}

class SutraTransmissionService : Service(), PeerTransportListener {
    companion object {
        private const val CHANNEL = "sutra_connection"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_STOP = "org.sih.offlinespeech.STOP_TRANSMISSION"
    }

    inner class LocalBinder : Binder() { val service: SutraTransmissionService get() = this@SutraTransmissionService }

    private val binder = LocalBinder()
    private val io = Executors.newSingleThreadExecutor()
    private val sentAt = ConcurrentHashMap<String, Long>()
    private val pendingSpeech = ConcurrentHashMap<String, SutraMessage>()
    private val speechQueue = ArrayDeque<SutraMessage>()
    private lateinit var wifi: TcpPeerTransport
    private lateinit var bluetooth: BluetoothPeerTransport
    private lateinit var store: SutraStore
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var security: SecureFrameCodec? = null
    private var focusRequest: AudioFocusRequest? = null
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
        getSharedPreferences("sutra", MODE_PRIVATE).getString("pairing_phrase", null)
            ?.takeIf { it.length >= 12 }?.let { security = SecureFrameCodec.fromPhrase(it) }
        wifi = TcpPeerTransport(this)
        bluetooth = BluetoothPeerTransport(this, this)
        initialiseTts()
        store.metric(null, "service_started")
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
    }

    fun wifiHost() = wifi.host()
    fun wifiConnect(host: String) = wifi.connect(host)
    fun wifiDisconnect() = wifi.disconnect()
    fun bluetoothListen() = bluetooth.listen()
    fun bluetoothConnect(address: String) = bluetooth.connect(address)
    fun bluetoothPeers(): List<BluetoothPeer> = bluetooth.bondedPeers()
    fun disconnectAll() { wifi.disconnect(); bluetooth.disconnect() }
    fun isConnected(): Boolean = wifi.isConnected || bluetooth.isConnected

    fun setPairingPhrase(phrase: String) {
        if (phrase.isBlank()) {
            security = null
            getSharedPreferences("sutra", MODE_PRIVATE).edit().remove("pairing_phrase").apply()
            status("Encryption disabled for laptop diagnostics")
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
            sentAt.putIfAbsent(message.id.toString(), System.nanoTime())
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

    override fun onTransportStatus(status: String) {
        status(status)
        if (status.contains("connected", ignoreCase = true)) flushOutbox()
    }

    override fun onTransportMessage(bytes: ByteArray) {
        io.execute {
            val plain = runCatching { security?.open(bytes) ?: bytes }.getOrElse { error ->
                store.metric(null, "rx_security_rejected", error.message.orEmpty())
                status("Rejected unauthenticated/wrong-phrase frame")
                return@execute
            }
            val message = runCatching { SutraCodec.decode(plain) }.getOrElse { error ->
                store.metric(null, "rx_invalid", error.message.orEmpty())
                status("Rejected invalid SUTRA frame: ${error.message}")
                return@execute
            }
            if (message.sourceId == sourceId) return@execute
            if (message.destinationId != 0L && message.destinationId != sourceId) return@execute
            val id = message.id.toString()
            store.metric(id, "rx_validated", "type=${message.type},bytes=${bytes.size}")
            when (message.type) {
                SutraMessageType.TEXT_FINAL -> {
                    sendAck(message, SutraMessageType.RECEIVED_ACK)
                    val fresh = store.recordReceipt(id)
                    listener?.onTransmissionMessage(message, null, !fresh)
                    if (fresh) {
                        speakOrQueue(message)
                        if (message.flags and SutraFlags.ALERT != 0) {
                            // ponytail: one bounded epidemic copy; add per-peer copy budgets only after a 3-phone trace needs them.
                            store.enqueue(message, plain)
                            store.metric(id, "relay_stored", "alert=true")
                        }
                    }
                }
                SutraMessageType.ALERT_CORE -> listener?.onTransmissionMessage(message, null, false)
                SutraMessageType.REPAIR_EVIDENCE -> {
                    sendAck(message, SutraMessageType.REPAIR_REQUEST)
                    listener?.onTransmissionMessage(message, null, false)
                }
                SutraMessageType.RECEIVED_ACK, SutraMessageType.PLAYBACK_STARTED,
                SutraMessageType.PLAYBACK_FINISHED, SutraMessageType.REPAIR_REQUEST -> {
                    val elapsed = sentAt[id]?.let { (System.nanoTime() - it) / 1_000_000 }
                    if (message.type == SutraMessageType.RECEIVED_ACK) store.acknowledge(id)
                    if (message.type == SutraMessageType.PLAYBACK_FINISHED) sentAt.remove(id)
                    store.metric(id, message.type.name.lowercase(), "sender_elapsed_ms=$elapsed")
                    listener?.onTransmissionMessage(message, elapsed, false)
                }
                SutraMessageType.HELLO -> listener?.onTransmissionMessage(message, null, false)
            }
        }
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

    private fun sendAck(original: SutraMessage, type: SutraMessageType) {
        val ack = SutraSemanticEncoder.acknowledgement(original, type, sourceId)
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
                    abandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    pendingSpeech.remove(utteranceId)
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
        val engine = tts ?: return
        val language = message.language.pipelineName
        val tag = mapOf(
            "english" to "en-IN", "hindi" to "hi-IN", "gujarati" to "gu-IN",
            "marathi" to "mr-IN", "kannada" to "kn-IN", "malayalam" to "ml-IN",
            "tamil" to "ta-IN", "telugu" to "te-IN", "odia" to "or-IN", "bengali" to "bn-IN",
        )[language] ?: "en-IN"
        val locale = Locale.forLanguageTag(tag)
        val voice = engine.voices.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            .maxByOrNull { if (it.locale.country == locale.country) 1 else 0 }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            status("No installed offline TTS voice for $language")
            return
        }
        val alert = message.flags and SutraFlags.ALERT != 0
        val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        requestAudioFocus(alert)
        val utteranceId = "sutra-${message.id}"
        pendingSpeech[utteranceId] = message
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) != TextToSpeech.SUCCESS) {
            pendingSpeech.remove(utteranceId)
            status("TTS rejected the received message")
        }
    }

    private fun requestAudioFocus(alert: Boolean) {
        val gain = if (alert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        focusRequest = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
            .setOnAudioFocusChangeListener { }
            .build().also(audioManager::requestAudioFocus)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
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
        wifi.close()
        bluetooth.close()
        tts?.shutdown()
        store.metric(null, "service_stopped")
        store.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
