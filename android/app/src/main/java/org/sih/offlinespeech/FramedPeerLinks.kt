package org.sih.offlinespeech

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** Independent framed streams: a failed receiver never closes the other links. */
internal class FramedPeerLinks(private val maxFrameBytes: Int) : Closeable {
    internal class Link(val socket: Closeable, input: InputStream, output: OutputStream) {
        val input = DataInputStream(input)
        val output = DataOutputStream(output)
    }
    private val links = ConcurrentHashMap<String, Link>()
    val size: Int get() = links.size
    fun contains(id: String) = links.containsKey(id)

    @Synchronized
    fun add(id: String, socket: Closeable, input: InputStream, output: OutputStream): Link? {
        val link = Link(socket, input, output)
        if (links.containsKey(id) || links.size >= 16) {
            socket.close()
            return null
        }
        links[id] = link
        return link
    }

    fun receive(link: Link): ByteArray {
        val size = link.input.readInt()
        require(size in 1..maxFrameBytes) { "Invalid peer frame size: $size" }
        return ByteArray(size).also(link.input::readFully)
    }

    fun send(bytes: ByteArray) {
        require(bytes.size in 1..maxFrameBytes) { "Invalid peer frame size" }
        var delivered = 0
        links.entries.toList().forEach { (id, link) ->
            try {
                synchronized(link.output) {
                    link.output.writeInt(bytes.size)
                    link.output.write(bytes)
                    link.output.flush()
                }
                delivered++
            } catch (_: Exception) { remove(id, link) }
        }
        check(delivered > 0) { "No peer accepted the frame" }
    }

    fun remove(id: String, link: Link) {
        links.remove(id, link)
        runCatching { link.socket.close() }
    }

    @Synchronized
    override fun close() { links.entries.toList().forEach { (id, link) -> remove(id, link) } }
}
