package com.example.crypto

import android.media.MediaDataSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Arrays
import javax.crypto.SecretKey

/**
 * EncryptedMediaDataSource:
 * Streams encrypted GCF2 video directly into android.media.MediaPlayer / MediaMetadataRetriever
 * with O(1) seek and < 600 KB RAM footprint (caches at most 2 decrypted chunks, zeroed on close).
 */
class EncryptedMediaDataSource(
    file: File,
    private val key: SecretKey
) : MediaDataSource() {

    private val header: GcfChunkedCodec.Gcf2Header =
        GcfChunkedCodec.readHeader(file) ?: throw IOException("Not a valid GCF2 stream: ${file.name}")
    private val raf: RandomAccessFile = RandomAccessFile(file, "r")
    private val lock = Any()

    private var slot0Idx: Int = -1
    private var slot0Data: ByteArray? = null
    private var slot1Idx: Int = -1
    private var slot1Data: ByteArray? = null
    private var lastUsedSlot: Int = 1

    @Volatile
    private var closed: Boolean = false

    override fun getSize(): Long = header.totalPlainSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        if (position < 0L || position >= header.totalPlainSize) return -1
        if (size <= 0) return 0

        val maxReadable = minOf(size.toLong(), header.totalPlainSize - position).toInt()
        if (maxReadable <= 0) return -1

        var copied = 0
        var curPos = position
        var dstOff = offset

        synchronized(lock) {
            if (closed) return -1
            while (copied < maxReadable) {
                val chunkIdx = (curPos / header.chunkSize).toInt()
                val inChunkPos = (curPos % header.chunkSize).toInt()
                val chunkBytes = getOrLoadChunkLocked(chunkIdx)
                val avail = chunkBytes.size - inChunkPos
                if (avail <= 0) break
                val toCopy = minOf(avail, maxReadable - copied)
                System.arraycopy(chunkBytes, inChunkPos, buffer, dstOff, toCopy)
                copied += toCopy
                curPos += toCopy
                dstOff += toCopy
            }
        }
        return if (copied > 0) copied else -1
    }

    private fun getOrLoadChunkLocked(chunkIdx: Int): ByteArray {
        if (slot0Idx == chunkIdx && slot0Data != null) {
            lastUsedSlot = 0
            return slot0Data!!
        }
        if (slot1Idx == chunkIdx && slot1Data != null) {
            lastUsedSlot = 1
            return slot1Data!!
        }

        val fresh = GcfChunkedCodec.decryptChunk(raf, header, chunkIdx, key)
        if (lastUsedSlot == 0) {
            slot1Data?.let { Arrays.fill(it, 0.toByte()) }
            slot1Data = fresh
            slot1Idx = chunkIdx
            lastUsedSlot = 1
        } else {
            slot0Data?.let { Arrays.fill(it, 0.toByte()) }
            slot0Data = fresh
            slot0Idx = chunkIdx
            lastUsedSlot = 0
        }
        return fresh
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            slot0Data?.let { Arrays.fill(it, 0.toByte()) }
            slot1Data?.let { Arrays.fill(it, 0.toByte()) }
            slot0Data = null
            slot1Data = null
            slot0Idx = -1
            slot1Idx = -1
            try {
                raf.close()
            } catch (_: Exception) {}
        }
    }
}
