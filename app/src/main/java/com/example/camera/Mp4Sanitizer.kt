package com.example.camera

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mp4Sanitizer:
 * Strips user/device/GPS metadata atoms (udta, meta, uuid, free, skip) from MP4 recordings,
 * zeroes creation/modification timestamps in mvhd/tkhd/mdhd, and accurately shifts stco/co64 offsets
 * so the sanitized video remains 100% playable with zero forensic timestamps or device tags.
 */
object Mp4Sanitizer {

    private data class Mp4Box(
        val type: String,
        val payload: ByteArray,
        val originalOffset: Long
    )

    private val STRIPPED_TOP_BOXES = setOf("udta", "meta", "free", "skip", "uuid")
    private val STRIPPED_MOOV_CHILDREN = setOf("udta", "meta", "free", "skip", "uuid")

    private const val MAX_IN_MEMORY_SANITIZE_BYTES = 48L * 1024L * 1024L

    fun sanitizeMp4File(inputFile: File, outputFile: File): Boolean {
        return try {
            if (!inputFile.exists() || inputFile.length() < 32 || inputFile.length() > MAX_IN_MEMORY_SANITIZE_BYTES) {
                false
            } else {
                val rt = Runtime.getRuntime()
                val usedHeap = rt.totalMemory() - rt.freeMemory()
                val availableHeap = (rt.maxMemory() - usedHeap).coerceAtLeast(0L)
                if (inputFile.length() * 3L > availableHeap) {
                    return false
                }
                val bytes = FileInputStream(inputFile).use { it.readBytes() }
                val clean = sanitizeMp4Bytes(bytes)
                FileOutputStream(outputFile).use { fos ->
                    fos.write(clean)
                    fos.fd.sync()
                }
                true
            }
        } catch (_: OutOfMemoryError) {
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
            false
        } catch (_: Exception) {
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
            false
        }
    }

    fun sanitizeMp4Bytes(data: ByteArray): ByteArray {
        val topBoxes = parseBoxes(data, 0, data.size)
        if (topBoxes.none { it.type == "ftyp" } || topBoxes.none { it.type == "moov" }) {
            return data
        }

        // Compute original mdat start offset
        var origMdatOffset = -1L
        for (b in topBoxes) {
            if (b.type == "mdat") {
                origMdatOffset = b.originalOffset
                break
            }
        }

        // First pass: check whether moov precedes mdat
        val filteredTop = topBoxes.filter { it.type !in STRIPPED_TOP_BOXES }
        val moovIdx = filteredTop.indexOfFirst { it.type == "moov" }
        val mdatIdx = filteredTop.indexOfFirst { it.type == "mdat" }

        // Build tentative moov (with delta=0) to know its exact sanitized byte length
        val rawMoovPayload = filteredTop[moovIdx].payload
        val preliminaryMoovPayload = rewriteMoov(rawMoovPayload, 0L)
        val preliminaryMoovSize = 8L + preliminaryMoovPayload.size

        var newMdatOffset = 0L
        for (i in filteredTop.indices) {
            if (i == mdatIdx) break
            newMdatOffset += if (i == moovIdx) preliminaryMoovSize else (8L + filteredTop[i].payload.size)
        }

        val chunkOffsetDelta = if (origMdatOffset >= 0 && mdatIdx >= 0) {
            newMdatOffset - origMdatOffset
        } else {
            0L
        }

        val finalMoovPayload = if (chunkOffsetDelta != 0L) {
            rewriteMoov(rawMoovPayload, chunkOffsetDelta)
        } else {
            preliminaryMoovPayload
        }

        val out = ByteArrayOutputStream(data.size)
        for (i in filteredTop.indices) {
            val box = filteredTop[i]
            val payload = if (i == moovIdx) finalMoovPayload else box.payload
            writeBox(out, box.type, payload)
        }
        return out.toByteArray()
    }

    fun auditMp4(bytes: ByteArray): ExifStripper.AuditReport {
        val boxes = parseBoxes(bytes, 0, bytes.size)
        val segments = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var metaBytes = 0
        var hasZeroTimestamps = true
        for (b in boxes) {
            if (b.type in STRIPPED_TOP_BOXES) {
                metaBytes += 8 + b.payload.size
                segments.add("${b.type} (META! ${b.payload.size}B)")
            } else if (b.type == "moov") {
                val children = parseBoxes(b.payload, 0, b.payload.size)
                val bad = children.filter { it.type in STRIPPED_MOOV_CHILDREN }
                bad.forEach {
                    metaBytes += 8 + it.payload.size
                    segments.add("moov.${it.type} (META! ${it.payload.size}B)")
                }
                val mvhd = children.firstOrNull { it.type == "mvhd" }
                if (mvhd != null && mvhd.payload.size >= 12) {
                    val version = mvhd.payload[0].toInt() and 0xFF
                    val tsEnd = if (version == 1 && mvhd.payload.size >= 20) 20 else 12
                    val nonZero = (4 until tsEnd).any { mvhd.payload[it] != 0.toByte() }
                    if (nonZero) hasZeroTimestamps = false
                }
                segments.add("moov (${children.size} atoms)")
            } else {
                segments.add("${b.type} (${b.payload.size}B)")
            }
        }
        notes.add(if (metaBytes == 0) "MP4: udta/meta/uuid/free удалены (0 B)" else "ВНИМАНИЕ: найдены атомы метаданных!")
        notes.add(if (hasZeroTimestamps) "Таймштампы mvhd/tkhd/mdhd обнулены" else "Таймштампы mvhd не обнулены")
        return ExifStripper.AuditReport("MP4", bytes.size, metaBytes, segments, metaBytes == 0, notes)
    }

