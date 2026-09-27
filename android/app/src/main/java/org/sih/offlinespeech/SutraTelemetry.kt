package org.sih.offlinespeech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-device latency arithmetic for the PS-26173 latency criterion.
 *
 * Four numbers are asked for, and three of them are single-device measurements this app already
 * records through [SutraStore.metric]:
 *   1. speech end -> STT complete          (send side, one clock)
 *   2. text received -> audio playing      (receive side, one clock)
 *   3. real-time factor                    (send side, one clock)
 *   4. sentence spoken on A -> same sentence playing on B
 *
 * Metric 4 spans two devices, so it cannot be measured by subtracting two readings: each phone's
 * wall clock is independently wrong. This object holds the offset estimate that makes it possible,
 * plus the payload codec used to carry a peer's clock reading inside an otherwise-empty
 * acknowledgement frame.
 *
 * Deliberately free of Android imports so the arithmetic is exercised by the JVM unit tests
 * (`SutraTelemetryTest`) rather than only on a device.
 */
object SutraTelemetry {

    /** Wire size of an epoch-millisecond reading carried in an acknowledgement payload. */
    const val EPOCH_PAYLOAD_BYTES = 8

    private val offsetMillis = AtomicLong(0)
    private val roundTripMillis = AtomicLong(-1)

    /**
     * Offset to add to one of our epoch readings to express it on the peer's clock, and the
     * round-trip time, both in milliseconds. [roundTripMs] is negative until a peer has replied.
     */
    val clockOffsetMs: Long get() = offsetMillis.get()
    val roundTripMs: Long get() = roundTripMillis.get()
    val hasPeerClock: Boolean get() = roundTripMillis.get() >= 0

    /**
     * NTP-style offset and round-trip from one request/reply pair.
     *
     * @param sentEpochMs our clock when the original frame left (t0).
     * @param peerEpochMs the peer's clock when it built the reply (t1), carried in the payload.
     * @param receivedEpochMs our clock when the reply arrived (t2).
     * @return offset (peer − us) and round-trip, in milliseconds.
     *
     * The naive alternative — `now − peerEpochMs`, halved — is wrong: with unsynchronised clocks
     * that difference mixes the clock offset with the one-way delay and is not a round trip.
     */
    fun computeOffsetAndRtt(sentEpochMs: Long, peerEpochMs: Long, receivedEpochMs: Long): Pair<Long, Long> =
        (peerEpochMs - (sentEpochMs + receivedEpochMs) / 2) to (receivedEpochMs - sentEpochMs)

    /** Record a measured exchange. Ignored if the reply appears to predate the request. */
    fun observeExchange(sentEpochMs: Long, peerEpochMs: Long, receivedEpochMs: Long): Pair<Long, Long>? {
        if (sentEpochMs <= 0 || peerEpochMs <= 0 || receivedEpochMs < sentEpochMs) return null
        val measured = computeOffsetAndRtt(sentEpochMs, peerEpochMs, receivedEpochMs)
        offsetMillis.set(measured.first)
        roundTripMillis.set(measured.second)
        return measured
    }

    /**
     * Metric 4: milliseconds from our speech ending to the peer starting playback.
     *
     * @param speechEndEpochMs our clock when the phrase was cut.
     * @param peerPlaybackEpochMs the peer's clock when its first audio frame was written.
     * Returns null when either reading is missing, or when no clock exchange has happened yet —
     * an unconverted subtraction across two clocks is not a latency and must not be reported.
     */
    fun peerPlaybackLatencyMs(speechEndEpochMs: Long, peerPlaybackEpochMs: Long): Long? {
        if (speechEndEpochMs <= 0 || peerPlaybackEpochMs <= 0 || !hasPeerClock) return null
        return peerPlaybackEpochMs - (speechEndEpochMs + clockOffsetMs)
    }

    /**
     * Converts a [System.nanoTime] reading to a wall-clock epoch estimate by pairing fresh
     * readings of both clocks and projecting backwards. Accurate to the gap between when
     * [monotonicNs] was taken and when this is called.
     *
     * Timings are taken with the monotonic clock (immune to wall-clock jumps) but cross-device
     * joins need an epoch, so one conversion is unavoidable; doing it in one place keeps the error
     * in one place too.
     */
    fun nsToEpochMs(monotonicNs: Long, nowNs: Long = System.nanoTime(), nowEpochMs: Long = System.currentTimeMillis()): Long {
        if (monotonicNs <= 0) return 0
        return nowEpochMs - (nowNs - monotonicNs) / 1_000_000
    }

    /**
     * Real-time factor: processing time over audio duration. Below 1.0 is faster than real time.
     *
     * Model load and queue wait are excluded by the caller passing processing-only time — they are
     * a property of the app's startup, not of the model's speed, and folding them in makes the
     * first utterance of a process look ten times slower than every later one.
     */
    fun realTimeFactor(processingMs: Long, audioMs: Long): Double =
        if (audioMs <= 0 || processingMs < 0) 0.0 else processingMs.toDouble() / audioMs.toDouble()

    /** Big-endian epoch-millisecond payload for an acknowledgement frame. */
    fun encodeEpoch(epochMs: Long): ByteArray =
        ByteBuffer.allocate(EPOCH_PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(epochMs).array()

    /**
     * Reads an epoch reading back, or null when the payload is not one.
     *
     * Older builds send acknowledgements with an empty payload, so a wrong-sized payload is an
     * expected peer-version difference and never an error: the caller simply skips clock sync.
     */
    fun decodeEpoch(payload: ByteArray): Long? {
        if (payload.size != EPOCH_PAYLOAD_BYTES) return null
        val value = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long
        return value.takeIf { it > 0 }
    }

    /** Test seam: forget any measured peer clock. */
    internal fun reset() {
        offsetMillis.set(0)
        roundTripMillis.set(-1)
    }
}
