package org.sih.offlinespeech

import java.io.Closeable
import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class ArcScheduledChunk(
    val packet: VoiceBurstPacket,
    val arrivalTimeMs: Long,
    val releaseTimeMs: Long,
)

/**
 * ARC-QG (Algorithms 1-3) for recovered text packets. All arithmetic uses the
 * receiver's monotonic clock; sender timestamps only provide spacing.
 */
class ArcJitterBuffer(
    private val onRelease: (VoiceBurstPacket) -> Unit,
    private val nowMs: () -> Long,
    private val jitterMarginMs: Long = 0,
    // δ bounds stale offset after a route change; one U window preserves the
    // paper's deliberately slow lower-envelope decay under ordinary jitter.
    private val offsetClampSlackMs: Long = U_MS.toLong(),
    private val executor: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor(),
) : Closeable {
    companion object {
        const val U_MS = 1_000.0
        const val RHO_UPPER = 0.5
        const val RHO_LOWER = 1.5
        const val LAMBDA_UPPER = 0.2
        const val LAMBDA_LOWER = 0.04
        const val QUANTIZATION_MS = 500.0
        const val GUARD_MS = 150L
    }

    private data class BurstKey(val id: String, val sequence: Int)
    private data class BurstState(var lastReleased: Int = -1)
    private val pending = PriorityQueue<ArcScheduledChunk>(compareBy<ArcScheduledChunk> { it.releaseTimeMs }
        .thenBy { it.packet.sequence })
    private val pendingByKey = HashMap<BurstKey, ArcScheduledChunk>()
    private val burstStates = HashMap<String, BurstState>()
    private var adaptiveOffset: Double? = null // D, Algorithm 1
    private var quantizedOffset: Double? = null // E, Algorithm 2
    private var scheduledWakeup: ScheduledFuture<*>? = null
    private var closed = false

    /** Enqueues a recovered packet and returns its scheduled time for observability/tests. */
    @Synchronized
    fun offer(packet: VoiceBurstPacket, arrivalTimeMs: Long = nowMs()): ArcScheduledChunk? {
        if (closed || packet.flags == VoiceBurstPacket.FLAG_CANCELED) {
            if (packet.flags == VoiceBurstPacket.FLAG_CANCELED) cancelBurstLocked(idOf(packet.burstId))
            return null
        }
        val senderTime = packet.senderTimestampMs.toDouble()
        val arrival = arrivalTimeMs.toDouble()
        val priorOffset = adaptiveOffset
        var d = priorOffset ?: (arrival - senderTime)
        if (priorOffset == null) {
            adaptiveOffset = d
            quantizedOffset = d + QUANTIZATION_MS / 2.0
        } else {
            // Algorithm 1: concave quick upward tracking and convex slow decay.
            val x = (arrival - senderTime) - d
            if (x >= jitterMarginMs) {
                val y = (min(x - jitterMarginMs, U_MS) / U_MS).pow(RHO_UPPER) * U_MS
                d += LAMBDA_UPPER * y
            } else if (x <= -jitterMarginMs) {
                val y = -(min(-(x + jitterMarginMs), U_MS) / U_MS).pow(RHO_LOWER) * U_MS
                d += LAMBDA_LOWER * y
            }
            d = min(d, arrival - senderTime + offsetClampSlackMs)
            adaptiveOffset = d
        }

        // Algorithm 2: midpoint hysteresis; E always stays within one step above D.
        var e = requireNotNull(quantizedOffset)
        if (d > e || d < e - QUANTIZATION_MS) e = d + QUANTIZATION_MS / 2.0
        quantizedOffset = e
        val baseRelease = max(arrivalTimeMs, (senderTime + e + jitterMarginMs).toLong())
        val id = idOf(packet.burstId)
        val key = BurstKey(id, packet.sequence)
        val state = burstStates.getOrPut(id) { BurstState() }
        if (packet.sequence <= state.lastReleased || pendingByKey.containsKey(key)) return null

        // Algorithm 3: a predecessor gets at most G extra milliseconds to arrive/release.
        val predecessor = pendingByKey[BurstKey(id, packet.sequence - 1)]
        val release = when {
            packet.sequence == 0 || state.lastReleased >= packet.sequence - 1 -> baseRelease
            // It may still be queued, but its already committed release is no
            // later than Tn, satisfying Algorithm 3's "released by Tn" test.
            predecessor != null && predecessor.releaseTimeMs <= baseRelease -> baseRelease
            predecessor != null && predecessor.releaseTimeMs in baseRelease until (baseRelease + GUARD_MS) ->
                max(baseRelease, predecessor.releaseTimeMs + 1)
            else -> baseRelease + GUARD_MS
        }
        val scheduled = ArcScheduledChunk(packet, arrivalTimeMs, release)
        pending += scheduled
        pendingByKey[key] = scheduled
        scheduleWakeupLocked()
        return scheduled
    }

    /** Releases all chunks due at [timeMs]. This makes scheduler math deterministic in unit tests. */
    @Synchronized
    fun drainDue(timeMs: Long = nowMs()): List<VoiceBurstPacket> {
        val released = ArrayList<VoiceBurstPacket>()
        while (pending.peek()?.releaseTimeMs?.let { it <= timeMs } == true) {
            val item = pending.remove()
            val id = idOf(item.packet.burstId)
            pendingByKey.remove(BurstKey(id, item.packet.sequence))
            val state = burstStates.getOrPut(id) { BurstState() }
            if (item.packet.sequence > state.lastReleased) {
                state.lastReleased = item.packet.sequence
                released += item.packet
            }
        }
        scheduleWakeupLocked()
        return released
    }

    @Synchronized fun currentAdaptiveOffsetMs(): Double? = adaptiveOffset
    @Synchronized fun currentQuantizedOffsetMs(): Double? = quantizedOffset

    private fun scheduleWakeupLocked() {
        scheduledWakeup?.cancel(false)
        val executor = executor ?: return
        val next = pending.peek() ?: return
        val delay = max(0, next.releaseTimeMs - nowMs())
        scheduledWakeup = executor.schedule({
            val due = drainDue()
            due.forEach(onRelease)
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelBurstLocked(id: String) {
        val retained = pending.filterNot { idOf(it.packet.burstId) == id }
        pending.clear(); pending.addAll(retained)
        pendingByKey.keys.removeAll { it.id == id }
        burstStates.remove(id)
        scheduleWakeupLocked()
    }

    private fun idOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun close() {
        synchronized(this) {
            closed = true
            scheduledWakeup?.cancel(false)
            pending.clear(); pendingByKey.clear(); burstStates.clear()
        }
        executor?.shutdownNow()
    }
}
