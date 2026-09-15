package org.sih.offlinespeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDistanceEstimatorTest {
    @Test fun calibratedRssiIsApproximatelyOneMetre() {
        assertEquals(1.0, BluetoothDistanceEstimator.metres(-59), 0.001)
    }

    @Test fun weakerSignalProducesGreaterDistance() {
        assertTrue(BluetoothDistanceEstimator.metres(-80) > BluetoothDistanceEstimator.metres(-65))
    }

    @Test fun filterReducesOneSampleJumps() {
        val filter = BluetoothDistanceEstimator.RssiFilter()
        filter.update(-60)
        val afterJump = filter.update(-70)
        assertTrue("expected $afterJump to land between the two raw samples", afterJump in -70..-60)
    }

    @Test fun filterConvergesOnAStableSignal() {
        val filter = BluetoothDistanceEstimator.RssiFilter()
        repeat(20) { filter.update(-65) }
        assertEquals(-65, filter.update(-65))
    }
}
