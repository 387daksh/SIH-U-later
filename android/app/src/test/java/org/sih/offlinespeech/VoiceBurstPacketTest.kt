package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBurstPacketTest {
    private val burstId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun canonicalBitchatPrefixAndTextFieldsRoundTrip() {
        val original = VoiceBurstPacket(
            burstId, 0x1234, VoiceBurstPacket.FLAG_DATA, 0x89ab_cdefL,
            SutraLanguage.HINDI, "नमस्ते",
        )
        val encoded = VoiceBurstPacketCodec.encode(original)

        // [burstID:8][seq:UInt16 BE][flags][Sn:UInt32 BE][language]
        assertArrayEquals(byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8, 0x12, 0x34, 0x00,
            0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x01,
        ), encoded.copyOf(VoiceBurstPacket.HEADER_BYTES))
        assertEquals(original, VoiceBurstPacketCodec.decode(encoded))
    }

    @Test
    fun allControlFlagsRoundTripWithoutText() {
        listOf(
            VoiceBurstPacket.FLAG_START,
            VoiceBurstPacket.FLAG_END,
            VoiceBurstPacket.FLAG_CANCELED,
        ).forEachIndexed { index, flag ->
            val decoded = VoiceBurstPacketCodec.decode(VoiceBurstPacketCodec.encode(
                VoiceBurstPacket(burstId, index, flag, 500L, SutraLanguage.UNDETERMINED)
            ))
            assertEquals(flag, decoded.flags)
            assertTrue(decoded.text.isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedEnvelope() {
        VoiceBurstPacketCodec.decode(ByteArray(VoiceBurstPacket.HEADER_BYTES - 1))
    }
}
