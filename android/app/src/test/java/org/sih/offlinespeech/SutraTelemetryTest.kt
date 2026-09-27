package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SutraTelemetryTest {

    @Before
    fun clearMeasuredClock() = SutraTelemetry.reset()

    @Test
    fun offsetAndRttRecoverAKnownPeerSkew() {
        // Peer clock runs 5000 ms ahead; one-way delay 20 ms each way.
        val sent = 1_000_000L
        val received = sent + 40
        val peer = sent + 20 + 5_000
        val (offset, rtt) = SutraTelemetry.computeOffsetAndRtt(sent, peer, received)
        assertEquals(5_000L, offset)
        assertEquals(40L, rtt)
    }

    @Test
    fun symmetricDelayWithIdenticalClocksReportsNoOffset() {
        val (offset, rtt) = SutraTelemetry.computeOffsetAndRtt(1_000L, 1_050L, 1_100L)
        assertEquals(0L, offset)
        assertEquals(100L, rtt)
    }

    @Test
    fun peerPlaybackLatencyIsNullUntilClocksAreExchanged() {
        assertNull(SutraTelemetry.peerPlaybackLatencyMs(1_000L, 6_500L))
    }

    @Test
    fun peerPlaybackLatencySubtractsTheMeasuredOffset() {
        val sent = 1_000_000L
        assertNotNull(SutraTelemetry.observeExchange(sent, sent + 20 + 5_000, sent + 40))
        // Speech ended at `sent`; the peer played 300 ms later on its own (5 s fast) clock.
        val latency = SutraTelemetry.peerPlaybackLatencyMs(sent, sent + 5_000 + 300)
        assertEquals(300L, latency)
    }

    @Test
    fun exchangeIsRejectedWhenTheReplyPredatesTheRequest() {
        assertNull(SutraTelemetry.observeExchange(2_000L, 2_500L, 1_999L))
        assertNull(SutraTelemetry.observeExchange(0L, 2_500L, 3_000L))
    }

    @Test
    fun epochPayloadRoundTrips() {
        val now = 1_795_000_000_123L
        val encoded = SutraTelemetry.encodeEpoch(now)
        assertEquals(SutraTelemetry.EPOCH_PAYLOAD_BYTES, encoded.size)
        assertEquals(now, SutraTelemetry.decodeEpoch(encoded))
    }

    @Test
    fun wrongSizedPayloadDecodesToNullSoOlderPeersAreIgnoredNotRejected() {
        assertNull(SutraTelemetry.decodeEpoch(ByteArray(0)))
        assertNull(SutraTelemetry.decodeEpoch(ByteArray(4)))
        assertNull(SutraTelemetry.decodeEpoch(SutraTelemetry.encodeEpoch(0)))
    }

    @Test
    fun realTimeFactorIsProcessingOverAudio() {
        assertEquals(0.25, SutraTelemetry.realTimeFactor(750, 3_000), 1e-9)
        assertEquals(0.0, SutraTelemetry.realTimeFactor(750, 0), 1e-9)
    }

    @Test
    fun monotonicToEpochProjectsBackwards() {
        val nowNs = 10_000_000_000L
        val nowEpoch = 1_795_000_000_000L
        // A reading two seconds before "now" should map two seconds before the epoch sample.
        assertEquals(nowEpoch - 2_000, SutraTelemetry.nsToEpochMs(nowNs - 2_000_000_000L, nowNs, nowEpoch))
        assertEquals(0L, SutraTelemetry.nsToEpochMs(0L, nowNs, nowEpoch))
    }

    @Test
    fun acknowledgementCarriesThePayloadAndStaysWireCompatible() {
        val original = SutraMessage(
            SutraMessageType.TEXT_FINAL, SutraLanguage.ODIA, "ସାହାଯ୍ୟ".toByteArray(), sourceId = 42L,
        )
        val stamped = SutraSemanticEncoder.acknowledgement(
            original, SutraMessageType.RECEIVED_ACK, sourceId = 7L, payload = SutraTelemetry.encodeEpoch(1_795_000_000_123L),
        )
        val decoded = SutraCodec.decode(SutraCodec.encode(stamped))
        assertEquals(1_795_000_000_123L, SutraTelemetry.decodeEpoch(decoded.payload))
        assertEquals(original.id, decoded.id)
        assertEquals(7L, decoded.sourceId)
        assertEquals(42L, decoded.destinationId)

        // The default (older-build) shape still encodes and decodes with an empty payload.
        val plain = SutraSemanticEncoder.acknowledgement(original, SutraMessageType.RECEIVED_ACK, sourceId = 7L)
        val plainDecoded = SutraCodec.decode(SutraCodec.encode(plain))
        assertTrue(plainDecoded.payload.isEmpty())
        assertNull(SutraTelemetry.decodeEpoch(plainDecoded.payload))
    }
}
