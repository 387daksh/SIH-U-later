package org.sih.offlinespeech

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SecureFrameCodec private constructor(private val key: SecretKeySpec) {
    companion object {
        private val PREFIX = byteArrayOf(0x53, 0x47, 1)
        private val SALT = "SIH-SUTRA-session-v1".toByteArray(Charsets.UTF_8)

        fun fromPhrase(phrase: String): SecureFrameCodec {
            require(phrase.length >= 12) { "Pairing phrase must contain at least 12 characters" }
            val material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(phrase.toCharArray(), SALT, 120_000, 256)).encoded
            return SecureFrameCodec(SecretKeySpec(material, "AES"))
        }
    }

    private val random = SecureRandom()

    fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(PREFIX)
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(PREFIX.size + nonce.size + encrypted.size)
            .put(PREFIX).put(nonce).put(encrypted).array()
    }

    fun open(frame: ByteArray): ByteArray {
        require(frame.size >= PREFIX.size + 12 + 16) { "Truncated secure frame" }
        require(frame.copyOfRange(0, PREFIX.size).contentEquals(PREFIX)) { "Peer did not use the configured pairing phrase" }
        val nonce = frame.copyOfRange(PREFIX.size, PREFIX.size + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(PREFIX)
        return cipher.doFinal(frame, PREFIX.size + 12, frame.size - PREFIX.size - 12)
    }
}
