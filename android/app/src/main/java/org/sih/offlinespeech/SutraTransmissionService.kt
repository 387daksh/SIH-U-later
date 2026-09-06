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
import java.util.concurrent.TimeUnit
import kotlin.math.max

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
    private val ttsWorker = Executors.newSingleThreadExecutor()
    private val sentAt = ConcurrentHashMap<String, Long>()
    private val pendingSpeech = ConcurrentHashMap<String, SutraMessage>()
    private val speechQueue = ArrayDeque<SutraMessage>()
    private lateinit var wifi: TcpPeerTransport
    private lateinit var bluetooth: BluetoothPeerTransport
    private lateinit var store: SutraStore
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onnxTts: IndicOnnxTts? = null
    private var onnxTtsAttempted = false
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

    fun speakLocal(text: String, language: String): Boolean {
        if (text.isBlank()) return false
        if (hasOnnxTts(language)) {
            playOnnx(text, language, alert = false, message = null)
            return true
        }
        return speakSystem(text, language, alert = false, utteranceId = "local-${System.nanoTime()}")
    }

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
        val voice = engine.voices.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            .maxByOrNull { if (it.locale.country == locale.country) 1 else 0 }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            status("No installed offline TTS voice for $language")
            return false
        }
        val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        requestAudioFocus(alert)
        val accepted = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
        if (!accepted) {
            status("TTS rejected the received message")
        }
        return accepted
    }

    private fun hasOnnxTts(language: String): Boolean {
        if (language != "english" && language != "hindi") return false
        val root = File(resolveModelDir(this), "tts/en-hi")
        return File(root, "fastpitch.onnx").isFile && File(root, "hifigan.onnx").isFile &&
            File(root, "meta.json").isFile
    }

    @Synchronized
    private fun loadOnnxTts(): IndicOnnxTts? {
        onnxTts?.let { return it }
        if (onnxTtsAttempted) return null
        onnxTtsAttempted = true
        val root = File(resolveModelDir(this), "tts/en-hi")
        return runCatching { IndicOnnxTts(root) }
            .onSuccess {
                onnxTts = it
                store.metric(null, "tts_model_loaded", "ai4bharat-indic-tts-en+hi-int8")
                status("AI4Bharat Indic-TTS ready")
            }
            .onFailure {
                store.metric(null, "tts_model_failed", it.message.orEmpty())
                status("Indic-TTS failed; using installed system voice")
            }
            .getOrNull()
    }

    private fun playOnnx(text: String, language: String, alert: Boolean, message: SutraMessage?) {
        ttsWorker.execute {
            val runtime = loadOnnxTts()
            if (runtime == null) {
                val id = message?.let { "sutra-${it.id}" } ?: "local-${System.nanoTime()}"
                if (message != null) pendingSpeech[id] = message
                if (!speakSystem(text, language, alert, id)) pendingSpeech.remove(id)
                return@execute
            }
            val started = System.nanoTime()
            var track: AudioTrack? = null
            try {
                val chunks = runtime.chunks(text)
                require(chunks.isNotEmpty()) { "TTS text has no supported characters" }
                val first = runtime.synthesize(chunks.first())
                val format = AudioFormat.Builder()
                    .setSampleRate(runtime.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                val bufferBytes = max(
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
                    sendAck(it, SutraMessageType.PLAYBACK_STARTED)
                }
                store.metric(message?.id?.toString(), "tts_first_audio", "ms=${(System.nanoTime() - started) / 1_000_000}")
                writeAll(track, first, prebuffer, first.size - prebuffer)
                var frames = first.size
                val gap = FloatArray(runtime.chunkGapFrames)
                chunks.drop(1).forEach { chunk ->
                    writeAll(track, gap, 0, gap.size)
                    val audio = runtime.synthesize(chunk)
                    writeAll(track, audio, 0, audio.size)
                    frames += gap.size + audio.size
                }
                val deadline = System.nanoTime() + (frames * 1_000_000_000L / runtime.sampleRate) + 2_000_000_000L
                while (track.playbackHeadPosition < frames && System.nanoTime() < deadline) Thread.sleep(10)
                message?.let {
                    store.markPlayed(it.id.toString())
                    store.metric(it.id.toString(), "playback_finished", "backend=indic-tts")
                    sendAck(it, SutraMessageType.PLAYBACK_FINISHED)
                }
            } catch (error: Exception) {
                store.metric(message?.id?.toString(), "tts_failed", error.message.orEmpty())
                status("Indic-TTS playback failed: ${error.message}")
            } finally {
                runCatching { track?.stop() }
                track?.release()
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
        ttsWorker.shutdownNow()
        runCatching { ttsWorker.awaitTermination(2, TimeUnit.SECONDS) }
        onnxTts?.close()
        store.metric(null, "service_stopped")
        store.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
