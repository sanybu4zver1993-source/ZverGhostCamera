package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.CameraSelector
import androidx.test.core.app.ApplicationProvider
import com.example.camera.AntiBlurSensorEngine
import com.example.camera.CameraCapabilityProfiler
import com.example.camera.ExifStripper
import com.example.camera.PhotoIqEngine
import com.example.camera.ZoomIntelligence
import com.example.security.ZverPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P1ProCameraAndIqTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun makePatternBitmap(width: Int = 64, height: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = if ((x / 8 + y / 8) % 2 == 0) 40 else 180
                val g = (y * 3).coerceIn(0, 220)
                val b = (x * 3).coerceIn(0, 220)
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bmp
    }

    @Test
    fun testPhotoIqProfilesAndHistogram() {
        val src = makePatternBitmap(64, 64)
        for (profile in PhotoIqEngine.PhotoIqProfile.values()) {
            val out = PhotoIqEngine.enhanceBitmap(src, profile, recycleSource = false)
            assertEquals(src.width, out.width)
            assertEquals(src.height, out.height)
            assertFalse(out.isRecycled)
            val bins = PhotoIqEngine.computeBitmapHistogram32(out)
            assertEquals(32, bins.size)
            assertTrue("Histogram must have positive peak", bins.maxOrNull() ?: 0f > 0f)
            if (out !== src) out.recycle()
        }

        val graded = PhotoIqEngine.applyManualAdjustments(
            src,
            PhotoIqEngine.PhotoAdjustments(
                brightness = 0.15f,
                contrast = 1.25f,
                saturation = 1.1f,
                sharpness = 0.5f,
                dehazeClahe = true
            )
        )
        assertEquals(64, graded.width)
        assertEquals(64, graded.height)
        graded.recycle()
        src.recycle()
    }

    @Test
    fun testZoomIntelligenceTiersAndEnhancement() {
        val t1 = ZoomIntelligence.evaluateZoomTier(1.0f)
        val t2 = ZoomIntelligence.evaluateZoomTier(2.0f)
        val t4 = ZoomIntelligence.evaluateZoomTier(4.0f)
        val t10 = ZoomIntelligence.evaluateZoomTier(10.0f)

        assertFalse(t1.requiresSteadyHand)
        assertEquals(0f, t1.sharpenStrength, 0.001f)
        assertTrue(t2.sharpenStrength > 0f)
        assertTrue(t4.requiresSteadyHand)
        assertTrue(t10.sharpenStrength > t4.sharpenStrength)

        val src = makePatternBitmap(80, 80)
        val enhanced = ZoomIntelligence.enhanceZoomedBitmap(src, zoomRatio = 6.0f, recycleSource = false)
        assertEquals(80, enhanced.width)
        assertEquals(80, enhanced.height)
        enhanced.recycle()

        val cropped = ZoomIntelligence.extractZoomDetailCrop(src, cropFraction = 0.5f, maxOutDim = 160)
        assertTrue(cropped.width in 40..160)
        assertTrue(cropped.height in 40..160)
        cropped.recycle()
        src.recycle()
    }

    @Test
    fun testHistogramAndZebraYPlaneTelemetry() {
        val engine = AntiBlurSensorEngine(context)
        val w = 120
        val h = 100
        val ySize = w * h
        val buf = ByteBuffer.allocateDirect(ySize)
        // Left half bright (250 -> zebra!), right half dark (8 -> underexposed)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lum = if (x < w / 2) 250 else 8
                buf.put(lum.toByte())
            }
        }
        buf.rewind()

        val telemetry = engine.computeHistogramAndZebra(
            yBuffer = buf,
            width = w,
            height = h,
            rowStride = w,
            rotationDegrees = 0,
            zebraThreshold = 240,
            collectZebra = true
        )
        assertEquals(32, telemetry.bins32.size)
        assertTrue("Expected ~50% overexposed highlights, got ${telemetry.overexposedPct}", telemetry.overexposedPct in 40..60)
        assertTrue("Expected ~50% underexposed shadows, got ${telemetry.underexposedPct}", telemetry.underexposedPct in 40..60)
        assertTrue("Expected zebra hotspots non-empty", telemetry.zebraHotspots.isNotEmpty())
        assertTrue("Mean luma should be near 129", telemetry.meanLuma in 110..150)
    }

    @Test
    fun testCameraProfilerAndZverPrefsP1Persistence() {
        val profile = CameraCapabilityProfiler.profileCamera(context, CameraSelector.LENS_FACING_BACK)
        assertEquals(CameraSelector.LENS_FACING_BACK, profile.lensFacing)
        assertTrue(profile.maxZoomRatio >= 1.0f)
        assertTrue(profile.evRange.first <= profile.evRange.last)
        assertEquals("1/1000", CameraCapabilityProfiler.formatShutterSpeed(1_000_000L))
        assertEquals("1.5s", CameraCapabilityProfiler.formatShutterSpeed(1_500_000_000L))

        ZverPrefs.setPhotoIqProfile(context, PhotoIqEngine.PhotoIqProfile.TEXT_CRISP)
        assertEquals(PhotoIqEngine.PhotoIqProfile.TEXT_CRISP, ZverPrefs.getPhotoIqProfile(context))
        ZverPrefs.setZoomIqEnabled(context, false)
        assertFalse(ZverPrefs.isZoomIqEnabled(context))
        ZverPrefs.setZebraThreshold(context, 235)
        assertEquals(235, ZverPrefs.getZebraThreshold(context))
    }

    @Test
    fun testExifStripperIntegratesPhotoIqAndZoomWithoutMetadata() {
        val src = makePatternBitmap(96, 96)
        val res = ExifStripper.processBitmapClean(
            sourceBitmap = src,
            recycleSource = true,
            preset = ExifStripper.CompressionPreset.BALANCED,
            antiPrnu = false,
            photoIqProfile = PhotoIqEngine.PhotoIqProfile.AUTO_IQ,
            zoomRatio = 6.0f
        )
        assertTrue("Processed bytes should be non-empty", res.cleanBytes.isNotEmpty())
        assertEquals(96, res.width)
        assertEquals(96, res.height)
        assertEquals(0, res.audit.metadataBytes)
        if (res.audit.format == "WEBP" || res.audit.format == "JPEG") {
            assertTrue("Audit must report clean output (0 EXIF)", res.audit.isClean)
        }
    }

    @Test
    fun testEvStopsFormattingAndSteppingSynced() {
        val mtkStep = 1f / 6f
        val range = -12..12
        assertEquals("EV 0.0", CameraCapabilityProfiler.formatEvStops(0, mtkStep))
        assertEquals("EV +0.5", CameraCapabilityProfiler.formatEvStops(3, mtkStep))
        assertEquals("EV -1.0", CameraCapabilityProfiler.formatEvStops(-6, mtkStep))
        assertEquals(3, CameraCapabilityProfiler.evIndexForStops(0.5f, mtkStep, range))
        assertEquals(6, CameraCapabilityProfiler.evIndexForStops(1.0f, mtkStep, range))
        assertEquals(-6, CameraCapabilityProfiler.evIndexForStops(-1.0f, mtkStep, range))
        assertEquals(12, CameraCapabilityProfiler.evIndexForStops(5.0f, mtkStep, range))
        assertEquals(-12, CameraCapabilityProfiler.evIndexForStops(-5.0f, mtkStep, range))
    }
}
