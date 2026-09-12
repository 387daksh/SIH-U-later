package org.sih.offlinespeech

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Monotonic sample time on this device. Never compare clocks from different phones. */
data class TeamFix(val lat: Double, val lon: Double, val accuracy: Double, val sampledMs: Long) {
    fun ageMs(now: Long) = (now - sampledMs).coerceAtLeast(0)
    fun fresh(now: Long) = ageMs(now) <= 30_000
    companion object {
        fun valid(lat: Double, lon: Double, accuracy: Double) =
            lat.isFinite() && lon.isFinite() && accuracy.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 && accuracy in 0.1..100_000.0
    }
}

data class TeamBeacon(val session: Long, val sequence: Long, val name: String, val hops: Int = 0,
    val fix: TeamFix? = null)
data class TeamPeer(val sourceId: Long, val beacon: TeamBeacon, val receivedMs: Long) {
    fun recent(now: Long) = now - receivedMs <= 35_000
}

/** Versioned HELLO payload; old apps already ignore HELLO. No speech/ACK/outbox traffic. */
object TeamPositionCodec {
    private const val MAGIC = 0x53504f31 // SPO1
    const val MAX_HOPS = 4
    fun encode(beacon: TeamBeacon, now: Long): ByteArray {
        require(beacon.sequence >= 0 && beacon.hops in 0..MAX_HOPS)
        val name = beacon.name.toByteArray(Charsets.UTF_8)
        require(name.size in 1..128)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(beacon.session)
            out.writeLong(beacon.sequence)
            out.writeByte(beacon.hops)
            out.writeByte(name.size)
            out.write(name)
            val fix = beacon.fix?.takeIf { it.ageMs(now) <= 120_000 }
            out.writeByte(if (fix == null) 0 else 1)
            if (fix != null) {
                require(TeamFix.valid(fix.lat, fix.lon, fix.accuracy))
                out.writeDouble(fix.lat); out.writeDouble(fix.lon)
                out.writeFloat(fix.accuracy.toFloat()); out.writeInt(fix.ageMs(now).toInt())
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, now: Long): TeamBeacon {
        require(bytes.size in 24..175) { "Invalid position length" }
        val input = ByteBuffer.wrap(bytes)
        require(input.int == MAGIC) { "Unsupported position payload" }
        val session = input.long
        val sequence = input.long.also { require(it >= 0) }
        val hops = (input.get().toInt() and 255).also { require(it in 0..MAX_HOPS) }
        val length = (input.get().toInt() and 255).also { require(it in 1..128 && input.remaining() >= it + 1) }
        val name = SutraCodec.utf8(ByteArray(length).also(input::get))
        require(name.isNotBlank() && name.none { it.isISOControl() })
        val present = input.get().toInt().also { require(it in 0..1) }
        val fix = if (present == 1) {
            require(input.remaining() == 24)
            val lat = input.double; val lon = input.double; val accuracy = input.float.toDouble()
            val age = input.int
            require(TeamFix.valid(lat, lon, accuracy) && age in 0..120_000)
            TeamFix(lat, lon, accuracy, now - age)
        } else null
        require(!input.hasRemaining())
        return TeamBeacon(session, sequence, name, hops, fix)
    }
}

/** In-memory bounded presence and duplicate suppression, separate from speech delivery receipts. */
class TeamDirectory {
    private val peers = LinkedHashMap<Long, TeamPeer>()
    private val seen = LinkedHashSet<SutraId>()
    @Synchronized fun accept(message: SutraMessage, now: Long): TeamBeacon? {
        if (message.type != SutraMessageType.HELLO || message.destinationId != 0L || message.id in seen) return null
        val beacon = runCatching { TeamPositionCodec.decode(message.payload, now) }.getOrNull() ?: return null
        seen.add(message.id)
        while (seen.size > 2048) seen.remove(seen.first())
        val previous = peers[message.sourceId]?.beacon
        if (previous?.session == beacon.session && beacon.sequence <= previous.sequence) return null
        peers.remove(message.sourceId)
        peers[message.sourceId] = TeamPeer(message.sourceId, beacon, now)
        while (peers.size > 128) peers.remove(peers.keys.first())
        return beacon
    }
    @Synchronized fun snapshot(now: Long): List<TeamPeer> {
        peers.entries.removeAll { now - it.value.receivedMs > 600_000 }
        return peers.values.toList()
    }
}

data class TeamRange(val meters: Double, val uncertainty: Double, val bearing: Double, val fresh: Boolean) {
    val directionResolved get() = meters > uncertainty
    val label: String get() = (if (fresh) "≈ " else "Last fix: ≈ ") + MapDistanceCalculator.formatDistance(meters)
    val detail: String get() = "${MapDistanceCalculator.formatDistance((meters - uncertainty).coerceAtLeast(0.0))}–" +
        "${MapDistanceCalculator.formatDistance(meters + uncertainty)} accuracy envelope · " +
        if (directionResolved) MapDistanceCalculator.compassDirection(bearing) else "direction unresolved"
}

fun teamRange(from: TeamFix?, to: TeamFix?, now: Long): TeamRange? {
    if (from == null || to == null) return null
    return TeamRange(MapDistanceCalculator.haversineDistanceMeters(from.lat, from.lon, to.lat, to.lon),
        from.accuracy + to.accuracy,
        MapDistanceCalculator.calculateBearingDegrees(from.lat, from.lon, to.lat, to.lon),
        from.fresh(now) && to.fresh(now))
}
