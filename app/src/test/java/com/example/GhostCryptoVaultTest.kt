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
}
