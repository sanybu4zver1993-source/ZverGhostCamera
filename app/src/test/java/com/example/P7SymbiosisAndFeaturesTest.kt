package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.PowerManager
import com.example.audio.OfflineVoiceChanger
import com.example.camera.CameraBindingCoordinator
import com.example.camera.CameraTab
import com.example.camera.CameraUiState
import com.example.camera.ThermalThrottleController
import com.example.privacy.OfflinePrivacyEngine
import com.example.visor.VisorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P7SymbiosisAndFeaturesTest {

    @Test
    fun offlineVoiceChanger_chipmunkAndDemonPresetsTransformSignalAndPreserveTiming() {
        val sr = 44100
        val sampleCount = sr // 1 second mono
        val input = ShortArray(sampleCount) { i ->
            val t = i.toDouble() / sr
            (sin(2.0 * PI * 220.0 * t) * 14000.0).roundToInt().toShort()
        }

        for (preset in listOf(OfflineVoiceChanger.VoicePreset.CHIPMUNK, OfflineVoiceChanger.VoicePreset.DEMON)) {
            val exact = OfflineVoiceChanger.transformPcm16Samples(
                input = input,
                sampleRate = sr,
                channelCount = 1,
                preset = preset,
                keepExactLength = true
            )
            assertEquals("keepExactLength=true must preserve length for $preset", input.size, exact.size)

            var energy = 0L
            var diffs = 0
            for (i in input.indices) {
                energy += abs(exact[i].toInt())
                if (abs(exact[i].toInt() - input[i].toInt()) > 120) diffs++
            }
            assertTrue("Expected audible energy for $preset", energy / sampleCount > 500L)
            assertTrue("Expected transformed waveform for $preset", diffs > sampleCount / 3)

            // Sonic pitch change preserves duration without stretching/compressing length wildly
            val natural = OfflineVoiceChanger.transformPcm16Samples(
                input = input,
                sampleRate = sr,
                channelCount = 1,
                preset = preset,
                keepExactLength = false
            )
            val ratio = natural.size.toFloat() / input.size.toFloat()
            assertTrue("Duration ratio for $preset should stay near 1.0 (was $ratio)", ratio in 0.80f..1.20f)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun offlinePrivacyEngine_maskPresetsAndCustomPngRenderNonBlackoutArtworkWithScale() {
        val base = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 215, 220))
        }
        val region = OfflinePrivacyEngine.NormalizedRegion(
            id = "face_0",
            rect = RectF(0.25f, 0.25f, 0.75f, 0.75f),
            kind = OfflinePrivacyEngine.RegionKind.FACE,
            rollDeg = 8f
        )

        val customOverlay = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }

        for (preset in OfflinePrivacyEngine.MaskPreset.values()) {
            val redacted = OfflinePrivacyEngine.applyRedactions(
                source = base,
                regions = listOf(region),
                style = OfflinePrivacyEngine.RedactionStyle.MASK,
                invertMode = false,
                maskPreset = preset,
                maskScale = 1.25f,
                customMaskBitmap = if (preset == OfflinePrivacyEngine.MaskPreset.CUSTOM_PNG) customOverlay else null
            )

            var changedPixels = 0
            var nonPureBlackChanged = 0
            for (y in 40 until 200 step 2) {
                for (x in 40 until 200 step 2) {
                    val p = redacted.getPixel(x, y)
                    if (p != Color.rgb(210, 215, 220)) {
                        changedPixels++
                        if (p != Color.BLACK) nonPureBlackChanged++
                    }
                }
            }
            assertTrue("Mask preset $preset must draw over target face", changedPixels > 200)
            assertTrue("Mask preset $preset must not be plain solid black", nonPureBlackChanged > 100)
            redacted.recycle()
        }

        val small = OfflinePrivacyEngine.computeScaledMaskBounds(Rect(60, 60, 180, 180), scale = 0.6f, maxW = 240, maxH = 240)
        val large = OfflinePrivacyEngine.computeScaledMaskBounds(Rect(60, 60, 180, 180), scale = 2.2f, maxW = 240, maxH = 240)
        assertTrue("Scaled mask must grow monotonically with scale", large.width() > small.width() * 2.5f)
        assertNotEquals(small, large)

        customOverlay.recycle()
        base.recycle()
    }

    @Test
    fun cameraUiState_andExtensionEligibility_isolateModesCleanly() {
        val docState = CameraUiState(
            activeTab = CameraTab.DOCUMENTS,
            visorMode = VisorMode.NORMAL,
            isDocumentModeActive = true,
            isRecordingVideo = false
        )
        assertTrue(docState.showDocumentLevel)
        assertFalse(docState.showTacticalReticle)

        val photoState = CameraUiState(
            activeTab = CameraTab.PHOTO,
            visorMode = VisorMode.NORMAL,
            isDocumentModeActive = true, // even if stray flag is true, PHOTO hides document level
            isRecordingVideo = false
        )
        assertFalse(photoState.showDocumentLevel)
        assertTrue(photoState.showTacticalReticle)

        assertTrue(CameraBindingCoordinator.isExtensionEligibleTab(CameraTab.NIGHT, VisorMode.NORMAL, useGpuVisorPipeline = false))
        assertFalse(CameraBindingCoordinator.isExtensionEligibleTab(CameraTab.VIDEO, VisorMode.NORMAL, useGpuVisorPipeline = false))
        assertFalse(CameraBindingCoordinator.isExtensionEligibleTab(CameraTab.VISOR, VisorMode.THERMAL_LUT, useGpuVisorPipeline = true))
        assertFalse(CameraBindingCoordinator.isExtensionEligibleTab(CameraTab.SENTINEL, VisorMode.NORMAL, useGpuVisorPipeline = false))
        assertFalse(CameraBindingCoordinator.isExtensionEligibleTab(CameraTab.NIGHT, VisorMode.NIGHT_GAMMA, useGpuVisorPipeline = true))
    }

    @Test
    fun thermalThrottleController_scalesIntervalUnderThermalPressure() {
        assertEquals(230L, ThermalThrottleController.computeAnalysisIntervalMs(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(350L, ThermalThrottleController.computeAnalysisIntervalMs(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(650L, ThermalThrottleController.computeAnalysisIntervalMs(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(1400L, ThermalThrottleController.computeAnalysisIntervalMs(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(1800L, ThermalThrottleController.computeAnalysisIntervalMs(PowerManager.THERMAL_STATUS_CRITICAL))
    }
}
