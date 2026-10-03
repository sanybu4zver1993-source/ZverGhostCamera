package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class GhostCryptoVaultTest {

    @Test
    fun testAesGcmCipherRoundtrip() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val plaintext = "GHOST_SUITE_PAYLOAD_TEST_ZERO_EXIF".toByteArray(Charsets.UTF_8)

        // Encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val ciphertext = cipher.doFinal(plaintext)

        // Decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = decryptCipher.doFinal(ciphertext)

        assertEquals("GHOST_SUITE_PAYLOAD_TEST_ZERO_EXIF", String(decrypted, Charsets.UTF_8))
        assertTrue(ciphertext.size > plaintext.size)
    }

    @Test
    fun testCipherOutputStreamGcm() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val plaintext = "STREAMING_EXIF_PURGE_TEST_PAYLOAD".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val baos = java.io.ByteArrayOutputStream()
        javax.crypto.CipherOutputStream(baos, cipher).use { cos ->
            cos.write(plaintext)
        }

        val encryptedBytes = baos.toByteArray()
        assertTrue(encryptedBytes.isNotEmpty())

        // Decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = decryptCipher.doFinal(encryptedBytes)
        assertEquals("STREAMING_EXIF_PURGE_TEST_PAYLOAD", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testPbkdf2KeyDerivationAndWrapping() {
        val pin = charArrayOf('4', '8', '1', '5', '1', '6')
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val spec = javax.crypto.spec.PBEKeySpec(pin, salt, 100_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val kekBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        val kek = javax.crypto.spec.SecretKeySpec(kekBytes, "AES")

        // Vault master key
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val vaultKey = keyGen.generateKey()

        // Wrap
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        wrapCipher.init(Cipher.WRAP_MODE, kek, GCMParameterSpec(128, iv))
        val wrappedKey = wrapCipher.wrap(vaultKey)

        // Unwrap
        val unwrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        unwrapCipher.init(Cipher.UNWRAP_MODE, kek, GCMParameterSpec(128, iv))
        val unwrappedKey = unwrapCipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY)

        assertEquals(vaultKey.algorithm, unwrappedKey.algorithm)
        assertEquals(vaultKey.encoded.size, unwrappedKey.encoded.size)
    }
}
