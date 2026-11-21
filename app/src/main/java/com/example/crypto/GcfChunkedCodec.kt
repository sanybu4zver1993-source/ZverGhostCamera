package com.example.crypto

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GcfChunkedCodec ("GCF2"):
 * Seekable chunked AES-256-GCM container designed for 4 GB RAM devices (Helio G85).
 * - Fixed 33-byte header: "GCF2" (4) + version (1) + chunkSize (4) + baseNonce (12) + totalPlainSize (8) + chunkCount (4)
 * - Every chunk i uses deterministic nonce (baseNonce XOR i) + AAD("GCF2", i, chunkPlainLen)
 * - Enables O(1) random-access chunk decryption via EncryptedMediaDataSource (< 600 KB RAM).
 */
object GcfChunkedCodec {
    val MAGIC_GCF2 = byteArrayOf(0x47, 0x43, 0x46, 0x32) // "GCF2"
    private const val VERSION_V2: Byte = 2
    const val HEADER_SIZE = 33
    const val DEFAULT_CHUNK_SIZE = 256 * 1024 // 256 KB
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN_BITS = 128
    const val GCM_TAG_BYTES = 16
    private const val AES_GCM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    data class Gcf2Header(
        val version: Int,
        val chunkSize: Int,
        val baseNonce: ByteArray,
        val totalPlainSize: Long,
        val chunkCount: Int
    ) {
        fun chunkPlainLength(chunkIndex: Int): Int {
            if (totalPlainSize == 0L || chunkIndex < 0 || chunkIndex >= chunkCount) return 0
            return if (chunkIndex == chunkCount - 1) {
                val rem = totalPlainSize - chunkIndex.toLong() * chunkSize.toLong()
                rem.toInt()
            } else {
                chunkSize
            }
        }

        fun chunkFileOffset(chunkIndex: Int): Long {
            return HEADER_SIZE.toLong() + chunkIndex.toLong() * (chunkSize.toLong() + GCM_TAG_BYTES)
        }
    }

    fun isGcf2HeaderBytes(bytes: ByteArray): Boolean {
        return bytes.size >= MAGIC_GCF2.size &&
            bytes[0] == MAGIC_GCF2[0] &&
            bytes[1] == MAGIC_GCF2[1] &&
            bytes[2] == MAGIC_GCF2[2] &&
            bytes[3] == MAGIC_GCF2[3]
    }

