package org.sih.offlinespeech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compact 18-byte control packet exchanged immediately after every new BLE GATT link is
 * established. Lets each phone learn the GPS position of its peer without any pairing or
 * central server.
 *
 * Wire layout (big-endian):
 *   0-1   magic  0x4D 0x4C  ("ML" -- Mesh Location)
 *   2-9   nodeId (Long)
 *  10-13  lat x 1e5 (Int, signed, range +-18_000_000 -> +-180.0 degrees)
 *  14-17  lng x 1e5 (Int, signed)
 */
data class MeshLocationPacket(
    val nodeId: Long,
    val lat: Double,   // WGS-84 decimal degrees; 0.0 = unavailable
    val lng: Double,
)

object MeshLocationCodec {
    private const val MAGIC_0: Byte = 0x4D
    private const val MAGIC_1: Byte = 0x4C
    const val WIRE_BYTES = 18

    /**
     * 2-byte sentinel passed through listener.onTransportMessage() when a new BLE peer
     * connects so that the service can respond by broadcasting the device's GPS location.
     * It is not a valid MeshLocation (wrong magic1) or SUTRA frame.
     */
    val SENTINEL: ByteArray = byteArrayOf(0x4D, 0x00)

    fun isSentinel(bytes: ByteArray): Boolean =
        bytes.size == 2 && bytes[0] == 0x4D.toByte() && bytes[1] == 0x00.toByte()

    fun isMeshLocation(bytes: ByteArray): Boolean =
        bytes.size >= WIRE_BYTES && bytes[0] == MAGIC_0 && bytes[1] == MAGIC_1

    fun encode(packet: MeshLocationPacket): ByteArray =
        ByteBuffer.allocate(WIRE_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_0); put(MAGIC_1)
            putLong(packet.nodeId)
            putInt((packet.lat * 1e5).toInt())
            putInt((packet.lng * 1e5).toInt())
        }.array()

    fun decode(bytes: ByteArray): MeshLocationPacket {
        require(isMeshLocation(bytes)) { "Not a MeshLocationPacket" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.get(); buf.get()
        val nodeId = buf.long
        val lat    = buf.int / 1e5
        val lng    = buf.int / 1e5
        return MeshLocationPacket(nodeId, lat, lng)
    }

    /** Haversine distance in metres. Returns null if either position is (0,0) = unavailable. */
    fun distanceMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double? {
        if ((lat1 == 0.0 && lng1 == 0.0) || (lat2 == 0.0 && lng2 == 0.0)) return null
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
