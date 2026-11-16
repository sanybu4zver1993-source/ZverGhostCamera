package com.example

import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZverSanitizerUnitTest {

    @Test
    fun testJpegStripperRemovesAppAndComSegments() {
        val baos = ByteArrayOutputStream()
        // SOI
        baos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // Fake APP1 (EXIF GPS/Device) - length 8 (2 length bytes + 6 data)
        baos.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08))
        baos.write("EXIF12".toByteArray())
        // Fake COM comment - length 6
        baos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x06))
        baos.write("TEST".toByteArray())
        // DQT table (non-metadata, MUST stay) - length 6
        baos.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x06))
        baos.write(byteArrayOf(1, 2, 3, 4))
        // SOS + dummy bitstream + EOI
        baos.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()))

        val raw = baos.toByteArray()
        val rawAudit = ExifStripper.auditBytes(raw)
        assertFalse(rawAudit.isClean)
        assertTrue(rawAudit.metadataBytes > 0)

        val cleaned = ExifStripper.stripJpegSegments(raw)
        val cleanAudit = ExifStripper.auditBytes(cleaned)
        assertEquals("JPEG", cleanAudit.format)
        assertEquals(0, cleanAudit.metadataBytes)
        assertTrue(cleanAudit.isClean)
    }

    @Test
    fun testWebpStripperRemovesExifAndXmpChunks() {
        fun chunk(tag: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag.toByteArray(Charsets.US_ASCII))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            out.write(data)
            if (data.size and 1 == 1) out.write(0)
            return out.toByteArray()
        }

        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        body.write(chunk("VP8 ", byteArrayOf(1, 2, 3, 4, 5, 6)))
        body.write(chunk("EXIF", "GPS_LAT_51_50".toByteArray()))
        body.write(chunk("XMP ", "<xmp>Redmi13C</xmp>".toByteArray()))

        val bodyBytes = body.toByteArray()
        val riff = ByteArrayOutputStream()
        riff.write("RIFF".toByteArray(Charsets.US_ASCII))
        riff.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bodyBytes.size).array())
        riff.write(bodyBytes)

        val dirtyWebp = riff.toByteArray()
        assertFalse(ExifStripper.auditBytes(dirtyWebp).isClean)

        val cleanWebp = ExifStripper.stripWebpMetadataChunks(dirtyWebp)
        val report = ExifStripper.auditBytes(cleanWebp)
        assertEquals("WEBP", report.format)
        assertEquals(0, report.metadataBytes)
        assertTrue(report.isClean)
    }

    @Test
    fun testMp4SanitizerStripsUdtaMetaAndZeroesTimestampsAndShiftsStco() {
        fun box(type: String, payload: ByteArray): ByteArray {
            val total = 8 + payload.size
            val bb = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(total)
            bb.put(type.toByteArray(Charsets.US_ASCII))
            bb.put(payload)
            return bb.array()
        }

        // mvhd version 0: 4 bytes version/flags + 4B creation + 4B mod + rest
        val mvhdPayload = ByteArray(20).apply {
            this[4] = 0x7E.toByte()
            this[8] = 0x7F.toByte()
            this[16] = 0x05 // timescale/duration marker that must remain untouched
        }
        // stco with 1 chunk offset pointing at original mdat offset
        val ftypBox = box("ftyp", "isom".toByteArray())
        val udtaBox = box("udta", "Redmi13C-Location".toByteArray())
        val mvhdBox = box("mvhd", mvhdPayload)

        // Construct stco placeholder first to know sizes
        val stcoPayload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version/flags
            putInt(1) // 1 entry
            putInt(0) // placeholder offset
        }.array()
        val stblBoxPlaceholder = box("stbl", box("stco", stcoPayload))
        val minfBox = box("minf", stblBoxPlaceholder)
        val mdiaBox = box("mdia", minfBox)
        val trakBox = box("trak", mdiaBox)
        val moovBeforeMdat = box("moov", mvhdBox + udtaBox + trakBox)

        val origMdatOffset = ftypBox.size + moovBeforeMdat.size
        // Patch stco entry inside raw mp4 to point to origMdatOffset + 8 (mdat data)
        val patchedStcoPayload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(1)
            putInt(origMdatOffset + 8)
        }.array()
        val patchedMoov = box(
            "moov",
            mvhdBox + udtaBox + box("trak", box("mdia", box("minf", box("stbl", box("stco", patchedStcoPayload)))))
        )
        val mdatBox = box("mdat", byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        val rawMp4 = ftypBox + patchedMoov + mdatBox

        val dirtyAudit = Mp4Sanitizer.auditMp4(rawMp4)
        assertFalse(dirtyAudit.isClean)
        assertTrue(dirtyAudit.metadataBytes > 0)

        val cleanMp4 = Mp4Sanitizer.sanitizeMp4Bytes(rawMp4)
        val cleanAudit = Mp4Sanitizer.auditMp4(cleanMp4)
        assertTrue(cleanAudit.isClean)
        assertEquals(0, cleanAudit.metadataBytes)
        assertTrue(cleanMp4.size < rawMp4.size)

        // Verify stco was shifted down by exact udtaBox.size!
        val expectedNewDataOffset = (origMdatOffset + 8) - udtaBox.size
        // Locate "stco" inside cleanMp4
        val stcoIdx = cleanMp4.indices.first { i ->
            i + 19 < cleanMp4.size &&
                cleanMp4[i] == 's'.code.toByte() &&
                cleanMp4[i + 1] == 't'.code.toByte() &&
                cleanMp4[i + 2] == 'c'.code.toByte() &&
                cleanMp4[i + 3] == 'o'.code.toByte()
        }
        val actualOffset = ByteBuffer.wrap(cleanMp4, stcoIdx + 12, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(expectedNewDataOffset, actualOffset)
    }
}
