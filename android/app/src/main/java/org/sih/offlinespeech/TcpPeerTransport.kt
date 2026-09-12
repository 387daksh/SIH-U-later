package org.sih.offlinespeech

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
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
            val list = Collections.list(interfaces).filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            val preferredOrder = listOf("wlan", "p2p", "eth", "en")
            return list
                .sortedBy { iface ->
                    val name = iface.name.lowercase()
                    preferredOrder.indexOfFirst { name.startsWith(it) }.let { if (it == -1) 99 else it }
                }
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
    private val outgoing = ConcurrentHashMap<String, Socket>()
    private val pending = ConcurrentHashMap<String, Socket>()
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var closed = false

    val isConnected: Boolean get() = peers.values.any { it.isConnected && !it.isClosed }
    val peerCount: Int get() = peers.size

    @Synchronized
    fun host() {
        if (closed || server?.isClosed == false) return
        val listening = try { ServerSocket(port).also { server = it } }
        catch (error: Exception) {
            listener.onTransportStatus("Wi-Fi host failed: ${error.message}")
            return
        }
        val epoch = generation.get()
        workers.execute {
            try {
                listener.onTransportStatus("Wi-Fi host ready; waiting for SUTRA receivers")
                while (!listening.isClosed) {
                    val peer = listening.accept()
                    workers.execute { attach(peer, epoch) }
                }
            } catch (error: Exception) {
                if (server?.isClosed == false) listener.onTransportStatus("Wi-Fi host failed: ${error.message}")
            } finally {
                runCatching { listening.close() }
                if (server === listening) server = null
            }
        }
    }

    fun connect(host: String) {
        require(host.isNotBlank()) { "No group-owner address available" }
        val target = host.trim()
        if (closed || outgoing[target]?.isClosed == false) return
        val first = Socket()
        if (pending.putIfAbsent(target, first) != null) { first.close(); return }
        val epoch = generation.get()
        workers.execute {
            var failure: Exception? = null
            var peer = first
            try { repeat(8) { attempt ->
                if (closed || epoch != generation.get()) return@execute
                try {
                    listener.onTransportStatus("Opening Wi-Fi link")
                    peer.connect(InetSocketAddress(target, port), 3_000)
                    outgoing[target] = peer
                    attach(peer, epoch)
                    return@execute
                } catch (error: Exception) {
                    runCatching { peer.close() }
                    failure = error
                    if (attempt < 7) {
                        Thread.sleep(350)
                        peer = Socket()
                        pending[target] = peer
                    }
                }
            } } finally {
                pending.remove(target, peer)
                outgoing.remove(target, peer)
                runCatching { peer.close() }
            }
            listener.onTransportStatus("Wi-Fi connection failed: ${failure?.message}")
        }
    }

    fun send(bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid transport frame size" }
        val active = peers.entries.toList()
        check(active.isNotEmpty()) { "No Wi-Fi peer is connected" }
        val header = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
        var delivered = 0
        active.forEach { (id, peer) ->
            try {
                synchronized(writeLocks.getOrPut(id) { Any() }) {
                    val output = peer.getOutputStream()
                    output.write(header)
                    output.write(bytes)
                    output.flush()
                }
                delivered++
            } catch (_: Exception) {
                remove(id, peer)
            }
        }
        check(delivered > 0) { "All Wi-Fi peers disconnected" }
    }

    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        runCatching { server?.close() }
        server = null
        pending.values.forEach { runCatching { it.close() } }
        peers.entries.toList().forEach { (id, peer) -> remove(id, peer) }
    }

    private fun attach(peer: Socket, epoch: Int) {
        peer.tcpNoDelay = true
        peer.keepAlive = true
        val id = "${peer.inetAddress.hostAddress}:${peer.port}"
        synchronized(this) {
            if (closed || epoch != generation.get()) { peer.close(); return }
            peers[id] = peer
        }
        listener.onTransportStatus("Wi-Fi connected: ${peers.size} receiver(s)")
        try {
            val input = DataInputStream(peer.getInputStream())
            while (!peer.isClosed) {
                val size = input.readInt()
                // Length-prefixed stream framing: if frame size is corrupted, stream synchronization is lost.
                // The connection must be torn down and renegotiated.
                require(size in 1..MAX_FRAME_BYTES) { "Wi-Fi framing desync (frame size $size exceeds limit $MAX_FRAME_BYTES)" }
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
        closed = true
        disconnect()
        workers.shutdownNow()
    }
}
