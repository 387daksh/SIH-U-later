package org.sih.offlinespeech

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors

data class BluetoothPeer(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val distanceMetres: Double? = null,
    val connected: Boolean = false,
)

class BluetoothPeerTransport(
    context: Context,
    private val listener: PeerTransportListener,
    private val peerListener: (List<BluetoothPeer>, Boolean) -> Unit = { _, _ -> },
) : AutoCloseable {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f5a40f45-8a20-4fa9-89d1-bad77c49f5c2")
        private const val MAX_FRAME_BYTES = SutraCodec.MAX_PAYLOAD_BYTES + 1024
    }

    private val appContext = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val worker = Executors.newCachedThreadPool()
    private val writeLock = Any()
    private val peerLock = Any()
    private val peers = LinkedHashMap<String, BluetoothPeer>()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var scanning = false
    @Volatile private var activeAddress: String? = null
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    publishPeers()
                    listener.onTransportStatus("Scanning Bluetooth signal strength")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    publishPeers()
                    listener.onTransportStatus("Bluetooth proximity scan complete")
                }
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (device.bondState != BluetoothDevice.BOND_BONDED) return
                    val measured = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (measured == Short.MIN_VALUE.toInt()) return
                    synchronized(peerLock) {
                        val old = peers[device.address]
                        val smoothed = BluetoothDistanceEstimator.smooth(old?.rssi, measured)
                        peers[device.address] = BluetoothPeer(
                            name = runCatching { device.name }.getOrNull() ?: old?.name ?: "Nearby phone",
                            address = device.address,
                            rssi = smoothed,
                            distanceMetres = BluetoothDistanceEstimator.metres(smoothed),
                            connected = device.address == activeAddress,
                        )
                    }
                    publishPeers()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(discoveryReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun bondedPeers(): List<BluetoothPeer> = try {
        adapter?.bondedDevices?.forEach { device ->
            synchronized(peerLock) {
                val old = peers[device.address]
                peers[device.address] = BluetoothPeer(
                    device.name ?: old?.name ?: "Bluetooth phone", device.address,
                    old?.rssi, old?.distanceMetres, device.address == activeAddress,
                )
            }
        }
        snapshot()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        val currentAdapter = adapter ?: run {
            listener.onTransportStatus("Bluetooth unavailable on this device")
            return
        }
        if (isConnected) {
            listener.onTransportStatus("Disconnect Bluetooth before running a proximity scan")
            return
        }
        bondedPeers()
        runCatching {
            if (currentAdapter.isDiscovering) currentAdapter.cancelDiscovery()
            currentAdapter.startDiscovery()
        }.onSuccess { started ->
            if (!started) listener.onTransportStatus("Bluetooth scan could not start; check that Bluetooth is enabled")
        }.onFailure { error ->
            listener.onTransportStatus("Bluetooth scan failed: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun listen() {
        val currentAdapter = adapter ?: run {
            listener.onTransportStatus("Bluetooth unavailable on this device")
            return
        }
        disconnect()
        worker.execute {
            try {
                val listening = currentAdapter.listenUsingRfcommWithServiceRecord("SUTRA Offline Speech", SERVICE_UUID)
                    .also { server = it }
                listener.onTransportStatus("Bluetooth receive mode ready")
                while (server === listening) attach(listening.accept())
            } catch (error: Exception) {
                if (server != null) listener.onTransportStatus("Bluetooth listen failed: ${error.message}")
            } finally {
                runCatching { server?.close() }
                server = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        require(address.isNotBlank()) { "Select a paired Bluetooth phone" }
        val currentAdapter = adapter ?: run {
            listener.onTransportStatus("Bluetooth unavailable on this device")
            return
        }
        disconnect()
        worker.execute {
            try {
                currentAdapter.cancelDiscovery()
                listener.onTransportStatus("Opening Bluetooth fallback")
                val peer = currentAdapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE_UUID)
                peer.connect()
                attach(peer)
            } catch (error: Exception) {
                listener.onTransportStatus("Bluetooth connection failed: ${error.message}")
            }
        }
    }

    fun send(bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid Bluetooth frame size" }
        val active = socket ?: error("No Bluetooth peer is connected")
        val header = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
        synchronized(writeLock) {
            val output = active.outputStream
            output.write(header)
            output.write(bytes)
            output.flush()
        }
    }

    private fun attach(peer: BluetoothSocket) {
        socket = peer
        activeAddress = peer.remoteDevice.address
        updateConnectedPeer(peer.remoteDevice)
        listener.onTransportStatus("Bluetooth connected to ${runCatching { peer.remoteDevice.name }.getOrNull() ?: "peer"}")
        try {
            val input = DataInputStream(peer.inputStream)
            while (peer.isConnected) {
                val size = input.readInt()
                // Length-prefixed stream framing: if frame size is corrupted, stream synchronization is lost.
                // The connection must be torn down and renegotiated.
                require(size in 1..MAX_FRAME_BYTES) { "Bluetooth framing desync (frame size $size exceeds limit $MAX_FRAME_BYTES)" }
                listener.onTransportMessage(ByteArray(size).also(input::readFully))
            }
        } catch (error: Exception) {
            if (socket === peer) listener.onTransportStatus("Bluetooth disconnected: ${error.message}")
        } finally {
            runCatching { peer.close() }
            if (socket === peer) {
                socket = null
                activeAddress = null
                updateConnectedPeer(null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateConnectedPeer(device: BluetoothDevice?) {
        synchronized(peerLock) {
            peers.replaceAll { address, old -> old.copy(connected = address == device?.address) }
            if (device != null && !peers.containsKey(device.address)) {
                peers[device.address] = BluetoothPeer(
                    runCatching { device.name }.getOrNull() ?: "Bluetooth phone",
                    device.address, connected = true,
                )
            }
        }
        publishPeers()
    }

    private fun snapshot(): List<BluetoothPeer> = synchronized(peerLock) {
        peers.values.sortedWith(compareByDescending<BluetoothPeer> { it.connected }.thenBy { it.name }).toList()
    }

    private fun publishPeers() = peerListener(snapshot(), scanning)

    fun disconnect() {
        adapter?.let { current -> runCatching { if (current.isDiscovering) current.cancelDiscovery() } }
        runCatching { server?.close() }
        server = null
        runCatching { socket?.close() }
        socket = null
        activeAddress = null
        updateConnectedPeer(null)
    }

    override fun close() {
        disconnect()
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(discoveryReceiver) }
        receiverRegistered = false
        worker.shutdownNow()
    }
}