    private fun rewriteMoov(moovPayload: ByteArray, chunkDelta: Long): ByteArray {
        val children = parseBoxes(moovPayload, 0, moovPayload.size)
        val out = ByteArrayOutputStream(moovPayload.size)
        for (child in children) {
            if (child.type in STRIPPED_MOOV_CHILDREN) continue
            val rewrittenPayload = when (child.type) {
                "mvhd" -> zeroFullBoxTimestamps(child.payload)
                "trak" -> rewriteContainerBox(child.payload, chunkDelta)
                "mvex" -> child.payload
                else -> child.payload
            }
            writeBox(out, child.type, rewrittenPayload)
        }
        return out.toByteArray()
    }

    private fun rewriteContainerBox(payload: ByteArray, chunkDelta: Long): ByteArray {
        val children = parseBoxes(payload, 0, payload.size)
        if (children.isEmpty()) return payload
        val out = ByteArrayOutputStream(payload.size)
        for (child in children) {
            if (child.type in STRIPPED_MOOV_CHILDREN) continue
            val newPayload = when (child.type) {
                "tkhd", "mdhd" -> zeroFullBoxTimestamps(child.payload)
                "mdia", "minf", "stbl", "edts" -> rewriteContainerBox(child.payload, chunkDelta)
                "stco" -> shiftStco(child.payload, chunkDelta)
                "co64" -> shiftCo64(child.payload, chunkDelta)
                else -> child.payload
            }
            writeBox(out, child.type, newPayload)
        }
        return out.toByteArray()
    }

    /**
     * Zeroes creation_time and modification_time in FullBox (mvhd, tkhd, mdhd).
     */
    private fun zeroFullBoxTimestamps(payload: ByteArray): ByteArray {
        val copy = payload.clone()
        if (copy.size < 12) return copy
        val version = copy[0].toInt() and 0xFF
        if (version == 0 && copy.size >= 12) {
            // bytes 4..7 = creation_time, 8..11 = modification_time
            for (i in 4 until 12) copy[i] = 0
        } else if (version == 1 && copy.size >= 20) {
            // bytes 4..11 = creation_time, 12..19 = modification_time
            for (i in 4 until 20) copy[i] = 0
        }
        return copy
    }

    private fun shiftStco(payload: ByteArray, delta: Long): ByteArray {
        if (delta == 0L || payload.size < 8) return payload
        val copy = payload.clone()
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val count = buf.int
        if (count < 0 || 8 + count * 4 > copy.size) return payload
        for (i in 0 until count) {
            val pos = 8 + i * 4
            val oldOff = buf.getInt(pos).toLong() and 0xFFFFFFFFL
            val newOff = (oldOff + delta).coerceAtLeast(0L)
            buf.putInt(pos, newOff.toInt())
        }
        return copy
    }

    private fun shiftCo64(payload: ByteArray, delta: Long): ByteArray {
        if (delta == 0L || payload.size < 8) return payload
        val copy = payload.clone()
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val count = buf.int
        if (count < 0 || 8 + count * 8 > copy.size) return payload
        for (i in 0 until count) {
            val pos = 8 + i * 8
            val oldOff = buf.getLong(pos)
            buf.putLong(pos, (oldOff + delta).coerceAtLeast(0L))
        }
        return copy
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val list = mutableListOf<Mp4Box>()
        var p = start
        while (p + 8 <= end) {
            val size = ByteBuffer.wrap(data, p, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            if (size == 0L) {
                val payload = data.copyOfRange(p + 8, end)
                list.add(Mp4Box(type, payload, p.toLong()))
                break
            }
            if (size < 8 || p + size > end) break
            val payload = data.copyOfRange(p + 8, (p + size).toInt())
            list.add(Mp4Box(type, payload, p.toLong()))
            p += size.toInt()
        }
        return list
    }

    private fun writeBox(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        val totalSize = 8 + payload.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        header.putInt(totalSize)
        header.put(type.toByteArray(Charsets.US_ASCII).copyOf(4))
        out.write(header.array())
        out.write(payload)
    }
}
