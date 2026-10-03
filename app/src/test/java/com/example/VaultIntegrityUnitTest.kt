package com.example

import com.example.crypto.MonoVaultEngine
import com.example.panic.PanicTriggerReceiver
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class VaultIntegrityUnitTest {

    @Test
    fun testBlockTamperingThrowsAeadBadTagException() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val plaintext = "SECRET_PAYLOAD_TEST_CORRUPTION".toByteArray(Charsets.UTF_8)
        val offset = 536875008L
        val timestamp = 1727950000000L
        val zoneId = 1
        val sequence = 0

        val aad = MonoVaultEngine.buildBlockAAD(offset, timestamp, zoneId, sequence)

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        encryptCipher.updateAAD(aad)
        val ciphertext = encryptCipher.doFinal(plaintext)

        // 1. Valid decrypt succeeds
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        decryptCipher.updateAAD(aad)
        val decrypted = decryptCipher.doFinal(ciphertext)
        assertEquals("SECRET_PAYLOAD_TEST_CORRUPTION", String(decrypted, Charsets.UTF_8))

        // 2. Tamper single byte in ciphertext
        val tamperedCiphertext = ciphertext.clone()
        tamperedCiphertext[tamperedCiphertext.size - 2] = (tamperedCiphertext[tamperedCiphertext.size - 2].toInt() xor 0x01).toByte()

        val tamperedDecryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        tamperedDecryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        tamperedDecryptCipher.updateAAD(aad)

        try {
            tamperedDecryptCipher.doFinal(tamperedCiphertext)
            fail("Expected AEADBadTagException on tampered ciphertext!")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException)
        }
    }

    @Test
    fun testBlockReplayThrowsAeadBadTagException() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val plaintext = "REPLAY_DEFENSE_PAYLOAD".toByteArray(Charsets.UTF_8)
        val originalOffset = 4096L
        val movedOffset = 8192L // Attacker moved block to a different offset
        val timestamp = 1727950000000L
        val zoneId = 0
        val sequence = 0

        val originalAad = MonoVaultEngine.buildBlockAAD(originalOffset, timestamp, zoneId, sequence)
        val replayedAad = MonoVaultEngine.buildBlockAAD(movedOffset, timestamp, zoneId, sequence)

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        encryptCipher.updateAAD(originalAad)
        val ciphertext = encryptCipher.doFinal(plaintext)

        // Decrypt at wrong offset (Replay attack attempt)
        val replayDecryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        replayDecryptCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        replayDecryptCipher.updateAAD(replayedAad)

        try {
            replayDecryptCipher.doFinal(ciphertext)
            fail("Expected AEADBadTagException when block is replayed at a different offset!")
        } catch (e: Exception) {
            assertTrue(e is AEADBadTagException)
        }
    }

    @Test
    fun testConstantTimeTokenComparison() {
        val secretA = "3f8b1c4e-7a9d-4e2a-b0c1-5d6e7f8a9b0c"
        val secretB = "3f8b1c4e-7a9d-4e2a-b0c1-5d6e7f8a9b0c"
        val secretC = "3f8b1c4e-7a9d-4e2a-b0c1-5d6e7f8a9b0d" // 1 character difference

        assertTrue(PanicTriggerReceiver.constantTimeEquals(secretA, secretB))
        assertFalse(PanicTriggerReceiver.constantTimeEquals(secretA, secretC))
        assertFalse(PanicTriggerReceiver.constantTimeEquals(secretA, "short"))
    }

    @Test
    fun testHkdfSubkeyDerivation() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (it * 3).toByte() }

        val masterSubkey = MonoVaultEngine.hkdfDeriveKey(ikm, salt, "zone_master".toByteArray())
        val decoySubkey = MonoVaultEngine.hkdfDeriveKey(ikm, salt, "zone_decoy".toByteArray())

        assertFalse(masterSubkey.encoded.contentEquals(decoySubkey.encoded))
        assertEquals(32, masterSubkey.encoded.size)
        assertEquals(32, decoySubkey.encoded.size)
    }
}
