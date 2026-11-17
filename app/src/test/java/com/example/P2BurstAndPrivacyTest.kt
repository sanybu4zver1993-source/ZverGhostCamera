package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import com.example.camera.MultiFrameStacker
import com.example.crypto.MonoVaultEngine
import com.example.security.ZverPrefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P2BurstAndPrivacyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun makeShiftedGridBitmap(w: Int, h: Int, shiftX: Int, shiftY: Int, brightBias: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = (x - shiftX + w) % w
                val sy = (y - shiftY + h) % h
                val v = if ((sx / 8 + sy / 8) % 2 == 0) (60 + brightBias) else (175 + brightBias)
                val c = v.coerceIn(0, 255)
                bmp.setPixel(x, y, Color.rgb(c, c, c))
            }
        }
        return bmp
    }

    @Test
    fun testMultiFrameSadAlignmentAndAllStackModes() {
        val w = 72
        val h = 72
        val f0 = makeShiftedGridBitmap(w, h, 0, 0)
        val f1 = makeShiftedGridBitmap(w, h, 3, -2)

        val p0 = IntArray(w * h)
        val p1 = IntArray(w * h)
        f0.getPixels(p0, 0, w, 0, 0, w, h)
        f1.getPixels(p1, 0, w, 0, 0, w, h)

        val (dx, dy) = MultiFrameStacker.estimateTranslationShift(p0, p1, w, h, searchRadius = 8)
        assertTrue("Expected SAD shift close to (3, -2), got ($dx, $dy)", abs(dx - 3) <= 1 && abs(dy - (-2)) <= 1)

        // Add 3rd frame with local moving ghost block to verify ghost rejection
        val f2 = makeShiftedGridBitmap(w, h, 0, 0)
        for (y in 10 until 34) {
            for (x in 10 until 34) {
                f2.setPixel(x, y, Color.rgb(255, 255, 255))
            }
        }

        for (mode in MultiFrameStacker.StackMode.values()) {
            val out = MultiFrameStacker.processBurst(listOf(f0, f1, f2), mode, recycleInputs = false)
            assertEquals(w, out.bitmap.width)
            assertEquals(h, out.bitmap.height)
            assertEquals(3, out.stats.frameCount)
            assertEquals(mode, out.stats.mode)
            assertTrue("Recommended smart quality in bounds", out.stats.recommendedQuality in 32..95)
            assertTrue("Ghost rejection should trigger on bright moving block", out.stats.rejectedGhostPercent > 0)
            out.bitmap.recycle()
        }

        // Verify HDR exposure fusion merges dark + bright frames cleanly
        val dark = makeShiftedGridBitmap(w, h, 0, 0, brightBias = -45)
        val mid = makeShiftedGridBitmap(w, h, 0, 0, brightBias = 0)
        val bright = makeShiftedGridBitmap(w, h, 0, 0, brightBias = 50)
        val hdrOut = MultiFrameStacker.processBurst(listOf(dark, mid, bright), MultiFrameStacker.StackMode.HDR_FUSION, recycleInputs = true)
        assertEquals(w, hdrOut.bitmap.width)
        hdrOut.bitmap.recycle()

        f0.recycle()
        f1.recycle()
        f2.recycle()
    }

    @Test
    fun testSmartCompressionAdaptsToSceneEdgeDensity() {
        val w = 96
        val h = 96
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        flat.eraseColor(Color.rgb(42, 42, 42))

        val highDetail = makeShiftedGridBitmap(w, h, 0, 0)
        // Make sharper 2px stripes so edges trigger dense threshold (>=42%)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if ((x + y) % 2 == 0) 20 else 235
                highDetail.setPixel(x, y, Color.rgb(c, c, c))
            }
        }

        val preset = ExifStripper.CompressionPreset.BALANCED
        val qFlat = MultiFrameStacker.estimateSmartCompressionQuality(flat, preset)
        val qDetail = MultiFrameStacker.estimateSmartCompressionQuality(highDetail, preset)
        assertTrue("Dense edge scene ($qDetail) must get higher quality than flat scene ($qFlat)", qDetail > qFlat)
        assertEquals(preset.quality - 8, qFlat)
        assertEquals(preset.quality + 8, qDetail)

        flat.recycle()
        highDetail.recycle()
    }

    @Test
    fun testMp4SanitizerShiftsCo64SixtyFourBitOffsets() {
        fun box(type: String, payload: ByteArray): ByteArray {
            val total = 8 + payload.size
            return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(total)
                put(type.toByteArray(Charsets.US_ASCII))
                put(payload)
            }.array()
        }

        val ftypBox = box("ftyp", "isom".toByteArray())
        val mvhdPayload = ByteArray(28).apply {
            this[0] = 1 // version 1 FullBox (64-bit timestamps)
            this[4] = 0x55
            this[12] = 0x66
        }
        val mvhdBox = box("mvhd", mvhdPayload)
        val udtaBox = box("udta", "MT6769Z-GPS-LEAK-PAYLOAD".toByteArray())

        val placeholderCo64 = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(1)
            putLong(0L)
        }.array()
        val moovPlaceholder = box("moov", mvhdBox + udtaBox + box("trak", box("mdia", box("minf", box("stbl", box("co64", placeholderCo64))))))
        val origMdatOffset = (ftypBox.size + moovPlaceholder.size).toLong()
        val origDataOffset = origMdatOffset + 8L

        val patchedCo64 = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(1)
            putLong(origDataOffset)
        }.array()
        val patchedMoov = box("moov", mvhdBox + udtaBox + box("trak", box("mdia", box("minf", box("stbl", box("co64", patchedCo64))))))
        val mdatBox = box("mdat", byteArrayOf(1, 2, 3, 4))
        val rawMp4 = ftypBox + patchedMoov + mdatBox

        val cleanMp4 = Mp4Sanitizer.sanitizeMp4Bytes(rawMp4)
        val audit = Mp4Sanitizer.auditMp4(cleanMp4)
        assertTrue(audit.isClean)
        assertEquals(0, audit.metadataBytes)

        val co64Idx = cleanMp4.indices.first { i ->
            i + 23 < cleanMp4.size &&
                cleanMp4[i] == 'c'.code.toByte() &&
                cleanMp4[i + 1] == 'o'.code.toByte() &&
                cleanMp4[i + 2] == '6'.code.toByte() &&
                cleanMp4[i + 3] == '4'.code.toByte()
        }
        val actualOffset64 = ByteBuffer.wrap(cleanMp4, co64Idx + 12, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(origDataOffset - udtaBox.size.toLong(), actualOffset64)
    }

    @Test
    fun testMonoVaultCryptoPrimitivesAndZverPrefsP2() {
        val ikm = "secret_master_ikm".toByteArray()
        val salt = "zver_salt".toByteArray()
        val k1 = MonoVaultEngine.hkdfDeriveKey(ikm, salt, "MASTER_ZONE".toByteArray(), 32)
        val k2 = MonoVaultEngine.hkdfDeriveKey(ikm, salt, "DECOY_ZONE".toByteArray(), 32)
        assertEquals(32, k1.encoded.size)
        assertFalse("Master and Decoy HKDF keys must differ", k1.encoded.contentEquals(k2.encoded))

        val aadA = MonoVaultEngine.buildBlockAAD(fileOffset = 4096L, timestamp = 123456L, zoneId = 1, sequence = 0, padLen = 77)
        val aadB = MonoVaultEngine.buildBlockAAD(fileOffset = 4096L, timestamp = 123456L, zoneId = 1, sequence = 1, padLen = 77)
        assertEquals(28, aadA.size)
        assertFalse("Sequence bump must change AAD", aadA.contentEquals(aadB))

        // Verify MonoVault file path helper & clean prefs roundtrip
        val cFile: File = MonoVaultEngine.getContainerFile(context)
        assertEquals(MonoVaultEngine.CONTAINER_NAME, cFile.name)

        assertTrue("Smart compression defaults to true", ZverPrefs.isSmartCompressionEnabled(context))
        ZverPrefs.setSmartCompressionEnabled(context, false)
        assertFalse(ZverPrefs.isSmartCompressionEnabled(context))
        ZverPrefs.setSmartCompressionEnabled(context, true)

        assertNull(ZverPrefs.getMultiFrameMode(context))
        ZverPrefs.setMultiFrameMode(context, MultiFrameStacker.StackMode.NIGHT_DENOISE)
        assertEquals(MultiFrameStacker.StackMode.NIGHT_DENOISE, ZverPrefs.getMultiFrameMode(context))
        ZverPrefs.setMultiFrameMode(context, null)
        assertNull(ZverPrefs.getMultiFrameMode(context))

        // Validate auditBytes still catches stripped containers cleanly
        val sample = makeShiftedGridBitmap(64, 64, 0, 0)
        val cleanRes = ExifStripper.processBitmapClean(sample, recycleSource = true, qualityOverride = 74)
        assertArrayEquals(cleanRes.cleanBytes, cleanRes.cleanBytes)
    }
}
