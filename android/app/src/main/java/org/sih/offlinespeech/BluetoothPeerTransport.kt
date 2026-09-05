package org.sih.offlinespeech

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors

data class BluetoothPeer(val name: String, val address: String)

class BluetoothPeerTransport(context: Context, private val listener: PeerTransportListener) : AutoCloseable {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f5a40f45-8a20-4fa9-89d1-bad77c49f5c2")
        private const val MAX_FRAME_BYTES = SutraCodec.MAX_PAYLOAD_BYTES + 1024
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val worker = Executors.newCachedThreadPool()
    private val writeLock = Any()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun bondedPeers(): List<BluetoothPeer> = adapter.bondedDevices
        .map { BluetoothPeer(it.name ?: "Bluetooth phone", it.address) }
        .sortedBy(BluetoothPeer::name)

    @SuppressLint("MissingPermission")
    fun listen() {
        disconnect()
        worker.execute {
            try {
                val listening = adapter.listenUsingRfcommWithServiceRecord("SUTRA Offline Speech", SERVICE_UUID)
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
        disconnect()
        worker.execute {
            try {
                adapter.cancelDiscovery()
                listener.onTransportStatus("Opening Bluetooth fallback")
                val peer = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE_UUID)
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
        synchronized(writeLock) {
            DataOutputStream(active.outputStream).apply {
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
        }
    }

    private fun attach(peer: BluetoothSocket) {
        socket = peer
        listener.onTransportStatus("Bluetooth connected")
        try {
            val input = DataInputStream(peer.inputStream)
            while (peer.isConnected) {
                val size = input.readInt()
                require(size in 1..MAX_FRAME_BYTES) { "Bluetooth frame is too large: $size" }
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
        disconnect()
        worker.shutdownNow()
    }
}
