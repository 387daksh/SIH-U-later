package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecureFrameCodecTest {
    @Test
    fun roundTripAndRandomNonce() {
        val codec = SecureFrameCodec.fromPhrase("correct horse battery staple")
        val plain = "offline alert सहायता".toByteArray()
        val first = codec.seal(plain)
        val second = codec.seal(plain)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, codec.open(first))
        assertArrayEquals(plain, codec.open(second))
    }

    @Test(expected = Exception::class)
    fun wrongPhraseCannotDecrypt() {
        val frame = SecureFrameCodec.fromPhrase("first secure phrase").seal(byteArrayOf(1, 2, 3))
        SecureFrameCodec.fromPhrase("second secure phrase").open(frame)
    }
}
