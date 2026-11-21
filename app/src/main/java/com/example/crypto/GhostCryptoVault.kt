package com.example.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.camera.ExifStripper
import com.example.security.ZverPrefs
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * GhostCryptoVault v3.0:
 * - 3-Contour Vault: MAIN, DECOY, and DURESS (Duress silently shreds MAIN + Monolith & opens DECOY).
 * - Ephemeral RAM-Only Contour: stores shots strictly in volatile memory (0 bytes touched on flash).
 * - Hardware TEE KeyStore with dynamic StrongBox capability check (Helio G85 safe).
 * - PBKDF2WithHmacSHA256 (600k iterations) Key Derivation & AES-256-GCM Key Wrapping.
 * - ZERO String objects for PIN (CharArray with mandatory zero-fill).
 * - Detached ProofMode-lite generator (SHA-256 + KeyStore ECDSA secp256r1, zero PII/EXIF/GPS).
 */
object GhostCryptoVault {
    const val MEDIA_PHOTO = "shot"
    const val MEDIA_VIDEO = "vid"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 600_000
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 128
    private const val SALT_LEN = 16
    private const val MAX_RAM_VAULT_BYTES = 96L * 1024 * 1024 // 96 MB cap on 4GB Helio G85
    private val MAGIC_HEADER = byteArrayOf(0x47, 0x43, 0x46, 0x31) // "GCF1"

    private const val DURESS_SALT_FILE = "salt_duress.dat"
    private const val DURESS_KEY_FILE = "wrapped_duress.key"

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

    enum class UnlockOutcome {
        MAIN,
        DECOY,
        DURESS_WIPED,
        INVALID
    }

    data class RamShot(
        val id: String,
        val bytes: ByteArray,
        val timestamp: Long,
        val isVideo: Boolean
    )

    enum class KeySourceKind(val labelRu: String) {
        ACTIVE_MAIN_PIN("PIN-ключ MAIN (PBKDF2)"),
        ACTIVE_DECOY_PIN("PIN-ключ DECOY (PBKDF2)"),
        KEYSTORE_MAIN_FALLBACK("KeyStore Fallback (Ghost_Vault_MAIN)"),
        KEYSTORE_DECOY_FALLBACK("KeyStore Fallback (Ghost_Vault_DECOY)"),
        UNKNOWN_OR_CORRUPTED("Неизвестный ключ / Повреждён")
    }

    data class GcfHeaderInfo(
        val fileName: String,
        val sizeBytes: Long,
        val hasValidMagic: Boolean,
        val ivHex: String,
        val ciphertextBytes: Long,
        val errorMessage: String? = null,
        val formatVersion: String = "GCF1"
    )

    data class FileKeyDiagnostic(
        val file: File,
        val headerInfo: GcfHeaderInfo,
        val matchedKeyKind: KeySourceKind,
        val plaintextBytesLength: Int = 0,
        val errorDetail: String? = null
    )

    data class BackupResult(
        val backupDir: File,
        val copiedFilesCount: Int,
        val copiedBytes: Long,
        val metadataFilesCount: Int
    )

    private val ramLock = Any()
    private val ramShots = mutableListOf<RamShot>()

    // Active unlocked session key cache (stored only in RAM while unlocked)
    private var activeMainKey: SecretKey? = null
    private var activeDecoyKey: SecretKey? = null
    var activeProfile: VaultProfile = VaultProfile.MAIN

    fun isConfigured(context: Context): Boolean {
        val saltFile = File(context.filesDir, VaultProfile.MAIN.saltFile)
        val keyFile = File(context.filesDir, VaultProfile.MAIN.keyFile)
        return saltFile.exists() && keyFile.exists()
    }

    fun hasDecoyConfigured(context: Context): Boolean {
        val saltFile = File(context.filesDir, VaultProfile.DECOY.saltFile)
        val keyFile = File(context.filesDir, VaultProfile.DECOY.keyFile)
        return saltFile.exists() && keyFile.exists()
    }

    fun hasDuressConfigured(context: Context): Boolean {
        val saltFile = File(context.filesDir, DURESS_SALT_FILE)
        val keyFile = File(context.filesDir, DURESS_KEY_FILE)
        return saltFile.exists() && keyFile.exists()
    }

    /**
     * Initializes or updates PIN credentials for Main, optional Decoy, and optional Duress vaults.
     * PINs are accepted strictly as CharArray and zero-filled in finally.
     */
    fun setupVaultPins(
        context: Context,
        mainPin: CharArray,
        decoyPin: CharArray?,
        duressPin: CharArray? = null
    ) {
        try {
            setupProfileKey(context, VaultProfile.MAIN, mainPin)
            activeProfile = VaultProfile.MAIN
            ZverPrefs.touchActivity(context)
            ZverPrefs.resetFailedPinAttempts(context)

            if (decoyPin != null && decoyPin.isNotEmpty()) {
                setupProfileKey(context, VaultProfile.DECOY, decoyPin)
            }

            if (duressPin != null && duressPin.isNotEmpty()) {
                setupWrappedPinFiles(
                    saltFile = File(context.filesDir, DURESS_SALT_FILE),
                    keyFile = File(context.filesDir, DURESS_KEY_FILE),
                    pin = duressPin
                )
            } else {
                shredFile(File(context.filesDir, DURESS_SALT_FILE))
                shredFile(File(context.filesDir, DURESS_KEY_FILE))
            }
        } finally {
            Arrays.fill(mainPin, '0')
            decoyPin?.let { Arrays.fill(it, '0') }
            duressPin?.let { Arrays.fill(it, '0') }
        }
    }

