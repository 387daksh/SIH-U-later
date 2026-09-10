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

    fun smooth(previous: Int?, measurement: Int): Int =
        previous?.let { (it * 0.65 + measurement * 0.35).toInt() } ?: measurement
}
