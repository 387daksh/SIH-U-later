package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDistanceCalculatorTest {

    private val calc = MapDistanceCalculator

    // Mumbai (18.9388, 72.8354) -> Pune (18.5196, 73.8554) ~118 km
    @Test
    fun haversineDistanceMumbaiToPune() {
        val dist = calc.haversineDistanceMeters(18.9388, 72.8354, 18.5196, 73.8554)
        assertTrue("Expected ~118 km, got " + (dist / 1000) + " km", dist > 115_000 && dist < 122_000)
    }

    @Test
    fun haversineDistanceSamePoint() {
        val dist = calc.haversineDistanceMeters(28.6139, 77.2090, 28.6139, 77.2090)
        assertEquals(0.0, dist, 0.01)
    }

    @Test
    fun bearingNorthward() {
        val bearing = calc.calculateBearingDegrees(0.0, 0.0, 10.0, 0.0)
        assertEquals(0.0, bearing, 0.5)
    }

    @Test
    fun bearingEastward() {
        val bearing = calc.calculateBearingDegrees(0.0, 0.0, 0.0, 10.0)
        assertEquals(90.0, bearing, 1.0)
    }

    @Test
    fun bearingSouthward() {
        val bearing = calc.calculateBearingDegrees(10.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, bearing, 0.5)
    }

    @Test
    fun compassDirectionNorth() {
        assertEquals("N", calc.compassDirection(0.0))
        assertEquals("N", calc.compassDirection(5.0))
    }

    @Test
    fun compassDirectionEast() { assertEquals("E", calc.compassDirection(90.0)) }

    @Test
    fun compassDirectionNE() { assertEquals("NE", calc.compassDirection(45.0)) }

    @Test
    fun formatMeters() {
        assertEquals("0 m", calc.formatDistance(0.5))
        assertEquals("500 m", calc.formatDistance(500.0))
    }

    @Test
    fun formatKilometers() {
        assertTrue(calc.formatDistance(1500.0).endsWith("km"))
    }

    @Test
    fun measureReturnsConsistentInfo() {
        val info = calc.measure(28.6139, 77.2090, "Delhi", 18.9388, 72.8354, "Mumbai")
        assertTrue(info.distanceMeters > 1_100_000)
        assertTrue(info.compassDirection.isNotEmpty())
        assertTrue(info.formattedDistance.contains("km"))
    }
}