    fun readHeader(file: File): Gcf2Header? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        val buf = ByteArray(HEADER_SIZE)
        FileInputStream(file).use { fis ->
            val read = fis.read(buf)
            if (read < HEADER_SIZE) return null
        }
        return parseHeader(buf)
    }

    fun parseHeader(buf: ByteArray): Gcf2Header? {
        if (buf.size < HEADER_SIZE || !isGcf2HeaderBytes(buf)) return null
        val bb = ByteBuffer.wrap(buf)
        bb.position(4)
        val ver = bb.get().toInt() and 0xFF
        if (ver != VERSION_V2.toInt()) return null
        val chunkSize = bb.int
        if (chunkSize < 1024 || chunkSize > 4 * 1024 * 1024) return null
        val baseNonce = ByteArray(GCM_IV_LEN)
        bb.get(baseNonce)
        val totalPlainSize = bb.long
        val chunkCount = bb.int
        if (totalPlainSize < 0L || chunkCount < 0) return null
        return Gcf2Header(ver, chunkSize, baseNonce, totalPlainSize, chunkCount)
    }

    private fun buildChunkIv(baseNonce: ByteArray, chunkIndex: Int): ByteArray {
        val iv = baseNonce.clone()
        iv[8] = (iv[8].toInt() xor ((chunkIndex ushr 24) and 0xFF)).toByte()
        iv[9] = (iv[9].toInt() xor ((chunkIndex ushr 16) and 0xFF)).toByte()
        iv[10] = (iv[10].toInt() xor ((chunkIndex ushr 8) and 0xFF)).toByte()
        iv[11] = (iv[11].toInt() xor (chunkIndex and 0xFF)).toByte()
        return iv
    }

    private fun buildChunkAad(chunkIndex: Int, chunkPlainLen: Int): ByteArray {
        return ByteBuffer.allocate(12)
            .put(MAGIC_GCF2)
            .putInt(chunkIndex)
            .putInt(chunkPlainLen)
            .array()
    }

    fun encryptFileAtomically(
        sourceFile: File,
        targetFile: File,
        key: SecretKey,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): Gcf2Header {
        if (!sourceFile.exists()) throw IOException("Source file missing: ${sourceFile.name}")
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val totalSize = sourceFile.length()
        val chunkCount = if (totalSize == 0L) 1 else ((totalSize + chunkSize - 1) / chunkSize).toInt()
        val baseNonce = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(baseNonce)
        val header = Gcf2Header(VERSION_V2.toInt(), chunkSize, baseNonce, totalSize, chunkCount)

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        val plainBuf = ByteArray(chunkSize)
        try {
            FileOutputStream(tempFile).use { fos ->
                writeHeader(fos, header)
                FileInputStream(sourceFile).use { fis ->
                    if (totalSize == 0L) {
                        val encryptedChunk = encryptSingleChunk(ByteArray(0), 0, 0, header.baseNonce, key)
                        fos.write(encryptedChunk)
                    } else {
                        var chunkIdx = 0
                        while (chunkIdx < chunkCount) {
                            var filled = 0
                            while (filled < chunkSize) {
                                val r = fis.read(plainBuf, filled, chunkSize - filled)
                                if (r <= 0) break
                                filled += r
                            }
                            if (filled <= 0) break
                            val encryptedChunk = encryptSingleChunk(plainBuf, filled, chunkIdx, header.baseNonce, key)
                            fos.write(encryptedChunk)
                            chunkIdx++
                        }
                    }
                }
                fos.flush()
                fos.fd.sync()
            }
            if (!tempFile.renameTo(targetFile)) {
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(targetFile).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                tempFile.delete()
            }
            return header
        } catch (e: Exception) {
            tempFile.delete()
            targetFile.delete()
            throw e
        } finally {
            Arrays.fill(plainBuf, 0.toByte())
            Arrays.fill(baseNonce, 0.toByte())
        }
    }

    fun encryptBytesAtomically(
        plainBytes: ByteArray,
        targetFile: File,
        key: SecretKey,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): Gcf2Header {
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val totalSize = plainBytes.size.toLong()
        val chunkCount = if (totalSize == 0L) 1 else ((totalSize + chunkSize - 1) / chunkSize).toInt()
        val baseNonce = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(baseNonce)
        val header = Gcf2Header(VERSION_V2.toInt(), chunkSize, baseNonce, totalSize, chunkCount)

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                writeHeader(fos, header)
                if (plainBytes.isEmpty()) {
                    fos.write(encryptSingleChunk(ByteArray(0), 0, 0, header.baseNonce, key))
                } else {
                    var offset = 0
                    var chunkIdx = 0
                    while (offset < plainBytes.size) {
                        val len = minOf(chunkSize, plainBytes.size - offset)
                        val cipher = Cipher.getInstance(AES_GCM)
                        val iv = buildChunkIv(header.baseNonce, chunkIdx)
                        try {
                            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
                            cipher.updateAAD(buildChunkAad(chunkIdx, len))
                            val enc = cipher.doFinal(plainBytes, offset, len)
                            fos.write(enc)
                        } finally {
                            Arrays.fill(iv, 0.toByte())
                        }
                        offset += len
                        chunkIdx++
                    }
                }
                fos.flush()
                fos.fd.sync()
            }
            if (!tempFile.renameTo(targetFile)) {
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(targetFile).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                tempFile.delete()
            }
            return header
        } catch (e: Exception) {
            tempFile.delete()
            targetFile.delete()
            throw e
        } finally {
            Arrays.fill(baseNonce, 0.toByte())
        }
    }

    private fun writeHeader(fos: OutputStream, header: Gcf2Header) {
        val bb = ByteBuffer.allocate(HEADER_SIZE)
        bb.put(MAGIC_GCF2)
        bb.put(VERSION_V2)
        bb.putInt(header.chunkSize)
        bb.put(header.baseNonce)
        bb.putLong(header.totalPlainSize)
        bb.putInt(header.chunkCount)
        fos.write(bb.array())
    }

    private fun encryptSingleChunk(
        buf: ByteArray,
        plainLen: Int,
        chunkIdx: Int,
        baseNonce: ByteArray,
        key: SecretKey
    ): ByteArray {
        val iv = buildChunkIv(baseNonce, chunkIdx)
        val cipher = Cipher.getInstance(AES_GCM)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
            cipher.updateAAD(buildChunkAad(chunkIdx, plainLen))
            cipher.doFinal(buf, 0, plainLen)
        } finally {
            Arrays.fill(iv, 0.toByte())
        }
    }

    fun decryptChunk(
        raf: RandomAccessFile,
        header: Gcf2Header,
        chunkIndex: Int,
        key: SecretKey
    ): ByteArray {
        if (chunkIndex < 0 || chunkIndex >= header.chunkCount) {
            throw IndexOutOfBoundsException("Chunk $chunkIndex out of ${header.chunkCount}")
        }
        val plainLen = header.chunkPlainLength(chunkIndex)
        val cipherLen = plainLen + GCM_TAG_BYTES
        val offset = header.chunkFileOffset(chunkIndex)
        val cipherBuf = ByteArray(cipherLen)
        synchronized(raf) {
            raf.seek(offset)
            raf.readFully(cipherBuf)
        }
        val iv = buildChunkIv(header.baseNonce, chunkIndex)
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
            cipher.updateAAD(buildChunkAad(chunkIndex, plainLen))
            cipher.doFinal(cipherBuf)
        } catch (e: AEADBadTagException) {
            throw AEADBadTagException("GCF2 chunk #$chunkIndex auth failed").apply { initCause(e) }
        } finally {
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(cipherBuf, 0.toByte())
        }
    }

    fun decryptToStream(file: File, key: SecretKey, out: OutputStream) {
        val header = readHeader(file) ?: throw IOException("Invalid GCF2 header: ${file.name}")
        RandomAccessFile(file, "r").use { raf ->
            for (i in 0 until header.chunkCount) {
                val plainChunk = decryptChunk(raf, header, i, key)
                try {
                    if (plainChunk.isNotEmpty()) {
                        out.write(plainChunk)
                    }
                } finally {
                    Arrays.fill(plainChunk, 0.toByte())
                }
            }
        }
    }

    fun decryptToBytes(file: File, key: SecretKey): ByteArray {
        val header = readHeader(file) ?: throw IOException("Invalid GCF2 header: ${file.name}")
        val expected = header.totalPlainSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val baos = ByteArrayOutputStream(expected)
        decryptToStream(file, key, baos)
        return baos.toByteArray()
    }
}
