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

    @Test fun smoothingReducesOneSampleJumps() {
        assertEquals(-63, BluetoothDistanceEstimator.smooth(-60, -70))
    }
}
