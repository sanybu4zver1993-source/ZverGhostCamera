package com.example

import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.example.audio.OfflineVoiceChanger
import com.example.audio.OfflineVoiceChanger.VoicePreset
import com.example.privacy.OfflinePrivacyEngine
import com.example.security.ZverPrefs
import com.example.visor.MuxerLifecycleGuard
import com.example.visor.VisorVideoRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P4VaultAndPerfTest {

    @Test
    fun testOfflineVoiceChangerPreservesExactLengthAndTransformsTimbre() {
        val sampleRate = 44100
        val sampleCount = 8192
        val input = ShortArray(sampleCount) { i ->
            val phase = 2.0 * PI * 220.0 * i / sampleRate
            (sin(phase) * 14000.0).toInt().toShort()
        }

        val unchanged = OfflineVoiceChanger.transformPcm16Samples(
            input = input,
            sampleRate = sampleRate,
            channelCount = 1,
            preset = VoicePreset.ORIGINAL
        )
        assertArrayEquals("ORIGINAL preset must keep exact samples", input, unchanged)

        for (preset in listOf(VoicePreset.DEEP_ANON, VoicePreset.GRAVE_BASS, VoicePreset.CYBER_ROBOT, VoicePreset.HIGH_PITCH)) {
            val out = OfflineVoiceChanger.transformPcm16Samples(
                input = input,
                sampleRate = sampleRate,
                channelCount = 1,
                preset = preset
            )
            assertEquals("Output length must match input to guarantee 0ms AV drift ($preset)", input.size, out.size)

            var energySum = 0L
            var diffCount = 0
            for (i in input.indices) {
                energySum += abs(out[i].toInt())
                if (out[i] != input[i]) diffCount++
            }
            assertTrue("Transformed audio must remain audible ($preset)", energySum / sampleCount > 500L)
            assertTrue("Preset $preset must actually modify signal", diffCount > sampleCount / 2)
        }
    }

    @Test
    fun testPrivacyRegionDragMovePreservesSizeAndClampsBounds() {
        val box = RectF(0.20f, 0.30f, 0.45f, 0.50f) // width = 0.25, height = 0.20

        val movedNormal = OfflinePrivacyEngine.moveRegionClamped(box, dxNorm = 0.10f, dyNorm = -0.05f)
        assertEquals(0.30f, movedNormal.left, 1e-4f)
        assertEquals(0.25f, movedNormal.top, 1e-4f)
        assertEquals(0.25f, movedNormal.width(), 1e-4f)
        assertEquals(0.20f, movedNormal.height(), 1e-4f)

        val clampedTopLeft = OfflinePrivacyEngine.moveRegionClamped(box, dxNorm = -0.80f, dyNorm = -0.90f)
        assertEquals(0.0f, clampedTopLeft.left, 1e-4f)
        assertEquals(0.0f, clampedTopLeft.top, 1e-4f)
        assertEquals(0.25f, clampedTopLeft.width(), 1e-4f)
        assertEquals(0.20f, clampedTopLeft.height(), 1e-4f)

        val clampedBottomRight = OfflinePrivacyEngine.moveRegionClamped(box, dxNorm = 2.0f, dyNorm = 2.0f)
        assertEquals(1.0f, clampedBottomRight.right, 1e-4f)
        assertEquals(1.0f, clampedBottomRight.bottom, 1e-4f)
        assertEquals(0.25f, clampedBottomRight.width(), 1e-4f)
        assertEquals(0.20f, clampedBottomRight.height(), 1e-4f)
    }

    @Test
    fun testScreenRecBlockPreferenceSyncForVaultFlagSecure() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = ZverPrefs.isScreenRecBlockEnabled(ctx)
        try {
            ZverPrefs.setScreenRecBlockEnabled(ctx, true)
            assertTrue(ZverPrefs.isScreenRecBlockEnabled(ctx))

            ZverPrefs.setScreenRecBlockEnabled(ctx, false)
            assertFalse(ZverPrefs.isScreenRecBlockEnabled(ctx))
        } finally {
            ZverPrefs.setScreenRecBlockEnabled(ctx, original)
        }
    }

    @Test
    fun testMuxerLifecycleGuardPreventsDoubleStopAndEmptyStop() {
        val guard = MuxerLifecycleGuard()

        // Not started -> consumeStopIfNeeded must return false
        assertFalse(guard.consumeStopIfNeeded())

        // Started, but 0 samples written -> must NOT invoke muxer.stop()
        guard.reset()
        guard.markStarted()
        assertFalse(guard.consumeStopIfNeeded())

        // Started + sample written -> consumeStopIfNeeded returns true strictly once
        guard.reset()
        guard.markStarted()
        guard.markSampleWritten()
        assertTrue(guard.consumeStopIfNeeded())
        assertFalse("Second stop must be suppressed to avoid MPEG4Writer crash", guard.consumeStopIfNeeded())
    }

    @Test
    fun testIdleVisorVideoRecorderStopsCleanly() = runBlocking {
        val recorder = VisorVideoRecorder()
        assertFalse(recorder.isRecording)
        assertNull(recorder.stopAndFinalize())
    }
}
