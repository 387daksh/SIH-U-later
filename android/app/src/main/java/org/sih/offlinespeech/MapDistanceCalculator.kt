package org.sih.offlinespeech

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class PinDistanceInfo(
    val distanceMeters: Double,
    val initialBearingDegrees: Double,
    val compassDirection: String,
    val formattedDistance: String,
    val formattedBearing: String,
    val fromLabel: String,
    val toLabel: String,
)

object MapDistanceCalculator {
    private const val EARTH_RADIUS_METERS = 6371000.0

    private val COMPASS_POINTS = arrayOf(
        "N", "NNE", "NE", "ENE",
        "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW",
        "W", "WNW", "NW", "NNW"
    )

    /**
     * Calculates great-circle distance between two geographic coordinates using the Haversine formula.
     * Numeric distance in meters; physical accuracy depends on the input fixes.
     */
    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val sinDeltaPhi2 = sin(deltaPhi / 2.0)
        val sinDeltaLambda2 = sin(deltaLambda / 2.0)

        val a = sinDeltaPhi2 * sinDeltaPhi2 +
                cos(phi1) * cos(phi2) * sinDeltaLambda2 * sinDeltaLambda2
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates initial bearing (forward azimuth) from point 1 to point 2 in degrees (0..360).
     */
    fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)

        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    /**
     * Converts a bearing in degrees into a 16-point compass direction (e.g., "N", "NE", "SSW").
     */
    fun compassDirection(bearingDegrees: Double): String {
        val normalized = (bearingDegrees % 360.0 + 360.0) % 360.0
        val index = (((normalized + 11.25) % 360.0) / 22.5).toInt()
        return COMPASS_POINTS[index.coerceIn(0, 15)]
    }

    /**
     * Human-friendly distance formatting.
     * e.g., "14 m", "450 m", "1.25 km", "24.8 km".
     */
    fun formatDistance(meters: Double): String {
        return when {
            meters < 1.0 -> "0 m"
            meters < 1000.0 -> "${meters.roundToInt()} m"
            meters < 10000.0 -> String.format(Locale.US, "%.2f km", meters / 1000.0)
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
    }

    /**
     * Complete distance and bearing analysis between two pins.
     */
    fun measure(
        lat1: Double, lon1: Double, fromLabel: String,
        lat2: Double, lon2: Double, toLabel: String
    ): PinDistanceInfo {
        val dist = haversineDistanceMeters(lat1, lon1, lat2, lon2)
        val bearing = calculateBearingDegrees(lat1, lon1, lat2, lon2)
        val compass = compassDirection(bearing)
        val bearingFormatted = "${bearing.roundToInt()}° $compass"

        return PinDistanceInfo(
            distanceMeters = dist,
            initialBearingDegrees = bearing,
            compassDirection = compass,
            formattedDistance = formatDistance(dist),
            formattedBearing = bearingFormatted,
            fromLabel = fromLabel,
            toLabel = toLabel,
        )
    }
}
