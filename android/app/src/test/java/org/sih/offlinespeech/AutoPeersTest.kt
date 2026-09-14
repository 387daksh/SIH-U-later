package org.sih.offlinespeech

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AutoPeersTest {
    @Test fun existingOwnerWinsAndOnlyOneIdlePhoneInitiates() {
        val peers = listOf(AutoPeer("a", "01", "idle", 100), AutoPeer("b", "02", "idle", 100),
            AutoPeer("c", "03", "idle", 100))
        assertEquals("b", chooseAutoPeer("01", peers, 200)?.address)
        assertNull(chooseAutoPeer("02", peers, 200))
        assertNull(chooseAutoPeer("03", peers, 200))
        val withOwner = peers + AutoPeer("owner", "99", "owner", 100)
        peers.forEach { assertEquals("owner", chooseAutoPeer(it.node, withOwner, 200)?.address) }
        assertNull(chooseAutoPeer("01", peers, 50_000))
        assertNull(chooseAutoPeer("01", listOf(AutoPeer("x", "02", "client", 100)), 200))
    }

    @Test fun higherNodeTakesOverWhenTheLowestOneNeverFormsAGroup() {
        // Seen just now, but this phone has been groupless since 100_000.
        val peers = listOf(AutoPeer("a", "01", "idle", 160_000), AutoPeer("c", "03", "idle", 160_000))
        // Node 02 defers to node 01 while the wait is still young.
        assertNull(chooseAutoPeer("02", listOf(AutoPeer("a", "01", "idle", 100_000)), 100_200, 100_000))
        // After a minute with no group, it stops waiting on the stuck low node.
        assertEquals("a", chooseAutoPeer("02", peers, 161_000, 100_000)?.address)
        // An owner still wins over the fallback.
        val withOwner = peers + AutoPeer("owner", "99", "owner", 160_000)
        assertEquals("owner", chooseAutoPeer("02", withOwner, 161_000, 100_000)?.address)
        // A stale peer stays ineligible no matter how long the wait has been.
        assertNull(chooseAutoPeer("02", listOf(AutoPeer("a", "01", "idle", 100_000)), 161_000, 100_000))
    }

    @Test fun broadcastSurvivesOneFailedPeerAndRejectsDuplicateLinks() {
        val links = FramedPeerLinks(1024)
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        val input = ByteArrayInputStream(byteArrayOf())
        val firstLink = links.add("first", Closeable {}, input, first)!!
        links.add("second", Closeable {}, input, second)
        var failedClosed = false
        links.add("broken", Closeable { failedClosed = true }, input, object : OutputStream() {
            override fun write(value: Int) { throw IOException("peer left") }
        })
        var duplicateClosed = false
        assertNull(links.add("first", Closeable { duplicateClosed = true }, input, ByteArrayOutputStream()))
        val payload = "message to all phones".toByteArray()
        links.send(payload)
        for (output in listOf(first, second)) {
            val frame = DataInputStream(ByteArrayInputStream(output.toByteArray()))
            assertEquals(payload.size, frame.readInt())
            assertArrayEquals(payload, ByteArray(payload.size).also(frame::readFully))
        }
        assertTrue(duplicateClosed)
        assertTrue(failedClosed)
        assertEquals(2, links.size)
        links.remove("first", firstLink)
        second.reset()
        links.send(payload)
        assertEquals(1, links.size)
        assertTrue(second.size() > payload.size)
        links.close()
        assertEquals(0, links.size)
    }

    @Test fun invalidIncomingLengthIsRejectedBeforeAllocation() {
        val links = FramedPeerLinks(1024)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(Int.MAX_VALUE)
        val link = links.add("bad", Closeable {}, ByteArrayInputStream(bytes.toByteArray()), ByteArrayOutputStream())!!
        assertTrue(runCatching { links.receive(link) }.exceptionOrNull() is IllegalArgumentException)
        links.close()
    }
}