    private fun setupProfileKey(
        context: Context,
        profile: VaultProfile,
        pin: CharArray
    ) {
        val key = setupWrappedPinFiles(
            saltFile = File(context.filesDir, profile.saltFile),
            keyFile = File(context.filesDir, profile.keyFile),
            pin = pin
        )
        if (profile == VaultProfile.MAIN) {
            activeMainKey = key
        } else {
            activeDecoyKey = key
        }
    }

    private fun setupWrappedPinFiles(
        saltFile: File,
        keyFile: File,
        pin: CharArray
    ): SecretKey {
        val salt = ByteArray(SALT_LEN)
        secureRandom.nextBytes(salt)

        val kek = deriveKekFromPin(pin, salt)

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val vaultAesKey = keyGen.generateKey()

        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.WRAP_MODE, kek, GCMParameterSpec(GCM_TAG_LEN, iv))
        val wrappedKeyBytes = cipher.wrap(vaultAesKey)

        FileOutputStream(saltFile).use {
            it.write(salt)
            it.fd.sync()
        }

        FileOutputStream(keyFile).use { fos ->
            fos.write(iv)
            fos.write(wrappedKeyBytes)
            fos.fd.sync()
        }

        Arrays.fill(salt, 0.toByte())
        Arrays.fill(iv, 0.toByte())
        return vaultAesKey
    }

    /**
     * Authenticates by attempting cryptographic unwrap across MAIN -> DECOY -> DURESS.
     * DURESS silently crypto-shreds MAIN + Monolith + RAM shots and opens DECOY.
     */
    fun unlockWithPinOutcome(context: Context, pin: CharArray): UnlockOutcome {
        try {
            // 1. Try Main Vault
            if (isConfigured(context)) {
                val key = tryUnwrapFiles(
                    File(context.filesDir, VaultProfile.MAIN.saltFile),
                    File(context.filesDir, VaultProfile.MAIN.keyFile),
                    pin
                )
                if (key != null) {
                    activeMainKey = key
                    activeProfile = VaultProfile.MAIN
                    ZverPrefs.resetFailedPinAttempts(context)
                    ZverPrefs.touchActivity(context)
                    return UnlockOutcome.MAIN
                }
            }

            // 2. Try Decoy Vault
            if (hasDecoyConfigured(context)) {
                val key = tryUnwrapFiles(
                    File(context.filesDir, VaultProfile.DECOY.saltFile),
                    File(context.filesDir, VaultProfile.DECOY.keyFile),
                    pin
                )
                if (key != null) {
                    activeDecoyKey = key
                    activeProfile = VaultProfile.DECOY
                    ZverPrefs.resetFailedPinAttempts(context)
                    ZverPrefs.touchActivity(context)
                    return UnlockOutcome.DECOY
                }
            }

            // 3. Try Duress PIN (Silent Plausible-Deniability Panic Shred)
            if (hasDuressConfigured(context)) {
                val duressMatch = tryUnwrapFiles(
                    File(context.filesDir, DURESS_SALT_FILE),
                    File(context.filesDir, DURESS_KEY_FILE),
                    pin
                )
                if (duressMatch != null) {
                    clearRamShots()
                    shredProfile(context, VaultProfile.MAIN)
                    MonoVaultEngine.cryptoShredMonolith(context)
                    shredFile(File(context.filesDir, DURESS_SALT_FILE))
                    shredFile(File(context.filesDir, DURESS_KEY_FILE))
                    activeMainKey = null
                    activeProfile = VaultProfile.DECOY
                    ZverPrefs.resetFailedPinAttempts(context)
                    return UnlockOutcome.DURESS_WIPED
                }
            }

            return UnlockOutcome.INVALID
        } finally {
            Arrays.fill(pin, '0')
        }
    }

    fun unlockWithPin(context: Context, pin: CharArray): VaultProfile? {
        return when (unlockWithPinOutcome(context, pin)) {
            UnlockOutcome.MAIN -> VaultProfile.MAIN
            UnlockOutcome.DECOY, UnlockOutcome.DURESS_WIPED -> VaultProfile.DECOY
            UnlockOutcome.INVALID -> null
        }
    }

    private fun tryUnwrapFiles(saltFile: File, keyFile: File, pin: CharArray): SecretKey? {
        if (!saltFile.exists() || !keyFile.exists()) return null

        val salt = FileInputStream(saltFile).use { it.readBytes() }
        val keyFileData = FileInputStream(keyFile).use { it.readBytes() }

        if (salt.size < SALT_LEN || keyFileData.size < (GCM_IV_LEN + 16)) {
            Arrays.fill(salt, 0.toByte())
            Arrays.fill(keyFileData, 0.toByte())
            return null
        }

        val iv = ByteArray(GCM_IV_LEN)
        System.arraycopy(keyFileData, 0, iv, 0, GCM_IV_LEN)
        val wrappedOffset = GCM_IV_LEN
        val wrappedSize = keyFileData.size - GCM_IV_LEN

        return try {
            val kek = deriveKekFromPin(pin, salt)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.UNWRAP_MODE, kek, GCMParameterSpec(GCM_TAG_LEN, iv))
            cipher.unwrap(
                keyFileData.copyOfRange(wrappedOffset, wrappedOffset + wrappedSize),
                "AES",
                Cipher.SECRET_KEY
            ) as? SecretKey
        } catch (_: Exception) {
            null
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

    fun getActiveVaultKey(context: Context): SecretKey {
        val key = if (activeProfile == VaultProfile.MAIN) activeMainKey else activeDecoyKey
        if (key != null) return key
        return getHardwareKeystoreKey(context, "Ghost_Vault_${activeProfile.name}")
    }

    fun getActiveVaultKeySourceLabel(context: Context): String {
        val unlockedPin = if (activeProfile == VaultProfile.MAIN) activeMainKey else activeDecoyKey
        return when {
            unlockedPin != null && activeProfile == VaultProfile.MAIN -> KeySourceKind.ACTIVE_MAIN_PIN.labelRu
            unlockedPin != null && activeProfile == VaultProfile.DECOY -> KeySourceKind.ACTIVE_DECOY_PIN.labelRu
            isConfigured(context) -> "KeyStore Fallback при настроенном PIN (Ghost_Vault_${activeProfile.name})"
            else -> "KeyStore Fallback (Ghost_Vault_${activeProfile.name})"
        }
    }

    private fun getExistingHardwareKeystoreKeyOrNull(alias: String): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(alias)) return null
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (_: Exception) {
            null
        }
    }

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

    // --- EPHEMERAL RAM-ONLY VAULT CONTOUR ---

    fun addRamShot(bytes: ByteArray, isVideo: Boolean = false): RamShot {
        synchronized(ramLock) {
            var currentTotal = ramShots.sumOf { it.bytes.size.toLong() }
            while (ramShots.isNotEmpty() && currentTotal + bytes.size > MAX_RAM_VAULT_BYTES) {
                val oldest = ramShots.removeAt(0)
                currentTotal -= oldest.bytes.size
                Arrays.fill(oldest.bytes, 0.toByte())
            }
            val shot = RamShot(
                id = UUID.randomUUID().toString().take(8),
                bytes = bytes,
                timestamp = System.currentTimeMillis(),
                isVideo = isVideo
            )
            ramShots.add(shot)
            return shot
        }
    }

    fun listRamShots(): List<RamShot> {
        synchronized(ramLock) {
            return ramShots.sortedByDescending { it.timestamp }
        }
    }

    fun getRamShotBytes(id: String): ByteArray? {
        synchronized(ramLock) {
            return ramShots.firstOrNull { it.id == id }?.bytes?.clone()
        }
    }

    fun shredRamShot(id: String): Boolean {
        synchronized(ramLock) {
            val idx = ramShots.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val removed = ramShots.removeAt(idx)
            Arrays.fill(removed.bytes, 0.toByte())
            return true
        }
    }

    fun clearRamShots(): Int {
        synchronized(ramLock) {
            val count = ramShots.size
            for (s in ramShots) {
                Arrays.fill(s.bytes, 0.toByte())
            }
            ramShots.clear()
            return count
        }
    }

    // --- ENCRYPTED DISK VAULT STREAMS ---

    fun writeEncryptedAtomically(
        context: Context,
        prefix: String = MEDIA_PHOTO,
        writer: (CipherOutputStream) -> Unit
    ): File {
        val vaultDir = activeProfile.getDirectory(context)
        val baseName = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val tempFile = File(vaultDir, "$baseName.gcf.tmp")
        val targetFile = File(vaultDir, "$baseName.gcf")

        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LEN, iv)
        cipher.init(Cipher.ENCRYPT_MODE, getActiveVaultKey(context), gcmSpec)

        val fos = FileOutputStream(tempFile)
        try {
            fos.write(MAGIC_HEADER)
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                writer(cos)
                cos.flush()
            }
            if (!tempFile.exists() || tempFile.length() < (MAGIC_HEADER.size + GCM_IV_LEN + 16)) {
                throw IOException("Incomplete GCF atomic write")
            }
            if (!tempFile.renameTo(targetFile)) {
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(targetFile).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                shredFile(tempFile)
            }
            return targetFile
        } catch (e: Exception) {
            try { fos.close() } catch (_: Exception) {}
            shredFile(tempFile)
            shredFile(targetFile)
            throw e
        } finally {
            Arrays.fill(iv, 0.toByte())
        }
    }

    fun openEncryptedOutputStream(context: Context, prefix: String = MEDIA_PHOTO): Pair<File, CipherOutputStream> {
        val vaultDir = activeProfile.getDirectory(context)
        val targetFile = File(vaultDir, "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.gcf")

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
        } finally {
            Arrays.fill(iv, 0.toByte())
        }

        val cos = CipherOutputStream(fos, cipher)
        return Pair(targetFile, cos)
    }

    fun isVideoFile(file: File): Boolean = file.name.startsWith("${MEDIA_VIDEO}_")

    fun encryptBytesToVault(context: Context, plainBytes: ByteArray, prefix: String = MEDIA_PHOTO): File {
        val targetFile = writeEncryptedAtomically(context, prefix) { stream ->
            stream.write(plainBytes)
        }
        try {
            val volume = if (activeProfile == VaultProfile.MAIN) {
                MonoVaultEngine.VolumeType.MASTER
            } else {
                MonoVaultEngine.VolumeType.DECOY
            }
            MonoVaultEngine.appendRecord(context, volume, plainBytes)
        } catch (_: Exception) {}
        return targetFile
    }

    fun isGcf2File(file: File): Boolean {
        if (!file.exists() || file.length() < GcfChunkedCodec.HEADER_SIZE) return false
        return try {
            val head = ByteArray(4)
            FileInputStream(file).use { fis ->
                if (fis.read(head) != 4) return false
            }
            GcfChunkedCodec.isGcf2HeaderBytes(head)
        } catch (_: Exception) {
            false
        }
    }

    fun openGcf2DataSource(context: Context, file: File): EncryptedMediaDataSource? {
        if (!isGcf2File(file)) return null
        return EncryptedMediaDataSource(file, getActiveVaultKey(context))
    }

    fun encryptFileToVault(context: Context, sourceFile: File, prefix: String = MEDIA_VIDEO): File {
        try {
            val useGcf2 = prefix == MEDIA_VIDEO || sourceFile.length() > 1024L * 1024L
            if (useGcf2) {
                val vaultDir = activeProfile.getDirectory(context)
                val targetFile = File(
                    vaultDir,
                    "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.gcf"
                )
                GcfChunkedCodec.encryptFileAtomically(sourceFile, targetFile, getActiveVaultKey(context))
                return targetFile
            }
            return writeEncryptedAtomically(context, prefix) { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
        } finally {
            shredFile(sourceFile)
        }
    }

    /**
     * Standard strict decryption: NEVER performs silent fallback to another key on AEADBadTagException.
     */
    fun decryptToRam(context: Context, file: File): ByteArray {
        if (!file.exists()) {
            throw IOException("Invalid or corrupted GCF file")
        }
        if (isGcf2File(file)) {
            return try {
                GcfChunkedCodec.decryptToBytes(file, getActiveVaultKey(context))
            } catch (e: AEADBadTagException) {
                val activeLabel = getActiveVaultKeySourceLabel(context)
                throw AEADBadTagException(
                    "Несовпадение ключа GCM (активен: $activeLabel). Оригинал не изменён; запустите read-only диагностику."
                ).apply { initCause(e) }
            }
        }
        if (file.length() < (MAGIC_HEADER.size + GCM_IV_LEN + 16)) {
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

            val decrypted = try {
                cipher.doFinal(fileBytes, ciphertextOffset, ciphertextSize)
            } catch (e: AEADBadTagException) {
                val activeLabel = getActiveVaultKeySourceLabel(context)
                throw AEADBadTagException(
                    "Несовпадение ключа GCM (активен: $activeLabel). Оригинал не изменён; запустите read-only диагностику."
                ).apply { initCause(e) }
            }
            Arrays.fill(iv, 0.toByte())
            return decrypted
        } finally {
            Arrays.fill(fileBytes, 0.toByte())
        }
    }

    fun decryptToBitmap(context: Context, file: File): Bitmap {
        val decryptedBytes = decryptToRam(context, file)
        try {
            return BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                ?: throw IOException("Failed to decode decrypted image")
        } finally {
            Arrays.fill(decryptedBytes, 0.toByte())
        }
    }

    // --- READ-ONLY VAULT DIAGNOSTICS, BACKUP & COPY-ONLY RECOVERY ---

    fun inspectGcfHeader(file: File): GcfHeaderInfo {
        if (!file.exists()) {
            return GcfHeaderInfo(file.name, 0L, false, "", 0L, "Файл не найден")
        }
        val size = file.length()
        if (isGcf2File(file)) {
            val h = GcfChunkedCodec.readHeader(file)
                ?: return GcfHeaderInfo(file.name, size, false, "", 0L, "Повреждён заголовок GCF2", "GCF2")
            val ivHex = h.baseNonce.joinToString("") { "%02x".format(it) }
            val ctLen = (size - GcfChunkedCodec.HEADER_SIZE).coerceAtLeast(0L)
            return GcfHeaderInfo(
                fileName = file.name,
                sizeBytes = size,
                hasValidMagic = true,
                ivHex = ivHex,
                ciphertextBytes = ctLen,
                errorMessage = null,
                formatVersion = "GCF2 (${h.chunkCount}ч)"
            )
        }
        val minSize = (MAGIC_HEADER.size + GCM_IV_LEN + 16).toLong()
        if (size < minSize) {
            return GcfHeaderInfo(file.name, size, false, "", 0L, "Файл меньше минимального размера GCF ($size < $minSize)")
        }
        return try {
            val head = ByteArray(MAGIC_HEADER.size + GCM_IV_LEN)
            FileInputStream(file).use { fis ->
                val read = fis.read(head)
                if (read < head.size) {
                    return GcfHeaderInfo(file.name, size, false, "", 0L, "Неполный заголовок ($read Б)")
                }
            }
            val validMagic = MAGIC_HEADER.indices.all { head[it] == MAGIC_HEADER[it] }
            val ivHex = head.copyOfRange(MAGIC_HEADER.size, head.size)
                .joinToString("") { "%02x".format(it) }
            val ctLen = size - head.size
            Arrays.fill(head, 0.toByte())
            GcfHeaderInfo(
                fileName = file.name,
                sizeBytes = size,
                hasValidMagic = validMagic,
                ivHex = ivHex,
                ciphertextBytes = ctLen,
                errorMessage = if (validMagic) null else "Отсутствует заголовок GCF1/GCF2",
                formatVersion = "GCF1"
            )
        } catch (e: Exception) {
            GcfHeaderInfo(file.name, size, false, "", 0L, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Strictly read-only: identifies which key class authenticates a GCF1/GCF2 file without altering
     * originals or creating new KeyStore aliases.
     */
    fun diagnoseDecryptKeySource(context: Context, file: File): FileKeyDiagnostic {
        val header = inspectGcfHeader(file)
        if (!header.hasValidMagic || header.ciphertextBytes < 16L) {
            return FileKeyDiagnostic(
                file = file,
                headerInfo = header,
                matchedKeyKind = KeySourceKind.UNKNOWN_OR_CORRUPTED,
                errorDetail = header.errorMessage ?: "Некорректный GCF"
            )
        }

        val candidates = buildList {
            activeMainKey?.let { add(KeySourceKind.ACTIVE_MAIN_PIN to it) }
            activeDecoyKey?.let { add(KeySourceKind.ACTIVE_DECOY_PIN to it) }
            getExistingHardwareKeystoreKeyOrNull("Ghost_Vault_MAIN")?.let {
                add(KeySourceKind.KEYSTORE_MAIN_FALLBACK to it)
            }
            getExistingHardwareKeystoreKeyOrNull("Ghost_Vault_DECOY")?.let {
                add(KeySourceKind.KEYSTORE_DECOY_FALLBACK to it)
            }
        }

        if (isGcf2File(file)) {
            val gcf2Header = GcfChunkedCodec.readHeader(file)
            if (gcf2Header == null || gcf2Header.chunkCount <= 0) {
                return FileKeyDiagnostic(file, header, KeySourceKind.UNKNOWN_OR_CORRUPTED, errorDetail = "Невалидный заголовок GCF2")
            }
            var lastError: String? = null
            try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    for ((kind, key) in candidates) {
                        try {
                            val probe = GcfChunkedCodec.decryptChunk(raf, gcf2Header, 0, key)
                            Arrays.fill(probe, 0.toByte())
                            return FileKeyDiagnostic(
                                file = file,
                                headerInfo = header,
                                matchedKeyKind = kind,
                                plaintextBytesLength = gcf2Header.totalPlainSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            )
                        } catch (e: Exception) {
                            lastError = "${kind.name}: ${e.javaClass.simpleName}"
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            return FileKeyDiagnostic(
                file = file,
                headerInfo = header,
                matchedKeyKind = KeySourceKind.UNKNOWN_OR_CORRUPTED,
                errorDetail = if (candidates.isEmpty()) "Нет доступных ключей в сессии/KeyStore" else lastError
            )
        }

        val fileBytes = FileInputStream(file).use { it.readBytes() }
        val iv = ByteArray(GCM_IV_LEN)
        try {
            System.arraycopy(fileBytes, MAGIC_HEADER.size, iv, 0, GCM_IV_LEN)
            val ctOffset = MAGIC_HEADER.size + GCM_IV_LEN
            val ctLen = fileBytes.size - ctOffset
            var lastError: String? = null

            for ((kind, key) in candidates) {
                try {
                    val cipher = Cipher.getInstance(AES_GCM)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
                    val plain = cipher.doFinal(fileBytes, ctOffset, ctLen)
                    val len = plain.size
                    Arrays.fill(plain, 0.toByte())
                    return FileKeyDiagnostic(
                        file = file,
                        headerInfo = header,
                        matchedKeyKind = kind,
                        plaintextBytesLength = len
                    )
                } catch (e: Exception) {
                    lastError = "${kind.name}: ${e.javaClass.simpleName}"
                }
            }

            return FileKeyDiagnostic(
                file = file,
                headerInfo = header,
                matchedKeyKind = KeySourceKind.UNKNOWN_OR_CORRUPTED,
                errorDetail = if (candidates.isEmpty()) "Нет доступных ключей в сессии/KeyStore" else lastError
            )
        } finally {
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(fileBytes, 0.toByte())
        }
    }

    fun diagnoseVaultDirectory(
        context: Context,
        profile: VaultProfile = activeProfile
    ): List<FileKeyDiagnostic> {
        val files = listVaultFiles(context, profile)
        return files.map { diagnoseDecryptKeySource(context, it) }
    }

    /**
     * Strictly read-only encrypted backup: copies .gcf files and wrapped key/salt metadata
     * into backupDir without modifying or removing originals.
     */
    fun backupVaultDirectoryReadOnly(
        context: Context,
        profile: VaultProfile = activeProfile,
        backupDir: File
    ): BackupResult {
        if (!backupDir.exists()) backupDir.mkdirs()
        val srcFiles = listVaultFiles(context, profile)
        var copiedFiles = 0
        var totalBytes = 0L

        for (src in srcFiles) {
            val dst = File(backupDir, src.name)
            if (dst.canonicalPath == src.canonicalPath) {
                throw IllegalArgumentException("Backup path must not match original file: ${src.name}")
            }
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (dst.length() != src.length()) {
                throw IOException("Backup size mismatch for ${src.name}")
            }
            copiedFiles++
            totalBytes += dst.length()
        }

        var metaCount = 0
        for (metaName in listOf(profile.saltFile, profile.keyFile)) {
            val metaSrc = File(context.filesDir, metaName)
            if (metaSrc.exists()) {
                val metaDst = File(backupDir, metaName)
                FileInputStream(metaSrc).use { input ->
                    FileOutputStream(metaDst).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                metaCount++
            }
        }

        return BackupResult(
            backupDir = backupDir,
            copiedFilesCount = copiedFiles,
            copiedBytes = totalBytes,
            metadataFilesCount = metaCount
        )
    }

    /**
     * Recovers a readable GCF file STRICTLY into a separate output copy file.
     * Refuses to overwrite the source original. If reencryptTargetKey == null, writes decrypted copy;
     * otherwise re-encrypts into a brand-new GCF1 container copy with reencryptTargetKey.
     */
    fun recoverGcfFileToCopy(
        context: Context,
        sourceEncryptedFile: File,
        outputCopyFile: File,
        reencryptTargetKey: SecretKey? = null
    ): KeySourceKind {
        if (sourceEncryptedFile.canonicalPath == outputCopyFile.canonicalPath) {
            throw IllegalArgumentException("Отказ: запрещено перезаписывать оригинальный файл ${sourceEncryptedFile.name}")
        }
        outputCopyFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        val diag = diagnoseDecryptKeySource(context, sourceEncryptedFile)
        val matchedKey = when (diag.matchedKeyKind) {
            KeySourceKind.ACTIVE_MAIN_PIN -> activeMainKey
            KeySourceKind.ACTIVE_DECOY_PIN -> activeDecoyKey
            KeySourceKind.KEYSTORE_MAIN_FALLBACK -> getExistingHardwareKeystoreKeyOrNull("Ghost_Vault_MAIN")
            KeySourceKind.KEYSTORE_DECOY_FALLBACK -> getExistingHardwareKeystoreKeyOrNull("Ghost_Vault_DECOY")
            KeySourceKind.UNKNOWN_OR_CORRUPTED -> null
        } ?: throw AEADBadTagException("Не найден подходящий ключ для ${sourceEncryptedFile.name}: ${diag.errorDetail}")

        if (isGcf2File(sourceEncryptedFile)) {
            if (reencryptTargetKey == null) {
                FileOutputStream(outputCopyFile).use { fos ->
                    GcfChunkedCodec.decryptToStream(sourceEncryptedFile, matchedKey, fos)
                    fos.fd.sync()
                }
            } else {
                val stageDir = getVideoStageDir(context)
                val tempPlain = File(stageDir, "recover_${UUID.randomUUID().toString().take(8)}.tmp")
                try {
                    FileOutputStream(tempPlain).use { fos ->
                        GcfChunkedCodec.decryptToStream(sourceEncryptedFile, matchedKey, fos)
                        fos.fd.sync()
                    }
                    GcfChunkedCodec.encryptFileAtomically(tempPlain, outputCopyFile, reencryptTargetKey)
                } finally {
                    shredFile(tempPlain)
                }
            }
            return diag.matchedKeyKind
        }

        val fileBytes = FileInputStream(sourceEncryptedFile).use { it.readBytes() }
        val iv = ByteArray(GCM_IV_LEN)
        var plain: ByteArray? = null
        try {
            System.arraycopy(fileBytes, MAGIC_HEADER.size, iv, 0, GCM_IV_LEN)
            val ctOffset = MAGIC_HEADER.size + GCM_IV_LEN
            val ctLen = fileBytes.size - ctOffset
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, matchedKey, GCMParameterSpec(GCM_TAG_LEN, iv))
            plain = cipher.doFinal(fileBytes, ctOffset, ctLen)

            if (reencryptTargetKey == null) {
                FileOutputStream(outputCopyFile).use { fos ->
                    fos.write(plain)
                    fos.fd.sync()
                }
            } else {
                val newIv = ByteArray(GCM_IV_LEN)
                secureRandom.nextBytes(newIv)
                val enc = Cipher.getInstance(AES_GCM)
                enc.init(Cipher.ENCRYPT_MODE, reencryptTargetKey, GCMParameterSpec(GCM_TAG_LEN, newIv))
                val outFos = FileOutputStream(outputCopyFile)
                try {
                    outFos.write(MAGIC_HEADER)
                    outFos.write(newIv)
                    CipherOutputStream(outFos, enc).use { cos ->
                        cos.write(plain)
                        cos.flush()
                    }
                } finally {
                    Arrays.fill(newIv, 0.toByte())
                }
            }
            return diag.matchedKeyKind
        } finally {
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(fileBytes, 0.toByte())
            plain?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

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

    private fun shredProfile(context: Context, profile: VaultProfile): Int {
        val salt = File(context.filesDir, profile.saltFile)
        val key = File(context.filesDir, profile.keyFile)
        shredFile(salt)
        shredFile(key)

        val files = listVaultFiles(context, profile)
        for (f in files) {
            shredFile(f)
        }
        val idxFile = File(profile.getDirectory(context), VaultIndexEngine.INDEX_FILE_NAME)
        if (idxFile.exists()) shredFile(idxFile)
        val idxTmp = File(profile.getDirectory(context), "${VaultIndexEngine.INDEX_FILE_NAME}.tmp")
        if (idxTmp.exists()) shredFile(idxTmp)
        return files.size
    }

    /**
     * True Crypto-Shredding:
     * Destroys MAIN, DECOY, DURESS keys, RAM-Only shots, Keystore aliases, and disk files.
     */
    fun cryptoShred(context: Context): Int {
        var count = clearRamShots()
        activeMainKey = null
        activeDecoyKey = null

        shredFile(File(context.filesDir, DURESS_SALT_FILE))
        shredFile(File(context.filesDir, DURESS_KEY_FILE))
        shredEphemeralShareFiles(context)
        shredOrphanedVideoTemps(context)

        for (profile in VaultProfile.values()) {
            count += shredProfile(context, profile)
        }

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

    /**
     * Dead-Man Switch: wipes vault if user has been inactive for configured N days.
     */
    fun checkDeadManSwitch(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val days = ZverPrefs.getDeadManDays(context)
        if (days <= 0 || !isConfigured(context)) return false
        val lastMs = ZverPrefs.getLastActivityEpochMs(context)
        if (lastMs <= 0L) {
            ZverPrefs.touchActivity(context, nowMs)
            return false
        }
        val thresholdMs = days * 86_400_000L
        if (nowMs - lastMs > thresholdMs) {
            cryptoShred(context)
            MonoVaultEngine.cryptoShredMonolith(context)
            ZverPrefs.resetFailedPinAttempts(context)
            return true
        }
        return false
    }

    fun listVaultFiles(context: Context, profile: VaultProfile = activeProfile): List<File> {
        val dir = profile.getDirectory(context)
        return dir.listFiles { _, name -> name.endsWith(".gcf") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun totalVaultItemsCount(context: Context): Int {
        return listVaultFiles(context).size + listRamShots().size
    }

    fun lockSession() {
        activeMainKey = null
        activeDecoyKey = null
        activeProfile = VaultProfile.MAIN
    }

    fun detectMediaExtension(bytes: ByteArray, isVideo: Boolean): String {
        if (isVideo) return "mp4"
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "webp"
        }
        return "jpg"
    }

    fun decryptToEphemeralFile(context: Context, sourceFile: File): File {
        if (!sourceFile.exists() || fileLengthInvalid(sourceFile)) {
            throw IOException("Invalid or corrupted GCF file")
        }

        val shareDir = File(context.cacheDir, "ephemeral_shared")
        if (!shareDir.exists()) shareDir.mkdirs()
        val isVideo = isVideoFile(sourceFile)
        val ext = if (isVideo) "mp4" else "bin"
        val tmpFile = File(shareDir, "ephemeral_${UUID.randomUUID().toString().take(8)}.$ext")

        if (isGcf2File(sourceFile)) {
            try {
                FileOutputStream(tmpFile).use { fos ->
                    GcfChunkedCodec.decryptToStream(sourceFile, getActiveVaultKey(context), fos)
                    fos.fd.sync()
                }
                if (!isVideo) {
                    val headPeek = ByteArray(12)
                    val read = FileInputStream(tmpFile).use { it.read(headPeek) }
                    if (read >= 12) {
                        val realExt = detectMediaExtension(headPeek, isVideo = false)
                        val renamed = File(shareDir, tmpFile.nameWithoutExtension + "." + realExt)
                        if (tmpFile.renameTo(renamed)) {
                            return renamed
                        }
                    }
                }
                return tmpFile
            } catch (e: Exception) {
                shredFile(tmpFile)
                throw e
            }
        }

        var headPeek = ByteArray(0)
        FileInputStream(sourceFile).use { fis ->
            val header = ByteArray(MAGIC_HEADER.size)
            if (fis.read(header) != MAGIC_HEADER.size || !header.contentEquals(MAGIC_HEADER)) {
                throw IOException("Invalid GCF magic header")
            }

            val iv = ByteArray(GCM_IV_LEN)
            if (fis.read(iv) != GCM_IV_LEN) {
                throw IOException("Missing GCF IV")
            }

            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, getActiveVaultKey(context), GCMParameterSpec(GCM_TAG_LEN, iv))
            Arrays.fill(iv, 0.toByte())

            try {
                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(tmpFile).use { fos ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        var capturedHead = false
                        while (cis.read(buffer).also { read = it } != -1) {
                            if (!capturedHead && read >= 12) {
                                headPeek = buffer.copyOfRange(0, 12)
                                capturedHead = true
                            }
                            fos.write(buffer, 0, read)
                        }
                        Arrays.fill(buffer, 0.toByte())
                        fos.fd.sync()
                    }
                }
            } catch (e: Exception) {
                shredFile(tmpFile)
                throw e
            }
        }

        if (!isVideo) {
            val realExt = detectMediaExtension(headPeek, isVideo = false)
            val renamed = File(shareDir, tmpFile.nameWithoutExtension + "." + realExt)
            if (tmpFile.renameTo(renamed)) {
                return renamed
            }
        }
        return tmpFile
    }

    private fun fileLengthInvalid(file: File): Boolean {
        if (isGcf2File(file)) {
            return file.length() < (GcfChunkedCodec.HEADER_SIZE + GcfChunkedCodec.GCM_TAG_BYTES)
        }
        return file.length() < (MAGIC_HEADER.size + GCM_IV_LEN + 16)
    }

    fun createEphemeralShareFile(context: Context, sourceFile: File): File {
        return decryptToEphemeralFile(context, sourceFile)
    }

    fun createEphemeralShareBytes(context: Context, plainBytes: ByteArray, extension: String = "webp"): File {
        val shareDir = File(context.cacheDir, "ephemeral_shared")
        if (!shareDir.exists()) shareDir.mkdirs()
        val tmpFile = File(shareDir, "ephemeral_${UUID.randomUUID().toString().take(8)}.$extension")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(plainBytes)
            fos.fd.sync()
        }
        return tmpFile
    }

    /**
     * ProofMode-lite Detached Cryptographic Integrity Proof (0% PII, 0% device tags).
     */
    fun generateDetachedProofJson(mediaBytes: ByteArray, audit: ExifStripper.AuditReport): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(mediaBytes)
        val sha256Hex = digest.joinToString("") { "%02x".format(it) }
        val signatureB64 = try {
            Base64.encodeToString(MonoVaultEngine.signData(digest), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
        val pubKeyB64 = try {
            MonoVaultEngine.getSigningPublicKeyBase64()
        } catch (_: Exception) {
            ""
        }
        return JSONObject().apply {
            put("schema", "zver-proof-v1")
            put("format", audit.format)
            put("bytes", audit.totalBytes)
            put("metadata_bytes", audit.metadataBytes)
            put("clean", audit.isClean)
            put("sha256", sha256Hex)
            put("ecdsa_sha256_sig_b64", signatureB64)
            put("ecdsa_pub_spki_b64", pubKeyB64)
        }.toString(2)
    }

    data class ProofVerificationResult(
        val valid: Boolean,
        val sha256Matches: Boolean,
        val signatureValid: Boolean,
        val expectedSha256: String,
        val actualSha256: String,
        val detailRu: String
    )

    fun verifyDetachedProofJson(
        mediaBytes: ByteArray,
        proofJson: String,
        verifySignatureFn: (ByteArray, ByteArray) -> Boolean = { d, s -> MonoVaultEngine.verifySignature(d, s) }
    ): ProofVerificationResult {
        val digest = MessageDigest.getInstance("SHA-256").digest(mediaBytes)
        val actualSha256 = digest.joinToString("") { "%02x".format(it) }
        val obj = try {
            JSONObject(proofJson)
        } catch (_: Exception) {
            return ProofVerificationResult(false, false, false, "", actualSha256, "Неверный формат proof JSON")
        }
        val expectedSha256 = obj.optString("sha256", "")
        val sha256Matches = expectedSha256.isNotEmpty() && actualSha256.equals(expectedSha256, ignoreCase = true)

        val sigB64 = obj.optString("ecdsa_sha256_sig_b64", "")
        val sigBytes = try {
            if (sigB64.isBlank()) ByteArray(0) else Base64.decode(sigB64, Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }

        val signatureValid = if (sigBytes.isNotEmpty()) {
            try {
                verifySignatureFn(digest, sigBytes)
            } catch (_: Exception) {
                false
            }
        } else false

        val valid = sha256Matches && signatureValid
        val detail = when {
            valid -> "Целостность и ECDSA подпись подтверждены"
            !sha256Matches -> "Хэш SHA-256 файла не совпал с proof-манифестом"
            !signatureValid -> "Подпись ECDSA не прошла верификацию"
            else -> "Неверная структура proof"
        }

        return ProofVerificationResult(valid, sha256Matches, signatureValid, expectedSha256, actualSha256, detail)
    }

    fun generateVaultChainManifestJson(context: Context, profile: VaultProfile = activeProfile): String {
        val dir = profile.getDirectory(context)
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".gcf") }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: emptyList()

        var prevChainHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val itemsArray = org.json.JSONArray()
        val md = MessageDigest.getInstance("SHA-256")

        for (file in files) {
            val fileBytes = try { file.readBytes() } catch (_: Exception) { ByteArray(0) }
            val fileHash = md.digest(fileBytes).joinToString("") { "%02x".format(it) }
            md.reset()

            val combined = (prevChainHash + fileHash + file.name).toByteArray(Charsets.UTF_8)
            val currentChainHash = md.digest(combined).joinToString("") { "%02x".format(it) }
            md.reset()

            itemsArray.put(JSONObject().apply {
                put("file_name", file.name)
                put("last_modified_ms", file.lastModified())
                put("file_size_bytes", file.length())
                put("file_sha256", fileHash)
                put("chain_hash", currentChainHash)
            })
            prevChainHash = currentChainHash
        }

        val manifestObj = JSONObject().apply {
            put("schema", "zver-chain-manifest-v1")
            put("profile", profile.name)
            put("total_files", files.size)
            put("chain_head_sha256", prevChainHash)
            put("timestamp_ms", System.currentTimeMillis())
            put("items", itemsArray)
        }

        val manifestBytes = manifestObj.toString(2).toByteArray(Charsets.UTF_8)
        val manifestDigest = md.digest(manifestBytes)
        val sigB64 = try {
            Base64.encodeToString(MonoVaultEngine.signData(manifestDigest), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
        val pubKeyB64 = try {
            MonoVaultEngine.getSigningPublicKeyBase64()
        } catch (_: Exception) {
            ""
        }
        manifestObj.put("manifest_ecdsa_sig_b64", sigB64)
        manifestObj.put("ecdsa_pub_spki_b64", pubKeyB64)
        return manifestObj.toString(2)
    }

    fun getVideoStageDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, "video_stage")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    fun shredOrphanedVaultTempFiles(context: Context, minAgeMs: Long = 0L): Int {
        var count = 0
        val now = System.currentTimeMillis()
        for (profile in VaultProfile.entries) {
            val dir = File(context.filesDir, profile.dirName)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { f ->
                val isOrphanTemp = f.name.endsWith(".tmp") || (f.name.endsWith(".gcf") && f.length() == 0L)
                val oldEnough = minAgeMs <= 0L || (now - f.lastModified()) >= minAgeMs
                if (isOrphanTemp && oldEnough && shredFile(f)) {
                    count++
                }
            }
        }
        return count
    }

    fun shredOrphanedVideoTemps(context: Context, minAgeMs: Long = 0L): Int {
        var count = 0
        val now = System.currentTimeMillis()
        val stageDir = File(context.noBackupFilesDir, "video_stage")
        if (stageDir.exists()) {
            stageDir.listFiles()?.forEach { f ->
                val oldEnough = minAgeMs <= 0L || (now - f.lastModified()) >= minAgeMs
                if (f.name != ".nomedia" && oldEnough && shredFile(f)) count++
            }
        }
        context.cacheDir.listFiles { _, name ->
            (name.startsWith("raw_video_") || name.startsWith("clean_video_")) && name.endsWith(".mp4")
        }?.forEach { f ->
            val oldEnough = minAgeMs <= 0L || (now - f.lastModified()) >= minAgeMs
            if (oldEnough && shredFile(f)) count++
        }
        return count
    }

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
