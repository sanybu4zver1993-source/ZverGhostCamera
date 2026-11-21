package com.example.crypto

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * ZverBackupEngine ("ZVB1"):
 * Portable, password-encrypted (PBKDF2WithHmacSHA256 + streaming chunked AES-256-GCM)
 * vault archive for migrating MAIN/DECOY vaults across debug/release builds or devices.
 *
 * - Streaming: handles large videos in 256 KB frames (< 1 MB RAM).
 * - Does NOT alter or delete original vault files during export.
 * - Imports and re-encrypts items under the target device/profile active key (GCF2 for video/>1MB, GCF1 for photo).
 */
object ZverBackupEngine {
    private val MAGIC_ZVB1 = byteArrayOf(0x5A, 0x56, 0x42, 0x31) // "ZVB1"
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 25 // 4 magic + 1 ver + 4 iters + 16 salt
    const val DEFAULT_PBKDF2_ITERATIONS = 300_000
    private const val SALT_LEN = 16
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val CHUNK_SIZE = 256 * 1024
    private const val AES_GCM = "AES/GCM/NoPadding"

    private const val FRAME_MANIFEST: Byte = 0x01
    private const val FRAME_ENTRY_HEADER: Byte = 0x02
    private const val FRAME_ENTRY_CHUNK: Byte = 0x03
    private const val FRAME_EOF: Byte = 0x7F

    private val secureRandom = SecureRandom()

    data class BackupExportResult(
        val fileCount: Int,
        val totalPlainBytes: Long,
        val archiveBytes: Long
    )

    data class BackupImportResult(
        val importedFilesCount: Int,
        val importedMetaCount: Int,
        val totalPlainBytes: Long,
        val skippedExpiredCount: Int
    )

