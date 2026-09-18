package org.sih.offlinespeech

import kotlin.math.pow

/** RSSI distance is an estimate: walls, pockets and antenna orientation can change it considerably. */
object BluetoothDistanceEstimator {
    const val DEFAULT_TX_POWER_AT_ONE_METRE = -59
    const val DEFAULT_PATH_LOSS_EXPONENT = 2.2

    fun metres(
        rssi: Int,
        txPowerAtOneMetre: Int = DEFAULT_TX_POWER_AT_ONE_METRE,
        pathLossExponent: Double = DEFAULT_PATH_LOSS_EXPONENT,
    ): Double {
        require(rssi < 0) { "RSSI must be a negative dBm value" }
        require(pathLossExponent > 0.0) { "Path-loss exponent must be positive" }
        return 10.0.pow((txPowerAtOneMetre - rssi) / (10.0 * pathLossExponent))
            .coerceIn(0.1, 99.0)
    }

    /**
     * A 1D Kalman filter over a single peer's RSSI stream. Tracks its own uncertainty instead of
     * blending with a fixed weight, so it settles faster on a stable signal and reacts less to
     * single-sample multipath/body-attenuation spikes than a plain exponential moving average.
     */
    class RssiFilter(
        private val processVariance: Double = 0.008,
        private val measurementVariance: Double = 4.0,
    ) {
        private var estimate: Double? = null
        private var errorCovariance = 1.0

        fun update(measurement: Int): Int {
            val prior = estimate
            if (prior == null) {
                estimate = measurement.toDouble()
                return measurement
            }
            val predictedCovariance = errorCovariance + processVariance
            val kalmanGain = predictedCovariance / (predictedCovariance + measurementVariance)
            val updated = prior + kalmanGain * (measurement - prior)
            errorCovariance = (1 - kalmanGain) * predictedCovariance
            estimate = updated
            return updated.toInt()
        }
    }
}
