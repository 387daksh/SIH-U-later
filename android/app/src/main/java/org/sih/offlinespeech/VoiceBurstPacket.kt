package org.sih.offlinespeech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Text adaptation of Bitchat's live [VoiceBurstPacket] envelope.
 *
 * The first 11 bytes deliberately retain Bitchat's burst id, big-endian sequence
 * number, and flags.  Sender timestamp and language are appended before the UTF-8
 * incremental transcript so an ARC receiver can schedule it without a clock sync.
 */
data class VoiceBurstPacket(
    val burstId: ByteArray,
    val sequence: Int,
    val flags: Int,
    val senderTimestampMs: Long,
    val language: SutraLanguage,
    val text: String = "",
) {
    init {
        require(burstId.size == BURST_ID_BYTES) { "burstId must be 8 bytes" }
        require(sequence in 0..0xffff) { "sequence exceeds UInt16" }
        require(flags in setOf(FLAG_DATA, FLAG_START, FLAG_END, FLAG_CANCELED)) { "Unknown burst flags" }
        require(senderTimestampMs in 0..UINT32_MAX) { "timestamp exceeds UInt32" }
    }

    // ByteArray uses reference equality; packet identity is useful in codec tests and receivers.
    override fun equals(other: Any?): Boolean = other is VoiceBurstPacket &&
        burstId.contentEquals(other.burstId) && sequence == other.sequence && flags == other.flags &&
        senderTimestampMs == other.senderTimestampMs && language == other.language && text == other.text

    override fun hashCode(): Int = arrayOf(burstId.contentHashCode(), sequence, flags, senderTimestampMs, language, text).contentHashCode()

    companion object {
        const val BURST_ID_BYTES = 8
        const val HEADER_BYTES = BURST_ID_BYTES + 2 + 1 + 4 + 1
        const val FLAG_DATA = 0x00
        const val FLAG_START = 0x01
        const val FLAG_END = 0x02
        const val FLAG_CANCELED = 0x04
        private const val UINT32_MAX = 0xffff_ffffL

        fun randomBurstId(): ByteArray = ByteArray(BURST_ID_BYTES).also(SecureRandom()::nextBytes)
    }
}

object VoiceBurstPacketCodec {
    const val MAX_TEXT_BYTES = 60 * 1024

    fun encode(packet: VoiceBurstPacket): ByteArray {
        val text = packet.text.toByteArray(Charsets.UTF_8)
        require(text.size <= MAX_TEXT_BYTES) { "Voice burst text is too large" }
        return ByteBuffer.allocate(VoiceBurstPacket.HEADER_BYTES + text.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(packet.burstId)
            putShort(packet.sequence.toShort())
            put(packet.flags.toByte())
            putInt(packet.senderTimestampMs.toInt())
            put(packet.language.wire.toByte())
            put(text)
        }.array()
    }

    fun decode(bytes: ByteArray): VoiceBurstPacket {
        require(bytes.size >= VoiceBurstPacket.HEADER_BYTES) { "Truncated VoiceBurstPacket" }
        require(bytes.size - VoiceBurstPacket.HEADER_BYTES <= MAX_TEXT_BYTES) { "Voice burst text is too large" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val burstId = ByteArray(VoiceBurstPacket.BURST_ID_BYTES).also(input::get)
        val sequence = input.short.toInt() and 0xffff
        val flags = input.get().toInt() and 0xff
        val timestamp = input.int.toLong() and 0xffff_ffffL
        val language = SutraLanguage.fromWire(input.get().toInt() and 0xff)
        val payload = ByteArray(input.remaining()).also(input::get)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString()
        } catch (error: Exception) {
            throw IllegalArgumentException("Voice burst payload is not UTF-8", error)
        }
        return VoiceBurstPacket(burstId, sequence, flags, timestamp, language, text)
    }
}

/** Serializes a live transcription into an ordered Bitchat-compatible burst. */
class StreamingTextPacketizer(private val burstId: ByteArray = VoiceBurstPacket.randomBurstId()) {
    private var nextSequence = 0
    private var started = false
    private var finished = false

    fun start(timestampMs: Long = 0): VoiceBurstPacket {
        check(!started) { "Burst already started" }
        started = true
        return packet(VoiceBurstPacket.FLAG_START, timestampMs, SutraLanguage.UNDETERMINED)
    }

    fun data(timestampMs: Long, language: SutraLanguage, text: String): VoiceBurstPacket? {
        check(started && !finished) { "Burst is not active" }
        if (text.isBlank()) return null
        return packet(VoiceBurstPacket.FLAG_DATA, timestampMs, language, text)
    }

    fun end(timestampMs: Long, canceled: Boolean = false): VoiceBurstPacket {
        check(started && !finished) { "Burst is not active" }
        finished = true
        return packet(if (canceled) VoiceBurstPacket.FLAG_CANCELED else VoiceBurstPacket.FLAG_END,
            timestampMs, SutraLanguage.UNDETERMINED)
    }

    private fun packet(flags: Int, timestampMs: Long, language: SutraLanguage, text: String = ""): VoiceBurstPacket {
        val packet = VoiceBurstPacket(burstId.copyOf(), nextSequence, flags, timestampMs, language, text)
        nextSequence = (nextSequence + 1) and 0xffff
        return packet
    }
}
