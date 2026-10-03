package com.example.crypto

import android.content.Context
import android.content.pm.PackageManager
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
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * GhostCryptoVault v2.1:
 * - VeraCrypt-style Dual Vault (MAIN & DECOY).
 * - Hardware TEE KeyStore with dynamic StrongBox fallback (prevents crash on Helio G85).
 * - PBKDF2WithHmacSHA256 Key Derivation & AES-GCM Key Wrapping.
 * - ZERO String objects for PIN (uses CharArray with mandatory zero-fill).
 * - True cryptographic authorization (successful GCM unwrap, not string comparison).
 */
object GhostCryptoVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 128
    private const val SALT_LEN = 16
    private val MAGIC_HEADER = byteArrayOf(0x47, 0x43, 0x46, 0x31) // "GCF1"

    private val secureRandom = SecureRandom()

    enum class VaultProfile(val dirName: String, val saltFile: String, val keyFile: String) {
        MAIN("vault_main", "salt_main.dat", "wrapped_main.key"),
        DECOY("vault_decoy", "salt_decoy.dat", "wrapped_decoy.key");

        fun getDirectory(context: Context): File {
            val dir = File(context.filesDir, dirName)
            if (!dir.exists()) dir.mkdirs()
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) {
                try { nomedia.createNewFile() } catch (_: Exception) {}
            }
            return dir
        }
    }

    // Active unlocked session key cache (stored only in RAM while unlocked)
    private var activeMainKey: SecretKey? = null
    private var activeDecoyKey: SecretKey? = null
    var activeProfile: VaultProfile = VaultProfile.MAIN

    /**
     * Checks if a master PIN has been configured.
     */
    fun isConfigured(context: Context): Boolean {
        val saltFile = File(context.filesDir, VaultProfile.MAIN.saltFile)
        val keyFile = File(context.filesDir, VaultProfile.MAIN.keyFile)
        return saltFile.exists() && keyFile.exists()
    }

    /**
     * Checks if a decoy vault PIN has been configured.
     */
    fun hasDecoyConfigured(context: Context): Boolean {
        val saltFile = File(context.filesDir, VaultProfile.DECOY.saltFile)
        val keyFile = File(context.filesDir, VaultProfile.DECOY.keyFile)
        return saltFile.exists() && keyFile.exists()
    }

    /**
     * Initializes or updates PIN credentials for Main and optional Decoy vault.
     * PINs are accepted strictly as CharArray and zero-filled in finally.
     */
    fun setupVaultPins(
        context: Context,
        mainPin: CharArray,
        decoyPin: CharArray?
    ) {
        try {
            // Setup Main Vault
            setupProfileKey(context, VaultProfile.MAIN, mainPin)

            // Setup Decoy Vault if provided
            if (decoyPin != null && decoyPin.isNotEmpty()) {
                setupProfileKey(context, VaultProfile.DECOY, decoyPin)
            }
        } finally {
            Arrays.fill(mainPin, '0')
            decoyPin?.let { Arrays.fill(it, '0') }
        }
    }

    private fun setupProfileKey(
        context: Context,
        profile: VaultProfile,
        pin: CharArray
    ) {
        val salt = ByteArray(SALT_LEN)
        secureRandom.nextBytes(salt)

        val kek = deriveKekFromPin(pin, salt)

        // Generate high-entropy 256-bit AES master key for this vault
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val vaultAesKey = keyGen.generateKey()

        // Wrap the vault key using the derived KEK with AES-GCM
        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.WRAP_MODE, kek, GCMParameterSpec(GCM_TAG_LEN, iv))
        val wrappedKeyBytes = cipher.wrap(vaultAesKey)

        // Save salt
        val saltFile = File(context.filesDir, profile.saltFile)
        FileOutputStream(saltFile).use { it.write(salt) }

        // Save wrapped key: [12 bytes IV] + [wrapped key payload]
        val keyFile = File(context.filesDir, profile.keyFile)
        FileOutputStream(keyFile).use { fos ->
            fos.write(iv)
            fos.write(wrappedKeyBytes)
            fos.fd.sync()
        }

        // Cache in memory for immediate use
        if (profile == VaultProfile.MAIN) {
            activeMainKey = vaultAesKey
        } else {
            activeDecoyKey = vaultAesKey
        }

        Arrays.fill(salt, 0.toByte())
        Arrays.fill(iv, 0.toByte())
    }

    /**
     * Authenticates by attempting cryptographic unwrap of Main key, then Decoy key.
     * ZERO string comparisons. If GCM auth tag matches -> valid PIN.
     * PIN CharArray is immediately zeroed.
     */
    fun unlockWithPin(context: Context, pin: CharArray): VaultProfile? {
        try {
            // 1. Try Main Vault
            if (isConfigured(context)) {
                val key = tryUnwrapKey(context, VaultProfile.MAIN, pin)
                if (key != null) {
                    activeMainKey = key
                    activeProfile = VaultProfile.MAIN
                    return VaultProfile.MAIN
                }
            }

            // 2. Try Decoy Vault
            if (hasDecoyConfigured(context)) {
                val key = tryUnwrapKey(context, VaultProfile.DECOY, pin)
                if (key != null) {
                    activeDecoyKey = key
                    activeProfile = VaultProfile.DECOY
                    return VaultProfile.DECOY
                }
            }

            return null // Invalid PIN
        } finally {
            Arrays.fill(pin, '0')
        }
    }

    private fun tryUnwrapKey(context: Context, profile: VaultProfile, pin: CharArray): SecretKey? {
        val saltFile = File(context.filesDir, profile.saltFile)
        val keyFile = File(context.filesDir, profile.keyFile)
        if (!saltFile.exists() || !keyFile.exists()) return null

        val salt = FileInputStream(saltFile).use { it.readBytes() }
        val keyFileData = FileInputStream(keyFile).use { it.readBytes() }

        if (salt.size < SALT_LEN || keyFileData.size < (GCM_IV_LEN + 16)) {
            return null
        }

        val iv = ByteArray(GCM_IV_LEN)
        System.arraycopy(keyFileData, 0, iv, 0, GCM_IV_LEN)
        val wrappedOffset = GCM_IV_LEN
        val wrappedSize = keyFileData.size - GCM_IV_LEN

        try {
            val kek = deriveKekFromPin(pin, salt)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.UNWRAP_MODE, kek, GCMParameterSpec(GCM_TAG_LEN, iv))
            return cipher.unwrap(keyFileData.copyOfRange(wrappedOffset, wrappedOffset + wrappedSize), "AES", Cipher.SECRET_KEY) as? SecretKey
        } catch (_: Exception) {
            return null // Auth tag mismatch
        } finally {
            Arrays.fill(salt, 0.toByte())
            Arrays.fill(keyFileData, 0.toByte())
            Arrays.fill(iv, 0.toByte())
        }
    }

    private fun deriveKekFromPin(pin: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        val rawBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        val key = SecretKeySpec(rawBytes, "AES")
        Arrays.fill(rawBytes, 0.toByte())
        return key
    }

    /**
     * Returns the active session key for the currently unlocked profile,
     * or gets/creates hardware Keystore fallback if first run before setup.
     */
    fun getActiveVaultKey(context: Context): SecretKey {
        val key = if (activeProfile == VaultProfile.MAIN) activeMainKey else activeDecoyKey
        if (key != null) return key

        // Fallback for unconfigured initial run: Hardware Keystore
        return getHardwareKeystoreKey(context, "Ghost_Vault_${activeProfile.name}")
    }

    /**
     * Hardware KeyStore key with dynamic StrongBox fallback to standard TEE.
     * Prevents crash on MediaTek Helio G85 on Redmi 13C.
     */
    @Synchronized
    private fun getHardwareKeystoreKey(context: Context, alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val isStrongBoxSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false)

        if (isStrongBoxSupported) {
            try {
                builder.setIsStrongBoxBacked(true)
            } catch (_: Exception) {}
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Opens a streaming CipherOutputStream directly to an encrypted .gcf file
     * inside the current active vault directory (vault_main or vault_decoy).
     */
    fun openEncryptedOutputStream(context: Context): Pair<File, CipherOutputStream> {
        val vaultDir = activeProfile.getDirectory(context)
        val targetFile = File(vaultDir, "shot_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.gcf")

        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LEN, iv)
        cipher.init(Cipher.ENCRYPT_MODE, getActiveVaultKey(context), gcmSpec)

        val fos = FileOutputStream(targetFile)
        try {
            fos.write(MAGIC_HEADER)
            fos.write(iv)
            fos.flush()
        } catch (e: Exception) {
            fos.close()
            targetFile.delete()
            throw e
        }

        val cos = CipherOutputStream(fos, cipher)
        return Pair(targetFile, cos)
    }

    /**
     * Decrypts a .gcf file on-the-fly into RAM. Returns raw decrypted JPEG bytes.
     */
    fun decryptToRam(context: Context, file: File): ByteArray {
        if (!file.exists() || file.length() < (MAGIC_HEADER.size + GCM_IV_LEN + 16)) {
            throw IOException("Invalid or corrupted GCF file")
        }

        val fileBytes = FileInputStream(file).use { it.readBytes() }
        try {
            for (i in MAGIC_HEADER.indices) {
                if (fileBytes[i] != MAGIC_HEADER[i]) {
                    throw IOException("Invalid GCF magic header")
                }
            }

            val iv = ByteArray(GCM_IV_LEN)
            System.arraycopy(fileBytes, MAGIC_HEADER.size, iv, 0, GCM_IV_LEN)

            val ciphertextOffset = MAGIC_HEADER.size + GCM_IV_LEN
            val ciphertextSize = fileBytes.size - ciphertextOffset

            val cipher = Cipher.getInstance(AES_GCM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LEN, iv)
            cipher.init(Cipher.DECRYPT_MODE, getActiveVaultKey(context), gcmSpec)

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
    fun decryptToBitmap(context: Context, file: File): Bitmap {
        val decryptedBytes = decryptToRam(context, file)
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
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(dummy.size.toLong(), remaining).toInt()
                        secureRandom.nextBytes(dummy)
                        fos.write(dummy, 0, toWrite)
                        remaining -= toWrite
                    }
                    fos.fd.sync()

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
        } catch (_: Exception) {}
        return file.delete()
    }

    /**
     * True Crypto-Shredding:
     * Destroys both MAIN and DECOY keys, salts, wrapped key files, Keystore aliases,
     * and shreds all files in both directories.
     */
    fun cryptoShred(context: Context): Int {
        var count = 0
        activeMainKey = null
        activeDecoyKey = null

        // Shred wrapped keys and salts
        for (profile in VaultProfile.values()) {
            val salt = File(context.filesDir, profile.saltFile)
            val key = File(context.filesDir, profile.keyFile)
            shredFile(salt)
            shredFile(key)

            val files = listVaultFiles(context, profile)
            count += files.size
            for (f in files) {
                shredFile(f)
            }
        }

        // Wipe Keystore aliases
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (alias.startsWith("Ghost_Vault")) {
                    keyStore.deleteEntry(alias)
                }
            }
        } catch (_: Exception) {}

        return count
    }

    fun listVaultFiles(context: Context, profile: VaultProfile = activeProfile): List<File> {
        val dir = profile.getDirectory(context)
        return dir.listFiles { _, name -> name.endsWith(".gcf") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun lockSession() {
        activeMainKey = null
        activeDecoyKey = null
        activeProfile = VaultProfile.MAIN
    }

    /**
     * Decrypts a file strictly to internal cacheDir for temporary sharing via FileProvider.
     * External storage is never used.
     */
    fun createEphemeralShareFile(context: Context, sourceFile: File): File {
        val shareDir = File(context.cacheDir, "ephemeral_shared")
        if (!shareDir.exists()) shareDir.mkdirs()
        val tmpFile = File(shareDir, "share_${UUID.randomUUID().toString().take(8)}.jpg")
        val decryptedBytes = decryptToRam(context, sourceFile)
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(decryptedBytes)
                fos.fd.sync()
            }
            return tmpFile
        } finally {
            Arrays.fill(decryptedBytes, 0.toByte())
        }
    }

    /**
     * Immediately zero-overwrites and shreds all temporary share files in cacheDir.
     */
    fun shredEphemeralShareFiles(context: Context): Int {
        val shareDir = File(context.cacheDir, "ephemeral_shared")
        if (!shareDir.exists()) return 0
        val files = shareDir.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (shredFile(f)) count++
        }
        return count
    }
}
