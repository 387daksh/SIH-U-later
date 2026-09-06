package org.sih.offlinespeech

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SecureFrameCodec private constructor(
    private val masterKey: ByteArray,
    private val legacyKey: SecretKeySpec?,
) {
    companion object {
        private val PREFIX_V1 = byteArrayOf(0x53, 0x47, 1)
        private val PREFIX_V2 = byteArrayOf(0x53, 0x47, 2)
        private val V1_SALT = "SIH-SUTRA-session-v1".toByteArray(Charsets.UTF_8)
        private val V2_MASTER_SALT = "SIH-SUTRA-v2-master".toByteArray(Charsets.UTF_8)
        private val HKDF_INFO = "sutra-v2-frame-key".toByteArray(Charsets.UTF_8)
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16

        fun fromPhrase(phrase: String): SecureFrameCodec {
            require(phrase.length >= 12) { "Pairing phrase must contain at least 12 characters" }
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val v2Master = factory.generateSecret(
                PBEKeySpec(phrase.toCharArray(), V2_MASTER_SALT, 60_000, 256)
            ).encoded
            val v1Key = runCatching {
                val material = factory.generateSecret(
                    PBEKeySpec(phrase.toCharArray(), V1_SALT, 120_000, 256)
                ).encoded
                SecretKeySpec(material, "AES")
            }.getOrNull()
            return SecureFrameCodec(v2Master, v1Key)
        }

        private fun deriveFrameKey(masterKey: ByteArray, salt: ByteArray): SecretKeySpec {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = hmac.doFinal(masterKey)

            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(HKDF_INFO)
            hmac.update(1.toByte())
            val okm = hmac.doFinal()
            return SecretKeySpec(okm.copyOf(32), "AES")
        }
    }

    private val random = SecureRandom()

    fun seal(plain: ByteArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val frameKey = deriveFrameKey(masterKey, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, frameKey, GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(PREFIX_V2)
        cipher.updateAAD(salt)
        val encrypted = cipher.doFinal(plain)

        return ByteBuffer.allocate(PREFIX_V2.size + salt.size + nonce.size + encrypted.size)
            .put(PREFIX_V2)
            .put(salt)
            .put(nonce)
            .put(encrypted)
            .array()
    }

    fun open(frame: ByteArray): ByteArray {
        if (frame.size >= PREFIX_V2.size + SALT_BYTES + NONCE_BYTES + TAG_BYTES &&
            frame.copyOfRange(0, PREFIX_V2.size).contentEquals(PREFIX_V2)) {
            val saltStart = PREFIX_V2.size
            val salt = frame.copyOfRange(saltStart, saltStart + SALT_BYTES)
            val nonceStart = saltStart + SALT_BYTES
            val nonce = frame.copyOfRange(nonceStart, nonceStart + NONCE_BYTES)
            val cipherStart = nonceStart + NONCE_BYTES

            val frameKey = deriveFrameKey(masterKey, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, frameKey, GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(PREFIX_V2)
            cipher.updateAAD(salt)
            return cipher.doFinal(frame, cipherStart, frame.size - cipherStart)
        }

        // Backward compatibility for legacy V1 frames
        if (legacyKey != null &&
            frame.size >= PREFIX_V1.size + NONCE_BYTES + TAG_BYTES &&
            frame.copyOfRange(0, PREFIX_V1.size).contentEquals(PREFIX_V1)) {
            val nonce = frame.copyOfRange(PREFIX_V1.size, PREFIX_V1.size + NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(PREFIX_V1)
            return cipher.doFinal(frame, PREFIX_V1.size + NONCE_BYTES, frame.size - PREFIX_V1.size - NONCE_BYTES)
        }

        throw IllegalArgumentException("Peer did not use the configured pairing phrase or frame is corrupt")
    }
}
