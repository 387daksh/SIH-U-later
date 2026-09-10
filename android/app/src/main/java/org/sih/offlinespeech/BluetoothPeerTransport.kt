package org.sih.offlinespeech

import android.annotation.SuppressLint
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

data class BluetoothPeer(val name: String, val address: String)

class BluetoothPeerTransport(context: Context, private val listener: PeerTransportListener) : AutoCloseable {
    private val appContext: Context = context.applicationContext
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f5a40f45-8a20-4fa9-89d1-bad77c49f5c2")
        private const val MAX_FRAME_BYTES = SutraCodec.MAX_PAYLOAD_BYTES + 1024
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val worker = Executors.newCachedThreadPool()
    private val writeLock = Any()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null

    // Callback invoked when fetchSutraPeers() completes
    private var pendingPeerCallback: ((List<BluetoothPeer>) -> Unit)? = null
    private val uuidReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_UUID) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            @Suppress("DEPRECATION")
            val uuids = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, android.os.ParcelUuid::class.java)
            } else {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            }
            val hasSutra = uuids?.any { it.toString().equals(SERVICE_UUID.toString(), ignoreCase = true) } == true
            if (hasSutra) {
                pendingResults[device.address] = BluetoothPeer(device.name ?: "SUTRA device", device.address)
            }
            pendingCount--
            if (pendingCount <= 0) {
                runCatching { appContext.unregisterReceiver(this) }
                uuidReceiverRegistered = false
                val peers = pendingResults.values.sortedBy(BluetoothPeer::name)
                pendingPeerCallback?.invoke(peers)
                pendingPeerCallback = null
            }
        }
    }
    private val pendingResults = LinkedHashMap<String, BluetoothPeer>()
    private var pendingCount = 0
    private var uuidReceiverRegistered = false

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Returns ALL bonded (paired) devices — used for the legacy "connect by address" flow.
     * To filter to SUTRA-only use [fetchSutraPeers].
     */
    @SuppressLint("MissingPermission")
    fun bondedPeers(): List<BluetoothPeer> = try {
        adapter?.bondedDevices
            ?.map { BluetoothPeer(it.name ?: "Bluetooth device", it.address) }
            ?.sortedBy(BluetoothPeer::name)
            ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Asynchronously scans bonded devices for the SUTRA SERVICE_UUID and calls [onResult]
     * with only those that have the SUTRA app installed.
     * Falls back to all bonded devices if UUID discovery is unavailable.
     */
    @SuppressLint("MissingPermission")
    fun fetchSutraPeers(onResult: (List<BluetoothPeer>) -> Unit) {
        val bonded = try { adapter?.bondedDevices?.toList() ?: emptyList() }
        catch (_: Exception) { emptyList() }

        if (bonded.isEmpty()) { onResult(emptyList()); return }

        // Register UUID broadcast receiver before triggering fetches
        pendingResults.clear()
        pendingCount = bonded.size
        pendingPeerCallback = onResult

        if (!uuidReceiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(uuidReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(uuidReceiver, filter)
            }
            uuidReceiverRegistered = true
        }

        // fetchUuidsWithSdp() triggers ACTION_UUID broadcast per device
        val allTriggered = bonded.all { device ->
            runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
        }
        if (!allTriggered) {
            // fetchUuidsWithSdp unsupported or failed — fall back to all bonded peers
            runCatching { appContext.unregisterReceiver(uuidReceiver) }
            uuidReceiverRegistered = false
            pendingPeerCallback = null
            onResult(bondedPeers())
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
                val listening = try {
                    currentAdapter.listenUsingInsecureRfcommWithServiceRecord("SUTRA Offline Speech", SERVICE_UUID)
                } catch (_: Exception) {
                    currentAdapter.listenUsingRfcommWithServiceRecord("SUTRA Offline Speech", SERVICE_UUID)
                }.also { server = it }
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
                val device = currentAdapter.getRemoteDevice(address)
                var activeSocket: BluetoothSocket? = null
                var lastErr: Exception? = null

                // Candidate 1: Insecure RFCOMM with SUTRA SERVICE_UUID (fastest, no OS PIN prompt)
                try {
                    val s = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                    s.connect()
                    activeSocket = s
                } catch (e: Exception) {
                    lastErr = e
                }

                // Candidate 2: Secure RFCOMM with SUTRA SERVICE_UUID
                if (activeSocket == null) {
                    try {
                        val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                        s.connect()
                        activeSocket = s
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }

                // Candidate 3: Reflection channel 1 (bypasses SDP completely)
                if (activeSocket == null) {
                    try {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val s = method.invoke(device, 1) as BluetoothSocket
                        s.connect()
                        activeSocket = s
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }

                val peer = activeSocket ?: throw (lastErr ?: IllegalStateException("Could not connect to Bluetooth device"))
                attach(peer)
            } catch (error: Exception) {
                listener.onTransportStatus("Bluetooth connection failed: ${error.message}")
                // Auto-return to listening mode so peer can connect back
                runCatching { listen() }
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
        listener.onTransportStatus("Bluetooth connected")
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
            if (socket === peer) socket = null
        }
    }

    fun disconnect() {
        runCatching { server?.close() }
        server = null
        runCatching { socket?.close() }
        socket = null
    }

    override fun close() {
        pendingPeerCallback = null
        if (uuidReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(uuidReceiver) }
            uuidReceiverRegistered = false
        }
        disconnect()
        worker.shutdownNow()
    }
}
