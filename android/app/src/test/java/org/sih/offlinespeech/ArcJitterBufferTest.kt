package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArcJitterBufferTest {
    private val burstId = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)

    private fun packet(sequence: Int, timestampMs: Long) = VoiceBurstPacket(
        burstId, sequence, VoiceBurstPacket.FLAG_DATA, timestampMs, SutraLanguage.ENGLISH, "chunk $sequence"
    )

    @Test
    fun upperEnvelopeMovesFastForLatePacketsAndSlowlyForEarlyPackets() {
        val buffer = ArcJitterBuffer({}, nowMs = { 0L }, executor = null)
        buffer.offer(packet(0, 0), 100)
        assertEquals(100.0, buffer.currentAdaptiveOffsetMs()!!, 0.0001)

        buffer.offer(packet(1, 500), 1_100) // 500 ms later than the current projected arrival
        val afterLate = requireNotNull(buffer.currentAdaptiveOffsetMs())
        assertTrue(afterLate > 200.0) // concave upper arm advances quickly

        buffer.offer(packet(2, 1_000), 1_100) // early relative to the new envelope
        val afterEarly = requireNotNull(buffer.currentAdaptiveOffsetMs())
        assertTrue(afterEarly < afterLate)
        assertTrue(afterLate - afterEarly < 10.0) // convex lower arm decays slowly
        buffer.close()
    }

    @Test
    fun quantizedOffsetHoldsUntilMidpointHysteresisBoundary() {
        val buffer = ArcJitterBuffer({}, nowMs = { 0L }, executor = null)
        buffer.offer(packet(0, 0), 100)
        assertEquals(350.0, buffer.currentQuantizedOffsetMs()!!, 0.0001)
        buffer.offer(packet(1, 500), 3_000)
        assertEquals(350.0, buffer.currentQuantizedOffsetMs()!!, 0.0001)
        buffer.offer(packet(2, 1_000), 4_000)
        val reanchored = requireNotNull(buffer.currentQuantizedOffsetMs())
        assertTrue(reanchored > 500.0)
        assertTrue(abs(reanchored - requireNotNull(buffer.currentAdaptiveOffsetMs()) - 250.0) < 0.0001)
        buffer.close()
    }

    @Test
    fun missingPredecessorGetsAtMostTheBoundedGuard() {
        val buffer = ArcJitterBuffer({}, nowMs = { 0L }, executor = null)
        val later = requireNotNull(buffer.offer(packet(1, 500), 100))
        val predecessor = requireNotNull(buffer.offer(packet(0, 0), 100))
        assertEquals(500L, later.releaseTimeMs) // base T1=350 plus the 150 ms guard
        assertTrue(later.releaseTimeMs >= predecessor.releaseTimeMs + ArcJitterBuffer.GUARD_MS)
        assertEquals(listOf(0, 1), buffer.drainDue(later.releaseTimeMs).map { it.sequence })
        assertNull(buffer.offer(packet(1, 500), 1_000)) // released packets cannot be replayed
        buffer.close()
    }
}
