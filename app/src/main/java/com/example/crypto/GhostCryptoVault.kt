package com.example.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GhostCryptoVault: Hardware-backed Android KeyStore vault using AES-256-GCM.
 * No deprecated APIs. Zero network dependencies.
 * File format: [4 bytes "GCF1"] [12 bytes IV] [Ciphertext + 16 bytes GCM Auth Tag]
 */
object GhostCryptoVault {
    private const val KEY_ALIAS = "GhostSuite_Camera_AES256"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private val MAGIC_HEADER = byteArrayOf(0x47, 0x43, 0x46, 0x31) // "GCF1"

    private val secureRandom = SecureRandom()

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false) // We supply our own cryptographically secure IV
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts plaintext bytes in-memory and writes directly to an encrypted .gcf file
     * in the private vault directory. Never writes plaintext to flash.
     */
    fun encryptAndSave(context: Context, plaintext: ByteArray): File {
        val vaultDir = getVaultDir(context)
        val targetFile = File(vaultDir, "shot_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.gcf")

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)

        try {
            FileOutputStream(targetFile).use { fos ->
                fos.write(MAGIC_HEADER)
                fos.write(iv)
                fos.write(ciphertext)
                fos.fd.sync()
            }
            return targetFile
        } catch (e: Exception) {
            shredFile(targetFile)
            throw IOException("Failed to write encrypted file to vault", e)
        } finally {
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(ciphertext, 0.toByte())
        }
    }

    /**
     * Decrypts a .gcf file on-the-fly into RAM. Returns raw decrypted JPEG bytes.
     */
    fun decryptToRam(file: File): ByteArray {
        if (!file.exists() || file.length() < (MAGIC_HEADER.size + GCM_IV_LENGTH_BYTES + 16)) {
            throw IOException("Invalid or corrupted GCF file")
        }

        val fileBytes = FileInputStream(file).use { it.readBytes() }
        try {
            for (i in MAGIC_HEADER.indices) {
                if (fileBytes[i] != MAGIC_HEADER[i]) {
                    throw IOException("Invalid GCF magic header")
                }
            }

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(fileBytes, MAGIC_HEADER.size, iv, 0, GCM_IV_LENGTH_BYTES)

            val ciphertextOffset = MAGIC_HEADER.size + GCM_IV_LENGTH_BYTES
            val ciphertextSize = fileBytes.size - ciphertextOffset

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), gcmSpec)

            val decrypted = cipher.doFinal(fileBytes, ciphertextOffset, ciphertextSize)
            Arrays.fill(iv, 0.toByte())
            return decrypted
        } finally {
            Arrays.fill(fileBytes, 0.toByte())
        }
    }

    /**
     * Decrypts file into RAM and decodes directly to a Bitmap. Immediately zeroes decrypted byte array.
     */
    fun decryptToBitmap(file: File): Bitmap {
        val decryptedBytes = decryptToRam(file)
        try {
            return BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                ?: throw IOException("Failed to decode decrypted JPEG")
        } finally {
            Arrays.fill(decryptedBytes, 0.toByte())
        }
    }

    /**
     * Multi-pass file shredder: Overwrites file with random bytes, then zeroes,
     * flushes to hardware storage, and deletes the file inode.
     */
    fun shredFile(file: File): Boolean {
        if (!file.exists()) return true
        val length = file.length()
        try {
            if (length > 0) {
                val dummy = ByteArray(4096)
                FileOutputStream(file).use { fos ->
                    // Pass 1: Random noise
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(dummy.size.toLong(), remaining).toInt()
                        secureRandom.nextBytes(dummy)
                        fos.write(dummy, 0, toWrite)
                        remaining -= toWrite
                    }
                    fos.fd.sync()

                    // Pass 2: Pure zeroes
                    Arrays.fill(dummy, 0.toByte())
                    remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(dummy.size.toLong(), remaining).toInt()
                        fos.write(dummy, 0, toWrite)
                        remaining -= toWrite
                    }
                    fos.fd.sync()
                }
            }
        } catch (_: Exception) {
            // Best effort wipe
        }
        return file.delete()
    }

    /**
     * Emergency Wipe: Destroys all vault files and securely shreds each one.
     */
    fun emergencyWipeAll(context: Context): Int {
        val vaultDir = getVaultDir(context)
        val files = vaultDir.listFiles { _, name -> name.endsWith(".gcf") } ?: emptyArray()
        var shreddedCount = 0
        for (f in files) {
            if (shredFile(f)) {
                shredgedCountIncrement(shreddedCount)
                shreddedCount++
            }
        }
        // Also remove key from keystore so any unrecoverable residues cannot be decrypted
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {}
        return shreddedCount
    }

    private fun shredgedCountIncrement(count: Int) {}

    fun getVaultDir(context: Context): File {
        val dir = File(context.filesDir, "ghost_vault")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (_: Exception) {}
        }
        return dir
    }

    fun listVaultFiles(context: Context): List<File> {
        val dir = getVaultDir(context)
        return dir.listFiles { _, name -> name.endsWith(".gcf") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun getVaultSizeBytes(context: Context): Long {
        val files = listVaultFiles(context)
        return files.sumOf { it.length() }
    }
}
