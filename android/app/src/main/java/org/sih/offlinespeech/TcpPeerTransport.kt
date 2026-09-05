package org.sih.offlinespeech

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

interface PeerTransportListener {
    fun onTransportStatus(status: String)
    fun onTransportMessage(bytes: ByteArray)
}

class TcpPeerTransport(private val listener: PeerTransportListener, private val port: Int = PORT) : Closeable {
    companion object {
        const val PORT = 38_473
        private const val MAX_FRAME_BYTES = SutraCodec.MAX_PAYLOAD_BYTES + 1024

        fun localIpv4Addresses(): List<String> {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            return Collections.list(interfaces)
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }
    }

    private val workers = Executors.newCachedThreadPool()
    private val peers = ConcurrentHashMap<String, Socket>()
    private val writeLocks = ConcurrentHashMap<String, Any>()
    @Volatile private var server: ServerSocket? = null

    val isConnected: Boolean get() = peers.values.any { it.isConnected && !it.isClosed }
    val peerCount: Int get() = peers.size

    fun host() {
        disconnect()
        workers.execute {
            try {
                val listening = ServerSocket(port).also { server = it }
                listener.onTransportStatus("Wi-Fi host ready; waiting for SUTRA receivers")
                while (!listening.isClosed) {
                    val peer = listening.accept()
                    workers.execute { attach(peer) }
                }
            } catch (error: Exception) {
                if (server?.isClosed == false) listener.onTransportStatus("Wi-Fi host failed: ${error.message}")
            } finally {
                runCatching { server?.close() }
                server = null
            }
        }
    }

    fun connect(host: String) {
        require(host.isNotBlank()) { "No group-owner address available" }
        disconnect()
        workers.execute {
            val target = host.trim()
            var failure: Exception? = null
            repeat(8) { attempt ->
                try {
                    listener.onTransportStatus("Opening Wi-Fi link")
                    val peer = Socket(target, port)
                    attach(peer)
                    return@execute
                } catch (error: Exception) {
                    failure = error
                    if (attempt < 7) Thread.sleep(350)
                }
            }
            listener.onTransportStatus("Wi-Fi connection failed: ${failure?.message}")
        }
    }

    fun send(bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid transport frame size" }
        val active = peers.entries.toList()
        check(active.isNotEmpty()) { "No Wi-Fi peer is connected" }
        var delivered = 0
        active.forEach { (id, peer) ->
            try {
                synchronized(writeLocks.getOrPut(id) { Any() }) {
                    DataOutputStream(peer.getOutputStream()).apply {
                        writeInt(bytes.size)
                        write(bytes)
                        flush()
                    }
                }
                delivered++
            } catch (_: Exception) {
                remove(id, peer)
            }
        }
        check(delivered > 0) { "All Wi-Fi peers disconnected" }
    }

    fun disconnect() {
        runCatching { server?.close() }
        server = null
        peers.entries.toList().forEach { (id, peer) -> remove(id, peer) }
    }

    private fun attach(peer: Socket) {
        peer.tcpNoDelay = true
        peer.keepAlive = true
        val id = "${peer.inetAddress.hostAddress}:${peer.port}"
        peers[id] = peer
        listener.onTransportStatus("Wi-Fi connected: ${peers.size} receiver(s)")
        try {
            val input = DataInputStream(peer.getInputStream())
            while (!peer.isClosed) {
                val size = input.readInt()
                require(size in 1..MAX_FRAME_BYTES) { "Peer frame is too large: $size" }
                listener.onTransportMessage(ByteArray(size).also(input::readFully))
            }
        } catch (error: Exception) {
            if (!peer.isClosed) listener.onTransportStatus("Wi-Fi peer disconnected: ${error.message}")
        } finally {
            remove(id, peer)
            listener.onTransportStatus("Wi-Fi receivers remaining: ${peers.size}")
        }
    }

    private fun remove(id: String, peer: Socket) {
        peers.remove(id, peer)
        writeLocks.remove(id)
        runCatching { peer.close() }
    }

    override fun close() {
        disconnect()
        workers.shutdownNow()
    }
}
