package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TcpPeerTransportTest {
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
            host.send(expected)
            assertTrue(received.await(3, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
            host.close()
        }
    }
}
