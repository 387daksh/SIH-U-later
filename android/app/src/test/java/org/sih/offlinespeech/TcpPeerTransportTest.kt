package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

class TcpPeerTransportTest {
    @Test
    fun clientMessageReachesTwoOtherClientsAndSurvivesOneLeaving() {
        val port = 38_476
        val hostReady = CountDownLatch(1)
        val connected = CountDownLatch(6)
        lateinit var host: TcpPeerTransport
        host = TcpPeerTransport(object : PeerTransportListener {
            override fun onTransportStatus(status: String) {
                if (status.contains("host ready")) hostReady.countDown()
                if (status.startsWith("Wi-Fi connected:")) connected.countDown()
            }
            override fun onTransportMessage(bytes: ByteArray) { host.send(bytes) }
        }, port)
        fun client(inbox: LinkedBlockingQueue<ByteArray>) = TcpPeerTransport(object : PeerTransportListener {
            override fun onTransportStatus(status: String) {
                if (status.startsWith("Wi-Fi connected:")) connected.countDown()
            }
            override fun onTransportMessage(bytes: ByteArray) { inbox.offer(bytes) }
        }, port)
        val firstInbox = LinkedBlockingQueue<ByteArray>()
        val secondInbox = LinkedBlockingQueue<ByteArray>()
        val sender = client(LinkedBlockingQueue())
        val first = client(firstInbox)
        val second = client(secondInbox)
        try {
            host.host()
            assertTrue(hostReady.await(3, TimeUnit.SECONDS))
            listOf(sender, first, second).forEach { it.connect("127.0.0.1") }
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            sender.send("all three".toByteArray())
            assertArrayEquals("all three".toByteArray(), firstInbox.poll(3, TimeUnit.SECONDS))
            assertArrayEquals("all three".toByteArray(), secondInbox.poll(3, TimeUnit.SECONDS))
            second.close()
            host.host()
            first.connect("127.0.0.1")
            sender.send("still connected".toByteArray())
            assertArrayEquals("still connected".toByteArray(), firstInbox.poll(3, TimeUnit.SECONDS))
        } finally {
            sender.close(); first.close(); second.close(); host.close()
        }
    }

    @Test
    fun hostAndClientExchangeOneFramedMessage() {
        val port = 38_474
        val hostReady = CountDownLatch(1)
        val connected = CountDownLatch(2)
        val received = CountDownLatch(1)
        var receivedBytes = ByteArray(0)
        val host = TcpPeerTransport(object : PeerTransportListener {
            override fun onTransportStatus(status: String) {
                if (status.contains("host ready")) hostReady.countDown()
                if (status.contains("connected")) connected.countDown()
            }

            override fun onTransportMessage(bytes: ByteArray) {
                receivedBytes = bytes
                received.countDown()
            }
        }, port)
        val client = TcpPeerTransport(object : PeerTransportListener {
            override fun onTransportStatus(status: String) {
                if (status.contains("connected")) connected.countDown()
            }

            override fun onTransportMessage(bytes: ByteArray) = Unit
        }, port)

        try {
            host.host()
            assertTrue(hostReady.await(3, TimeUnit.SECONDS))
            client.connect("127.0.0.1")
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            val expected = SutraCodec.encode(SutraMessage(
                SutraMessageType.TEXT_FINAL, SutraLanguage.ENGLISH, "help at gate two".toByteArray()
            ))
            client.send(expected)
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertArrayEquals(expected, receivedBytes)
        } finally {
            client.close()
            host.close()
        }
    }

    @Test
    fun hostBroadcastsToTwoReceivers() {
        val port = 38_475
        val hostReady = CountDownLatch(1)
        val connected = CountDownLatch(4)
        val received = CountDownLatch(2)
        val expected = SutraCodec.encode(SutraMessage(
            SutraMessageType.TEXT_FINAL, SutraLanguage.ENGLISH, "broadcast alert".toByteArray()
        ))
        val host = TcpPeerTransport(object : PeerTransportListener {
            override fun onTransportStatus(status: String) {
                if (status.contains("host ready")) hostReady.countDown()
                if (status.contains("connected")) connected.countDown()
            }
            override fun onTransportMessage(bytes: ByteArray) = Unit
        }, port)
        fun receiver() = TcpPeerTransport(object : PeerTransportListener {
            override fun onTransportStatus(status: String) {
                if (status.contains("connected")) connected.countDown()
            }
            override fun onTransportMessage(bytes: ByteArray) {
                assertArrayEquals(expected, bytes)
                received.countDown()
            }
        }, port)
        val first = receiver()
        val second = receiver()
        try {
            host.host()
            assertTrue(hostReady.await(3, TimeUnit.SECONDS))
            first.connect("127.0.0.1")
            second.connect("127.0.0.1")
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            // Repeated group-info callbacks must not close an established group.
            host.host()
            first.connect("127.0.0.1")
            host.send(expected)
            assertTrue(received.await(3, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
            host.close()
        }
    }
}
