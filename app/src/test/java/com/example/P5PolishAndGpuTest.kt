package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.camera.ExifStripper
import com.example.camera.MultiFrameStacker
import com.example.camera.VoiceControlManager
import com.example.visor.GlVisorMath
import com.example.visor.GlVisorShaders
import com.example.visor.MuxerLifecycleGuard
import com.example.visor.VisorMode
import com.example.visor.VisorVideoRecorder
import com.example.visor.shaderModeId
import com.example.visor.supportsGpuShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P5PolishAndGpuTest {

    private fun leInt(v: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(v)
        .array()

    private fun buildWebpChunk(fourCc: String, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(fourCc.toByteArray(Charsets.US_ASCII))
        baos.write(leInt(payload.size))
        baos.write(payload)
        if ((payload.size and 1) == 1) baos.write(0)
        return baos.toByteArray()
    }

    @Test
    fun testWebpStripperFastPathAndMetadataRemoval() {
        val vp8Payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val cleanChunk = buildWebpChunk("VP8 ", vp8Payload)
        val cleanWebp = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(leInt(4 + cleanChunk.size))
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(cleanChunk)
        }.toByteArray()

        // Fast-path returns exact same array when no EXIF/XMP/ICCP/VP8X flags present
        val fastResult = ExifStripper.stripWebpMetadataChunks(cleanWebp)
        assertSame(cleanWebp, fastResult)

        // Now build VP8X container with EXIF (0x08), XMP (0x04), ICCP (0x20) flags set (0x2C)
        val vp8xPayload = ByteArray(10)
        vp8xPayload[0] = 0x2E // animation/exif/xmp/iccp bits; strip must clear 0x2C leaving 0x02
        val body = ByteArrayOutputStream().apply {
            write(buildWebpChunk("VP8X", vp8xPayload))
            write(buildWebpChunk("ICCP", byteArrayOf(1, 2, 3)))
            write(buildWebpChunk("VP8 ", vp8Payload))
            write(buildWebpChunk("EXIF", "Redmi 13C GPS".toByteArray(Charsets.US_ASCII)))
            write(buildWebpChunk("XMP ", "<xmp>secret</xmp>".toByteArray(Charsets.US_ASCII)))
        }.toByteArray()
        val dirtyWebp = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(leInt(4 + body.size))
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(body)
        }.toByteArray()

        val stripped = ExifStripper.stripWebpMetadataChunks(dirtyWebp)
        val strippedText = String(stripped, Charsets.US_ASCII)
        assertFalse(strippedText.contains("EXIF"))
        assertFalse(strippedText.contains("XMP "))
        assertFalse(strippedText.contains("ICCP"))
        assertTrue(strippedText.contains("VP8 "))

        val declaredRiffSize = ByteBuffer.wrap(stripped, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(stripped.size - 8, declaredRiffSize)

        // VP8X flags at offset 20 should have metadata bits (0x2C) cleared -> 0x02 preserved
        assertEquals(0x02, stripped[20].toInt() and 0xFF)
        val webpAudit = ExifStripper.auditBytes(stripped)
        assertEquals("WEBP", webpAudit.format)
        assertTrue(webpAudit.isClean)
        assertEquals(0, webpAudit.metadataBytes)
    }

    @Test
    fun testProcessBitmapCleanAndSmartQualityBulkPixels() {
        val textured = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val c = if (((x + y) and 1) == 0) 20 else 235
                textured.setPixel(x, y, Color.rgb(c, (x * 2) and 0xFF, (y * 2) and 0xFF))
            }
        }
        val smooth = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(110, 120, 130))
        }

        val qTex = MultiFrameStacker.estimateSmartCompressionQuality(textured, ExifStripper.CompressionPreset.BALANCED)
        val qSmooth = MultiFrameStacker.estimateSmartCompressionQuality(smooth, ExifStripper.CompressionPreset.BALANCED)
        assertEquals(86, qTex)
        assertEquals(70, qSmooth)
        assertTrue("Expected smooth quality < textured quality ($qSmooth < $qTex)", qSmooth < qTex)

        val cleanRes = ExifStripper.processBitmapClean(
            sourceBitmap = textured,
            recycleSource = true,
            preset = ExifStripper.CompressionPreset.BALANCED,
            antiPrnu = true
        )
        assertTrue(cleanRes.isWebP)
        assertTrue(cleanRes.width in 100..128 && cleanRes.height in 100..128)
        assertTrue(cleanRes.cleanBytes.size > 16)
        assertEquals(0, cleanRes.audit.metadataBytes)
        smooth.recycle()
    }

    @Test
    fun testVoiceCommandTimerOffAndStopRouting() {
        assertEquals(VoiceControlManager.VoiceCommand.TIMER_OFF, VoiceControlManager.parseCommand("сбрось таймер"))
        assertEquals(VoiceControlManager.VoiceCommand.TIMER_OFF, VoiceControlManager.parseCommand("Выключи таймер пожалуйста"))
        assertEquals(VoiceControlManager.VoiceCommand.TIMER_OFF, VoiceControlManager.parseCommand("без таймера"))
        assertEquals(VoiceControlManager.VoiceCommand.TIMER_5, VoiceControlManager.parseCommand("поставь таймер"))
        assertEquals(VoiceControlManager.VoiceCommand.TOGGLE_VIDEO, VoiceControlManager.parseCommand("стоп"))
        assertEquals(VoiceControlManager.VoiceCommand.TOGGLE_VIDEO, VoiceControlManager.parseCommand("останови видео"))
        assertEquals(VoiceControlManager.VoiceCommand.CAPTURE, VoiceControlManager.parseCommand("сфоткай быстрее"))
        assertNull(VoiceControlManager.parseCommand("   "))
    }

    @Test
    fun testGpuVisorModeMappingAndShadersCompleteness() {
        val gpuModes = VisorMode.values().filter { it.supportsGpuShader() }
        val cpuTemporalModes = VisorMode.values().filterNot { it.supportsGpuShader() }

        assertTrue(cpuTemporalModes.isEmpty())
        assertEquals(14, gpuModes.size)

        val ids = gpuModes.map { it.shaderModeId() }
        assertEquals(14, ids.distinct().size)
        assertTrue(ids.all { it in 0..13 })
        assertEquals(12, VisorMode.BIO_RPPG.shaderModeId())
        assertEquals(13, VisorMode.EULER_AMP.shaderModeId())

        assertTrue(GlVisorShaders.VERTEX_300.contains("#version 300 es"))
        assertTrue(GlVisorShaders.FRAGMENT_300.contains("GL_OES_EGL_image_external_essl3"))
        assertTrue(GlVisorShaders.FRAGMENT_100.contains("GL_OES_EGL_image_external"))
        assertTrue(GlVisorShaders.POSE_VERTEX.contains("uPointSize"))
        for (modeId in 1..13) {
            assertTrue("FRAGMENT_300 missing uMode == $modeId", GlVisorShaders.FRAGMENT_300.contains("uMode == $modeId"))
            assertTrue("FRAGMENT_100 missing uMode == $modeId", GlVisorShaders.FRAGMENT_100.contains("uMode == $modeId"))
        }
    }

    @Test
    fun testCenterCropMvpAspectAndRotationMath() {
        val mvp = FloatArray(16)
        val identityTex = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Wider source (16:9) on 3:4 portrait dst -> scales X wider (>1)
        GlVisorMath.computeCenterCropMvp(1280, 720, 720, 960, identityTex, mvp)
        assertTrue("Expected scaleX > 1f, got ${mvp[0]}", mvp[0] > 1.0f)
        assertEquals(1f, mvp[5], 0.001f)

        // Rotated 90deg texture matrix swaps oriented src (1280x720 -> 720x1280 = 9:16 portrait) on 3:4 dst -> scales Y (>1)
        val rot90Tex = floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f
        )
        GlVisorMath.computeCenterCropMvp(1280, 720, 720, 960, rot90Tex, mvp)
        assertEquals(1f, mvp[0], 0.001f)
        assertTrue("Expected scaleY > 1f for 9:16 on 3:4, got ${mvp[5]}", mvp[5] > 1.0f)

        // flipVertical negates mvp[5]
        GlVisorMath.computeCenterCropMvp(1280, 720, 720, 960, rot90Tex, mvp, flipVertical = true)
        assertTrue("Expected negative mvp[5] when flipped, got ${mvp[5]}", mvp[5] < -1.0f)
    }

    @Test
    fun testMuxerLifecycleGuardAndRecorderInitialState() {
        val guard = MuxerLifecycleGuard()
        assertFalse(guard.consumeStopIfNeeded())

        guard.reset()
        guard.markStarted()
        assertFalse("Must not stop muxer if zero samples written", guard.consumeStopIfNeeded())

        guard.reset()
        guard.markStarted()
        guard.markSampleWritten()
        assertTrue("First stop with written samples should return true", guard.consumeStopIfNeeded())
        assertFalse("Second stop must return false", guard.consumeStopIfNeeded())

        val recorder = VisorVideoRecorder()
        assertFalse(recorder.isRecording)
    }
}
