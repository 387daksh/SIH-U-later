package org.sih.offlinespeech

import org.junit.Assert.*
import org.junit.Test

class TeamPositionTest {
    private val now = 100_000L
    private val fix = TeamFix(28.6139, 77.2090, 5.0, now - 2_000)
    private fun message(sequence: Long = 1, source: Long = 42, hops: Int = 0, position: TeamFix? = fix) =
        SutraMessage(SutraMessageType.HELLO, SutraLanguage.UNDETERMINED,
            TeamPositionCodec.encode(TeamBeacon(7, sequence, "टीम फोन", hops, position), now), sourceId = source)

    @Test fun wireRoundTripUsesAgeInsteadOfComparingPhoneClocks() {
        val decoded = TeamPositionCodec.decode(SutraCodec.decode(SutraCodec.encode(message())).payload, 9_000_000)
        assertEquals("टीम फोन", decoded.name)
        assertEquals(2_000L, decoded.fix!!.ageMs(9_000_000))
        assertEquals(fix.lat, decoded.fix.lat, 0.0)
        assertNull(TeamPositionCodec.decode(message(position = null).payload, now).fix)
    }

    @Test fun malformedCoordinatesAndTruncationAreRejected() {
        val good = message().payload
        for (size in 0 until good.size) {
            assertTrue(runCatching { TeamPositionCodec.decode(good.copyOf(size), now) }.isFailure)
        }
        assertTrue(runCatching { message(position = fix.copy(lat = Double.NaN)) }.isFailure)
        assertTrue(runCatching { message(position = fix.copy(lon = 181.0)) }.isFailure)
        assertTrue(runCatching { message(position = fix.copy(accuracy = -1.0)) }.isFailure)
        assertTrue(runCatching { message(hops = 5) }.isFailure)
        assertTrue(runCatching { TeamPositionCodec.decode(good + byteArrayOf(1), now) }.isFailure)
    }

    @Test fun threeNodeRelayDeliversOnceAndDoesNotFreshenDuplicateFixes() {
        val b = TeamDirectory(); val c = TeamDirectory()
        val original = message()
        val first = b.accept(original, now)!!
        val forwarded = original.copy(payload = TeamPositionCodec.encode(first.copy(hops = first.hops + 1), now + 500))
        val received = c.accept(forwarded, now + 500)!!
        assertEquals(1, received.hops)
        assertEquals(2_500L, received.fix!!.ageMs(now + 500))
        assertNull(b.accept(forwarded, now + 1_000))
        assertNull(c.accept(forwarded, now + 60_000))
        assertFalse(c.snapshot(now + 60_000).single().beacon.fix!!.fresh(now + 60_000))
        assertFalse(c.snapshot(now + 60_000).single().recent(now + 60_000))
    }

    @Test fun multiplePeersOutOfOrderAndWithdrawal() {
        val directory = TeamDirectory()
        directory.accept(message(sequence = 2), now)
        directory.accept(message(source = 43), now)
        assertEquals(2, directory.snapshot(now).size)
        assertNull(directory.accept(message(sequence = 1), now + 500))
        assertNotNull(directory.accept(message(sequence = 3, position = null), now + 1_000))
        assertNull(directory.snapshot(now + 1_000).first { it.sourceId == 42L }.beacon.fix)
        assertTrue(directory.snapshot(now + 601_001).isEmpty())
    }

    @Test fun overlappingAccuracyDoesNotInventDirectionAndStaleIsExplicit() {
        val overlap = teamRange(fix, fix.copy(lon = fix.lon + 0.00001), now)!!
        assertFalse(overlap.directionResolved)
        assertEquals(10.0, overlap.uncertainty, 0.0)
        assertTrue(overlap.detail.contains("unresolved"))
        val east = teamRange(fix, fix.copy(lon = fix.lon + 0.001), now)!!
        assertTrue(east.meters in 90.0..105.0)
        assertTrue(east.directionResolved)
        assertTrue(east.detail.endsWith("E"))
        assertFalse(teamRange(fix, fix, now + 31_000)!!.fresh)
        assertNull(teamRange(null, fix, now))
    }
}
