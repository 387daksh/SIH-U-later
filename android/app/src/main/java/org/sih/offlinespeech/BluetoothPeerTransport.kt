package org.sih.offlinespeech

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

data class BluetoothPeer(val name: String, val address: String, val rssi: Int? = null,
    val distanceMetres: Double? = null, val connected: Boolean = false)

@SuppressLint("MissingPermission")
class BluetoothPeerTransport(context: Context, private val listener: PeerTransportListener,
    private val peerListener: (List<BluetoothPeer>, Boolean) -> Unit = { _, _ -> }) : AutoCloseable {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f5a40f45-8a20-4fa9-89d1-bad77c49f5c2")
    }
    private val app = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newCachedThreadPool()
    private val connector = Executors.newSingleThreadExecutor()
    private val links = FramedPeerLinks(SutraCodec.MAX_PAYLOAD_BYTES + 1024)
    private val peers = ConcurrentHashMap<String, BluetoothPeer>()
    private val candidates = LinkedHashMap<String, BluetoothDevice>()
    private val confirmed = mutableSetOf<String>()
    private val connecting = ConcurrentHashMap<String, BluetoothSocket>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val listening = AtomicBoolean()
    private val generation = AtomicInteger()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var closed = false
    private var automatic = false
    private var scanning = false
    /** Wi-Fi Direct is the primary transport; Bluetooth only actively seeks new links when it has no active group. */
    @Volatile var wifiDirectGrouped = false
    private var queryAddress: String? = null
    private val queried = mutableSetOf<String>()
    private var queryCallback: ((List<BluetoothPeer>) -> Unit)? = null
    val isConnected get() = links.size > 0
    val peerCount get() = links.size

    private fun permitted() = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE).all {
            app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    } else app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val cycle = object : Runnable {
        override fun run() {
            if (!automatic || closed) return
            runCatching {
                if (permitted() && adapter?.isEnabled == true) {
                    listen()
                    // Wi-Fi Direct already has a working group; don't spend radio/battery chasing new BT links.
                    if (!wifiDirectGrouped) scan()
                }
            }.onFailure { listener.onTransportStatus("Bluetooth discovery paused: ${it.message}") }
            handler.postDelayed(this, 45_000L + Random.nextLong(8_000))
        }
    }
    fun startAutomatic() {
        if (closed) return
        automatic = true
        handler.removeCallbacks(cycle)
        handler.post(cycle)
    }
    fun stopAutomatic() {
        automatic = false
        handler.removeCallbacks(cycle)
        handler.removeCallbacks(queryTimeout)
        queryAddress = null
        queryCallback = null
        candidates.clear()
        queried.clear()
    }
    private val queryTimeout = Runnable { queryAddress = null; queryNext() }
    private fun queryNext() {
        if (closed || !permitted() || adapter?.isDiscovering == true || queryAddress != null || queued.isNotEmpty()) return
        val device = candidates.values.firstOrNull { it.address !in queried && !links.contains(it.address) }
        if (device == null) {
            queryCallback?.invoke(sutraSnapshot())
            queryCallback = null
            return
        }
        queried.add(device.address)
        queryAddress = device.address
        handler.postDelayed(queryTimeout, 4_000)
        if (!runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)) {
            handler.removeCallbacks(queryTimeout)
            queryAddress = null
            handler.post { queryNext() }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || !permitted()) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> if (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON && automatic) {
                    handler.removeCallbacks(cycle); handler.post(cycle)
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> { scanning = true; publish() }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanning = false; publish(); queryNext() }
                BluetoothDevice.ACTION_FOUND -> if (device != null) {
                    if (candidates.size < 64 || device.address in candidates) candidates[device.address] = device
                    val measured = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val old = peers[device.address]
                    val rssi = if (measured == Short.MIN_VALUE.toInt()) old?.rssi
                        else BluetoothDistanceEstimator.smooth(old?.rssi, measured)
                    peers[device.address] = BluetoothPeer(device.name ?: "Nearby phone", device.address,
                        rssi, rssi?.let(BluetoothDistanceEstimator::metres), links.contains(device.address))
                    publish()
                }
                BluetoothDevice.ACTION_UUID -> if (device != null && device.address == queryAddress) {
                    @Suppress("DEPRECATION")
                    val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    if (uuids?.any { it.toString() == SERVICE_UUID.toString() } == true) {
                        confirmed.add(device.address)
                        peers.putIfAbsent(device.address, BluetoothPeer(device.name ?: "SUTRA phone", device.address))
                        // Wi-Fi Direct is the primary transport; only fall back to an automatic BT connect without it.
                        if (automatic && !wifiDirectGrouped) {
                            val epoch = generation.get()
                            // Random backoff resolves simultaneous inbound/outbound duplicate attempts.
                            handler.postDelayed({
                                if (automatic && !wifiDirectGrouped && epoch == generation.get() && !closed) connect(device.address)
                            }, Random.nextLong(500, 3_000))
                        }
                    }
                    handler.removeCallbacks(queryTimeout)
                    queryAddress = null
                    publish()
                    queryNext()
                }
            }
        }
    }
    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_UUID)
        }
        // Bluetooth events may originate in the separate privileged Bluetooth process.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else { @Suppress("DEPRECATION") app.registerReceiver(receiver, filter) }
    }
    fun bondedPeers(): List<BluetoothPeer> {
        if (!permitted()) return emptyList()
        runCatching { adapter?.bondedDevices?.forEach { device ->
            val old = peers[device.address]
            peers[device.address] = BluetoothPeer(device.name ?: "Bluetooth phone", device.address,
                old?.rssi, old?.distanceMetres, links.contains(device.address))
        } }
        return sutraSnapshot()
    }
    fun fetchSutraPeers(onResult: (List<BluetoothPeer>) -> Unit) {
        if (!permitted()) { onResult(emptyList()); return }
        queryCallback = onResult
        adapter?.bondedDevices?.forEach { candidates[it.address] = it }
        queried.clear()
        queryNext()
    }
    fun scan() {
        val currentAdapter = adapter ?: return
        if (!permitted() || closed || !currentAdapter.isEnabled) return
        if (currentAdapter.isDiscovering || queryAddress != null || queued.isNotEmpty()) return
        candidates.clear()
        currentAdapter.bondedDevices.forEach { candidates[it.address] = it }
        queried.clear()
        if (!runCatching { currentAdapter.startDiscovery() }.getOrDefault(false)) queryNext()
    }
    fun listen() {
        val currentAdapter = adapter ?: return
        if (closed || !permitted() || !currentAdapter.isEnabled || !listening.compareAndSet(false, true)) return
        val epoch = generation.get()
        workers.execute {
            var local: BluetoothServerSocket? = null
            try {
                local = currentAdapter.listenUsingRfcommWithServiceRecord("SUTRA Offline Speech", SERVICE_UUID)
                server = local
                if (closed || epoch != generation.get()) return@execute
                while (!closed && epoch == generation.get()) {
                    val peer = local.accept()
                    workers.execute { attach(peer, epoch) }
                }
            } catch (error: Exception) {
                if (!closed && epoch == generation.get()) listener.onTransportStatus("Bluetooth listener: ${error.message}")
            } finally {
                runCatching { local?.close() }
                if (server === local) server = null
                listening.set(false)
            }
        }
    }
    fun connect(address: String) {
        require(BluetoothAdapter.checkBluetoothAddress(address)) { "Select a discovered Bluetooth phone" }
        if (closed || !permitted() || links.contains(address) || links.size >= 16 || !queued.add(address)) return
        listen()
        val epoch = generation.get()
        connector.execute {
            var socket: BluetoothSocket? = null
            try {
                if (epoch != generation.get() || closed || links.contains(address)) return@execute
                adapter?.cancelDiscovery()
                socket = adapter?.getRemoteDevice(address)?.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    ?: return@execute
                connecting[address] = socket
                val pending = socket
                val timeout = Runnable { if (connecting[address] === pending) runCatching { pending.close() } }
                handler.postDelayed(timeout, 15_000)
                try { socket.connect() } finally { handler.removeCallbacks(timeout) }
                connecting.remove(address, socket)
                val connectedSocket = socket
                workers.execute { attach(connectedSocket, epoch) }
                socket = null
            } catch (error: Exception) {
                if (!closed && epoch == generation.get()) listener.onTransportStatus("Bluetooth retry pending: ${error.message}")
            } finally {
                socket?.let { connecting.remove(address, it); runCatching { it.close() } }
                queued.remove(address)
                if (!closed) handler.post { queryNext() }
            }
        }
    }
    private fun attach(socket: BluetoothSocket, epoch: Int) {
        var link: FramedPeerLinks.Link? = null
        val address = socket.remoteDevice.address
        try {
            synchronized(links) {
                if (closed || epoch != generation.get()) { socket.close(); return }
                link = links.add(address, socket, socket.inputStream, socket.outputStream)
            }
            val active = link ?: return
            peers.putIfAbsent(address, BluetoothPeer(runCatching { socket.remoteDevice.name }.getOrNull() ?: "SUTRA phone", address))
            publish()
            listener.onTransportStatus("Bluetooth connected: ${links.size} peer(s)")
            while (!closed) listener.onTransportMessage(links.receive(active))
        } catch (_: Exception) {
            // The automatic cycle retries missing peers; surviving links stay open.
        } finally {
            link?.let { links.remove(address, it) }
            runCatching { socket.close() }
            publish()
            listener.onTransportStatus("Bluetooth peers remaining: ${links.size}")
        }
    }
    fun send(bytes: ByteArray) = links.send(bytes)
    private fun snapshot() = peers.values.map { it.copy(connected = links.contains(it.address)) }
        .sortedWith(compareByDescending<BluetoothPeer> { it.connected }.thenBy { it.name })
    /** Only devices confirmed (by SDP UUID) or already linked are actually running SUTRA; drop everything else. */
    private fun sutraSnapshot() = snapshot().filter { it.address in confirmed || it.connected }
    private fun publish() = peerListener(sutraSnapshot(), scanning)
    fun disconnect() {
        stopAutomatic()
        generation.incrementAndGet()
        runCatching { adapter?.cancelDiscovery() }
        runCatching { server?.close() }
        connecting.values.forEach { runCatching { it.close() } }
        synchronized(links) { links.close() }
        publish()
    }
    override fun close() {
        closed = true
        disconnect()
        handler.removeCallbacksAndMessages(null)
        runCatching { app.unregisterReceiver(receiver) }
        connector.shutdownNow()
        workers.shutdownNow()
    }
}