    private fun deriveBackupKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        val key = SecretKeySpec(raw, "AES")
        Arrays.fill(raw, 0.toByte())
        return key
    }

    private fun buildFrameAad(headerBytes: ByteArray, frameType: Byte, frameSeq: Int): ByteArray {
        return ByteBuffer.allocate(headerBytes.size + 5)
            .put(headerBytes)
            .put(frameType)
            .putInt(frameSeq)
            .array()
    }

    private fun writeEncryptedFrame(
        dos: DataOutputStream,
        headerBytes: ByteArray,
        frameType: Byte,
        frameSeq: Int,
        key: SecretKey,
        plain: ByteArray,
        plainLen: Int = plain.size
    ): Int {
        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_GCM)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(buildFrameAad(headerBytes, frameType, frameSeq))
            val encrypted = cipher.doFinal(plain, 0, plainLen)
            dos.writeByte(frameType.toInt())
            dos.write(iv)
            dos.writeInt(encrypted.size)
            dos.write(encrypted)
            return 1 + GCM_IV_LEN + 4 + encrypted.size
        } finally {
            Arrays.fill(iv, 0.toByte())
        }
    }

    private data class DecryptedFrame(
        val frameType: Byte,
        val payload: ByteArray
    )

    private fun readDecryptedFrame(
        dis: DataInputStream,
        headerBytes: ByteArray,
        expectedSeq: Int,
        key: SecretKey
    ): DecryptedFrame {
        val frameType = dis.readByte()
        val iv = ByteArray(GCM_IV_LEN)
        dis.readFully(iv)
        val cipherLen = dis.readInt()
        if (cipherLen < 16 || cipherLen > CHUNK_SIZE + 64 * 1024) {
            throw IOException("Некорректный размер фрейма ZVB1: $cipherLen")
        }
        val encrypted = ByteArray(cipherLen)
        dis.readFully(encrypted)
        val cipher = Cipher.getInstance(AES_GCM)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(buildFrameAad(headerBytes, frameType, expectedSeq))
            val plain = cipher.doFinal(encrypted)
            DecryptedFrame(frameType, plain)
        } catch (e: AEADBadTagException) {
            throw AEADBadTagException("Неверный мастер-пароль или повреждён архив ZVB1").apply { initCause(e) }
        } finally {
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(encrypted, 0.toByte())
        }
    }

    fun exportProfileBackup(
        context: Context,
        profile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        masterPassword: CharArray,
        outStream: OutputStream,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): BackupExportResult {
        if (masterPassword.isEmpty()) {
            throw IllegalArgumentException("Мастер-пароль не может быть пустым")
        }

        val prevProfile = GhostCryptoVault.activeProfile
        GhostCryptoVault.activeProfile = profile
        try {
            val files = GhostCryptoVault.listVaultFiles(context, profile)
            val indexMap = VaultIndexEngine.loadIndex(context, profile)

            val salt = ByteArray(SALT_LEN)
            secureRandom.nextBytes(salt)
            val backupKey = deriveBackupKey(masterPassword, salt, iterations)

            val headerBytes = ByteBuffer.allocate(HEADER_SIZE)
                .put(MAGIC_ZVB1)
                .put(VERSION)
                .putInt(iterations)
                .put(salt)
                .array()

            val dos = DataOutputStream(BufferedOutputStream(outStream, 64 * 1024))
            var writtenArchiveBytes = HEADER_SIZE.toLong()
            var totalPlainBytes = 0L
            var frameSeq = 0

            dos.write(headerBytes)

            val manifestJson = JSONObject().apply {
                put("schema", "zver-backup-v1")
                put("profile", profile.name)
                put("created_at_ms", System.currentTimeMillis())
                put("file_count", files.size)
                put("index_json", VaultIndexEngine.serializeIndexJson(indexMap))
            }.toString().toByteArray(Charsets.UTF_8)

            try {
                writtenArchiveBytes += writeEncryptedFrame(dos, headerBytes, FRAME_MANIFEST, frameSeq++, backupKey, manifestJson)
            } finally {
                Arrays.fill(manifestJson, 0.toByte())
            }

            val vaultKey = GhostCryptoVault.getActiveVaultKey(context)
            files.forEachIndexed { index, file ->
                onProgress?.invoke(index + 1, files.size)
                val isVideo = GhostCryptoVault.isVideoFile(file)

                if (GhostCryptoVault.isGcf2File(file)) {
                    val gcf2Header = GcfChunkedCodec.readHeader(file)
                        ?: throw IOException("Повреждён GCF2: ${file.name}")
                    val entryMetaBytes = JSONObject().apply {
                        put("name", file.name)
                        put("last_modified_ms", file.lastModified())
                        put("is_video", isVideo)
                        put("plain_size", gcf2Header.totalPlainSize)
                    }.toString().toByteArray(Charsets.UTF_8)
                    try {
                        writtenArchiveBytes += writeEncryptedFrame(dos, headerBytes, FRAME_ENTRY_HEADER, frameSeq++, backupKey, entryMetaBytes)
                    } finally {
                        Arrays.fill(entryMetaBytes, 0.toByte())
                    }

                    RandomAccessFile(file, "r").use { raf ->
                        for (c in 0 until gcf2Header.chunkCount) {
                            val plainChunk = GcfChunkedCodec.decryptChunk(raf, gcf2Header, c, vaultKey)
                            try {
                                writtenArchiveBytes += writeEncryptedFrame(
                                    dos, headerBytes, FRAME_ENTRY_CHUNK, frameSeq++, backupKey, plainChunk, plainChunk.size
                                )
                                totalPlainBytes += plainChunk.size
                            } finally {
                                Arrays.fill(plainChunk, 0.toByte())
                            }
                        }
                    }
                } else {
                    val plain = GhostCryptoVault.decryptToRam(context, file)
                    try {
                        val entryMetaBytes = JSONObject().apply {
                            put("name", file.name)
                            put("last_modified_ms", file.lastModified())
                            put("is_video", isVideo)
                            put("plain_size", plain.size.toLong())
                        }.toString().toByteArray(Charsets.UTF_8)
                        try {
                            writtenArchiveBytes += writeEncryptedFrame(dos, headerBytes, FRAME_ENTRY_HEADER, frameSeq++, backupKey, entryMetaBytes)
                        } finally {
                            Arrays.fill(entryMetaBytes, 0.toByte())
                        }

                        var off = 0
                        if (plain.isEmpty()) {
                            writtenArchiveBytes += writeEncryptedFrame(dos, headerBytes, FRAME_ENTRY_CHUNK, frameSeq++, backupKey, ByteArray(0), 0)
                        } else {
                            val chunkBuf = ByteArray(CHUNK_SIZE)
                            try {
                                while (off < plain.size) {
                                    val len = minOf(CHUNK_SIZE, plain.size - off)
                                    System.arraycopy(plain, off, chunkBuf, 0, len)
                                    writtenArchiveBytes += writeEncryptedFrame(
                                        dos, headerBytes, FRAME_ENTRY_CHUNK, frameSeq++, backupKey, chunkBuf, len
                                    )
                                    off += len
                                    totalPlainBytes += len
                                }
                            } finally {
                                Arrays.fill(chunkBuf, 0.toByte())
                            }
                        }
                    } finally {
                        Arrays.fill(plain, 0.toByte())
                    }
                }
            }

            val eofBytes = "ZVB1_EOF".toByteArray(Charsets.UTF_8)
            writtenArchiveBytes += writeEncryptedFrame(dos, headerBytes, FRAME_EOF, frameSeq, backupKey, eofBytes)
            dos.flush()

            return BackupExportResult(
                fileCount = files.size,
                totalPlainBytes = totalPlainBytes,
                archiveBytes = writtenArchiveBytes
            )
        } finally {
            GhostCryptoVault.activeProfile = prevProfile
            Arrays.fill(masterPassword, '\u0000')
        }
    }

    fun importProfileBackup(
        context: Context,
        targetProfile: GhostCryptoVault.VaultProfile = GhostCryptoVault.activeProfile,
        masterPassword: CharArray,
        inStream: InputStream,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): BackupImportResult {
        if (masterPassword.isEmpty()) {
            throw IllegalArgumentException("Мастер-пароль не может быть пустым")
        }

        val dis = DataInputStream(BufferedInputStream(inStream, 64 * 1024))
        val headerBytes = ByteArray(HEADER_SIZE)
        try {
            dis.readFully(headerBytes)
        } catch (e: EOFException) {
            Arrays.fill(masterPassword, '\u0000')
            throw IOException("Файл бэкапа слишком мал или повреждён", e)
        }

        val bb = ByteBuffer.wrap(headerBytes)
        val magic = ByteArray(4)
        bb.get(magic)
        if (!magic.contentEquals(MAGIC_ZVB1)) {
            Arrays.fill(masterPassword, '\u0000')
            throw IOException("Неверный формат файла: ожидается ZVB1")
        }
        val version = bb.get()
        if (version != VERSION) {
            Arrays.fill(masterPassword, '\u0000')
            throw IOException("Неподдерживаемая версия бэкапа ZVB: $version")
        }
        val iterations = bb.int
        if (iterations < 1_000 || iterations > 5_000_000) {
            Arrays.fill(masterPassword, '\u0000')
            throw IOException("Некорректное число итераций PBKDF2: $iterations")
        }
        val salt = ByteArray(SALT_LEN)
        bb.get(salt)

        val backupKey = deriveBackupKey(masterPassword, salt, iterations)
        Arrays.fill(masterPassword, '\u0000')

        val prevProfile = GhostCryptoVault.activeProfile
        GhostCryptoVault.activeProfile = targetProfile
        try {
            var frameSeq = 0
            val manifestFrame = readDecryptedFrame(dis, headerBytes, frameSeq++, backupKey)
            if (manifestFrame.frameType != FRAME_MANIFEST) {
                throw IOException("Отсутствует манифест ZVB1")
            }
            val manifestJson = try {
                JSONObject(String(manifestFrame.payload, Charsets.UTF_8))
            } finally {
                Arrays.fill(manifestFrame.payload, 0.toByte())
            }

            val fileCount = manifestJson.optInt("file_count", 0)
            val archivedIndex = try {
                val idxJson = manifestJson.optString("index_json", "")
                if (idxJson.isNotEmpty()) VaultIndexEngine.parseIndexJson(idxJson) else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val targetDir = targetProfile.getDirectory(context)
            val mergedIndex = VaultIndexEngine.loadIndex(context, targetProfile).toMutableMap()
            val nowMs = System.currentTimeMillis()

            var importedFiles = 0
            var importedMeta = 0
            var skippedExpired = 0
            var totalPlainBytes = 0L

            var currentEntry: JSONObject? = null
            var stageTempFile: File? = null
            var stageOut: FileOutputStream? = null
            var smallBuffer: ByteArrayOutputStream? = null
            var currentWrittenForEntry = 0L

            fun finalizeCurrentEntry() {
                val entry = currentEntry ?: return
                val origName = entry.optString("name", "restored_${System.currentTimeMillis()}.gcf")
                val isVideo = entry.optBoolean("is_video", false)
                val lastMod = entry.optLong("last_modified_ms", nowMs)

                val meta = archivedIndex[origName]
                if (meta != null && meta.isExpired(nowMs)) {
                    stageOut?.close()
                    stageTempFile?.let { GhostCryptoVault.shredFile(it) }
                    stageOut = null
                    stageTempFile = null
                    smallBuffer = null
                    currentEntry = null
                    skippedExpired++
                    return
                }

                var finalName = origName
                if (!finalName.endsWith(".gcf")) finalName = "$finalName.gcf"
                var destFile = File(targetDir, finalName)
                if (destFile.exists()) {
                    val base = finalName.removeSuffix(".gcf")
                    finalName = "${base}_imp_${UUID.randomUUID().toString().take(4)}.gcf"
                    destFile = File(targetDir, finalName)
                }

                val targetKey = GhostCryptoVault.getActiveVaultKey(context)
                if (stageTempFile != null) {
                    stageOut?.flush()
                    stageOut?.fd?.sync()
                    stageOut?.close()
                    stageOut = null
                    val tmp = stageTempFile!!
                    stageTempFile = null
                    try {
                        GcfChunkedCodec.encryptFileAtomically(tmp, destFile, targetKey)
                    } finally {
                        GhostCryptoVault.shredFile(tmp)
                    }
                } else {
                    val plain = smallBuffer?.toByteArray() ?: ByteArray(0)
                    smallBuffer = null
                    try {
                        if (isVideo || plain.size > 1024 * 1024) {
                            GcfChunkedCodec.encryptBytesAtomically(plain, destFile, targetKey)
                        } else {
                            // Preserve exact original prefix semantics & store in GCF1
                            val tempOut = File(targetDir, "$finalName.tmp")
                            val iv = ByteArray(12)
                            secureRandom.nextBytes(iv)
                            val cipher = Cipher.getInstance(AES_GCM)
                            cipher.init(Cipher.ENCRYPT_MODE, targetKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                            try {
                                FileOutputStream(tempOut).use { fos ->
                                    fos.write(byteArrayOf(0x47, 0x43, 0x46, 0x31))
                                    fos.write(iv)
                                    javax.crypto.CipherOutputStream(fos, cipher).use { cos ->
                                        cos.write(plain)
                                        cos.flush()
                                    }
                                }
                                if (!tempOut.renameTo(destFile)) {
                                    tempOut.copyTo(destFile, overwrite = true)
                                    GhostCryptoVault.shredFile(tempOut)
                                }
                            } finally {
                                Arrays.fill(iv, 0.toByte())
                            }
                        }
                    } finally {
                        Arrays.fill(plain, 0.toByte())
                    }
                }

                try {
                    destFile.setLastModified(lastMod)
                } catch (_: Exception) {}

                if (meta != null) {
                    mergedIndex[finalName] = meta.copy(fileName = finalName)
                    importedMeta++
                }
                importedFiles++
                onProgress?.invoke(importedFiles, maxOf(importedFiles, fileCount))
                currentEntry = null
                currentWrittenForEntry = 0L
            }

            try {
                while (true) {
                    val frame = readDecryptedFrame(dis, headerBytes, frameSeq++, backupKey)
                    when (frame.frameType) {
                        FRAME_ENTRY_HEADER -> {
                            finalizeCurrentEntry()
                            val json = try {
                                JSONObject(String(frame.payload, Charsets.UTF_8))
                            } finally {
                                Arrays.fill(frame.payload, 0.toByte())
                            }
                            currentEntry = json
                            currentWrittenForEntry = 0L
                            val isVideo = json.optBoolean("is_video", false)
                            val plainSize = json.optLong("plain_size", 0L)
                            if (isVideo || plainSize > 1024L * 1024L) {
                                val stageDir = GhostCryptoVault.getVideoStageDir(context)
                                val tmp = File(stageDir, "zvb_imp_${UUID.randomUUID().toString().take(8)}.tmp")
                                stageTempFile = tmp
                                stageOut = FileOutputStream(tmp)
                            } else {
                                smallBuffer = ByteArrayOutputStream(plainSize.coerceIn(0L, (2 * 1024 * 1024).toLong()).toInt())
                            }
                        }
                        FRAME_ENTRY_CHUNK -> {
                            if (currentEntry == null) {
                                Arrays.fill(frame.payload, 0.toByte())
                                throw IOException("Повреждена последовательность фреймов ZVB1")
                            }
                            try {
                                stageOut?.write(frame.payload)
                                smallBuffer?.write(frame.payload)
                                currentWrittenForEntry += frame.payload.size
                                totalPlainBytes += frame.payload.size
                            } finally {
                                Arrays.fill(frame.payload, 0.toByte())
                            }
                        }
                        FRAME_EOF -> {
                            Arrays.fill(frame.payload, 0.toByte())
                            finalizeCurrentEntry()
                            break
                        }
                        else -> {
                            Arrays.fill(frame.payload, 0.toByte())
                            throw IOException("Неизвестный тип фрейма ZVB1: ${frame.frameType}")
                        }
                    }
                }
            } catch (e: Exception) {
                try { stageOut?.close() } catch (_: Exception) {}
                stageTempFile?.let { GhostCryptoVault.shredFile(it) }
                throw e
            }

            if (mergedIndex.isNotEmpty()) {
                VaultIndexEngine.saveIndex(context, targetProfile, mergedIndex)
            }

            return BackupImportResult(
                importedFilesCount = importedFiles,
                importedMetaCount = importedMeta,
                totalPlainBytes = totalPlainBytes,
                skippedExpiredCount = skippedExpired
            )
        } finally {
            GhostCryptoVault.activeProfile = prevProfile
        }
    }
}
