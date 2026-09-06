package org.sih.offlinespeech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SecureFrameCodecTest {
    @Test
    fun roundTripAndRandomNonceAndSalt() {
        val codec = SecureFrameCodec.fromPhrase("correct horse battery staple")
        val plain = "offline alert सहायता".toByteArray()
        val first = codec.seal(plain)
        val second = codec.seal(plain)

        // Verifies distinct ciphertexts, salt, and nonce per seal
        assertFalse(first.contentEquals(second))
        assertEquals(0x53.toByte(), first[0])
        assertEquals(0x47.toByte(), first[1])
        assertEquals(2.toByte(), first[2]) // Protocol V2 prefix

        // Salts differ
        val salt1 = first.copyOfRange(3, 19)
        val salt2 = second.copyOfRange(3, 19)
        assertFalse(salt1.contentEquals(salt2))

        assertArrayEquals(plain, codec.open(first))
        assertArrayEquals(plain, codec.open(second))
    }

    @Test(expected = Exception::class)
    fun wrongPhraseCannotDecrypt() {
        val frame = SecureFrameCodec.fromPhrase("first secure phrase").seal(byteArrayOf(1, 2, 3))
        SecureFrameCodec.fromPhrase("second secure phrase").open(frame)
    }

    @Test(expected = Exception::class)
    fun tamperedFrameIsRejected() {
        val codec = SecureFrameCodec.fromPhrase("robust emergency encryption")
        val frame = codec.seal(byteArrayOf(10, 20, 30, 40))
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
        codec.open(frame)
    }

    @Test
    fun opensLegacyV1Frame() {
        val phrase = "legacy fallback test phrase"
        val v1Salt = "SIH-SUTRA-session-v1".toByteArray(Charsets.UTF_8)
        val material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(phrase.toCharArray(), v1Salt, 120_000, 256)).encoded
        val key = SecretKeySpec(material, "AES")
        val prefix = byteArrayOf(0x53, 0x47, 1)
        val nonce = ByteArray(12) { it.toByte() }
        val plain = "hello legacy v1".toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(prefix)
        val encrypted = cipher.doFinal(plain)
        val v1Frame = ByteBuffer.allocate(prefix.size + nonce.size + encrypted.size)
            .put(prefix).put(nonce).put(encrypted).array()

        val codec = SecureFrameCodec.fromPhrase(phrase)
        val opened = codec.open(v1Frame)
        assertArrayEquals(plain, opened)
    }
}
