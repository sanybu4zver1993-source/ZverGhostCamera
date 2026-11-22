package com.example

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.example.camera.AnalysisScheduler
import com.example.camera.ExifStripper
import com.example.camera.PhotoIqEngine
import com.example.camera.RetroReflectionDetector
import com.example.camera.TimeLapseRecorder
import com.example.security.AntiForensicsGuard
import com.example.security.CamouflageManager
import com.example.security.ZverPrefs
import com.example.ui.CameraTelemetryState
import com.example.ui.CameraTopHudAndDrawer
import com.example.ui.GhostSettingsDialog
import com.example.ui.RetroReflectionOverlay
import com.example.visor.VisorMode
import com.google.mlkit.common.internal.MlKitInitProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartBAndColdStartTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun startupProviders_initializeWithoutCrashOnApi35() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startupProvider = InitializationProvider()
        val startupInfo = ProviderInfo().apply {
            authority = "${context.packageName}.androidx-startup"
            packageName = context.packageName
        }
        startupProvider.attachInfo(context, startupInfo)

        val mlKitProvider = MlKitInitProvider()
        val mlKitInfo = ProviderInfo().apply {
            authority = "${context.packageName}.mlkitinitprovider"
            packageName = context.packageName
        }
        mlKitProvider.attachInfo(context, mlKitInfo)
    }

    @Test
    fun mainActivity_coldStartAndLifecycleTransitions_succeedInDefaultAndCalculatorCamouflage() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Default ZVER mode -> displays permission gate when CAMERA permission is not granted
        CamouflageManager.setCamouflage(context, CamouflageManager.CamouflageMode.ZVER)
        val defaultController = Robolectric.buildActivity(MainActivity::class.java).setup()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ZVER CAMERA", substring = true).assertIsDisplayed()
        defaultController.pause().stop().destroy()

        // CALCULATOR mode -> displays decoy calculator gate without crashing
        CamouflageManager.setCamouflage(context, CamouflageManager.CamouflageMode.CALCULATOR)
        val calcController = Robolectric.buildActivity(MainActivity::class.java).setup()
        composeTestRule.waitForIdle()
        calcController.pause().stop().destroy()

        CamouflageManager.setCamouflage(context, CamouflageManager.CamouflageMode.ZVER)
    }

    @Test
    fun antiForensicsGuard_andCrashBreadcrumb_doNotThrowOnMissingNativeOrBackgroundFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val status = AntiForensicsGuard.assessDeviceSecurity(context)
        assertNotNull(status)

        assertNull(ZverPrefs.getLastCrashBreadcrumb(context))
        ZverPrefs.recordLastCrashBreadcrumb(context, "[main] RuntimeException: test crash @ MainActivity.onCreate:240")
        val recorded = ZverPrefs.getLastCrashBreadcrumb(context)
        assertNotNull(recorded)
        assertTrue(recorded!!.contains("RuntimeException"))

        ZverPrefs.clearLastCrashBreadcrumb(context)
        assertNull(ZverPrefs.getLastCrashBreadcrumb(context))
    }

    @Test
    fun zverPrefs_partBPreferencesPersistAndClampCorrectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(ZverPrefs.isGyroEisEnabled(context))
        ZverPrefs.setGyroEisEnabled(context, true)
        assertTrue(ZverPrefs.isGyroEisEnabled(context))

        assertEquals(2000L, ZverPrefs.getTimeLapseIntervalMs(context))
        ZverPrefs.setTimeLapseIntervalMs(context, 5000L)
        assertEquals(5000L, ZverPrefs.getTimeLapseIntervalMs(context))
        ZverPrefs.setTimeLapseIntervalMs(context, 100L)
        assertEquals(500L, ZverPrefs.getTimeLapseIntervalMs(context))
    }

    @Test
    fun analysisScheduler_enforcesSingleFlightGenerationAndThermalPriority() {
        val scheduler = AnalysisScheduler()
        val gen1 = scheduler.tryBeginFrame()
        assertEquals(1L, gen1)
        assertNull(scheduler.tryBeginFrame())

        val gen2 = scheduler.bumpGeneration()
        assertEquals(2L, gen2)
        assertFalse(scheduler.isGenerationCurrent(gen1!!))
        assertTrue(scheduler.isGenerationCurrent(gen2))

        scheduler.endFrame()
        assertEquals(2L, scheduler.tryBeginFrame())
        scheduler.endFrame()

        // First slow tick at t=1000ms under thermal throttle 350ms
        val plan1 = scheduler.planFrameWork(
            nowMs = 1000L,
            thermalIntervalMs = 350L,
            visorMode = VisorMode.BIO_RPPG,
            useGpuShader = true,
            needExpo = false,
            needPeaking = true,
            needSentinel = false,
            stealthBlackoutActive = false
        )
        assertTrue(plan1.runSharpnessAndExpo)
        assertTrue(plan1.runVisorTelemetry)
        assertFalse(plan1.runCpuPeaking) // GPU shader owns peaking

        // Fast subsequent frame before 350ms still runs high-rate BIO_RPPG telemetry
        val planFast = scheduler.planFrameWork(
            nowMs = 1040L,
            thermalIntervalMs = 350L,
            visorMode = VisorMode.BIO_RPPG,
            useGpuShader = true,
            needExpo = false,
            needPeaking = false,
            needSentinel = false,
            stealthBlackoutActive = false
        )
        assertFalse(planFast.runSharpnessAndExpo)
        assertTrue(planFast.runVisorTelemetry)

        // DEHAZE_LIVE LUT dispatches on slow tick
        val planDehaze = scheduler.planFrameWork(
            nowMs = 1400L,
            thermalIntervalMs = 350L,
            visorMode = VisorMode.DEHAZE_LIVE,
            useGpuShader = true,
            needExpo = false,
            needPeaking = false,
            needSentinel = false,
            stealthBlackoutActive = false
        )
        assertTrue(planDehaze.runVisorDehazeLut)
    }

    @Test
    fun retroReflectionDetector_detectsCompactCoaxialGlintAndIgnoresUniformScene() {
        val w = 120
        val h = 160
        val off = ByteArray(w * h) { 45.toByte() }
        val on = ByteArray(w * h) { 60.toByte() }

        // Inject compact circular coaxial lens glint at (60, 80) radius ~4px
        for (y in 75..85) {
            for (x in 55..65) {
                val dx = x - 60
                val dy = y - 80
                if (sqrt((dx * dx + dy * dy).toDouble()) <= 4.0) {
                    on[y * w + x] = 240.toByte()
                }
            }
        }

        val glintResult = RetroReflectionDetector.analyzePairedYuv(
            onYPlane = on,
            offYPlane = off,
            width = w,
            height = h,
            rotationDegrees = 90,
            isFrontCamera = false
        )
        assertTrue("Expected lens hotspot detection", glintResult.hotspots.isNotEmpty())
        assertTrue(glintResult.confidence > 0.3f)

        // Uniform daylight flash increase across entire scene should produce 0 compact hotspots
        val uniformOff = ByteArray(w * h) { 100.toByte() }
        val uniformOn = ByteArray(w * h) { 170.toByte() }
        val cleanResult = RetroReflectionDetector.analyzePairedYuv(
            onYPlane = uniformOn,
            offYPlane = uniformOff,
            width = w,
            height = h,
            rotationDegrees = 90,
            isFrontCamera = false
        )
        assertTrue("Uniform brightness rise must not trigger false lens hotspots", cleanResult.hotspots.isEmpty())
    }

    @Test
    fun partBUi_settingsDrawerAndRetroOverlayExposeExpectedTestTags() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ZverPrefs.recordLastCrashBreadcrumb(context, "[main] TestCrash: sample trace")
        var eisToggled = false
        var retroTriggered = false
        var timeLapseToggled = false
        var selfTestOpened = false

        composeTestRule.setContent {
            val telemetry = CameraTelemetryState().apply {
                isRetroScanning = true
                retroHotspots = listOf(
                    RetroReflectionDetector.RetroHotspot(
                        normX = 0.5f,
                        normY = 0.5f,
                        radiusPx = 4f,
                        deltaLuma = 180,
                        circularity = 0.9f
                    )
                )
            }
            RetroReflectionOverlay(
                isScanning = true,
                hotspots = telemetry.retroHotspots,
                summaryLabel = "test"
            )
            CameraTopHudAndDrawer(
                ramMode = false,
                compressionPreset = ExifStripper.CompressionPreset.BALANCED,
                isAssistModeActive = false,
                hasByokKey = false,
                isTorchActive = false,
                isToolsDrawerOpen = true,
                activeToolsCount = 1,
                isTopOverflowMenuOpen = false,
                isProPanelExpanded = false,
                isHistogramEnabled = false,
                isZebraEnabled = false,
                zebraThreshold = 235,
                isFocusPeakingEnabled = false,
                isFalseColorEnabled = false,
                isDocumentModeActive = false,
                isDcpDehazeActive = false,
                isAutoShutterEnabled = false,
                isFocusBracketingActive = false,
                timerSeconds = 0,
                isVoiceControlActive = false,
                photoIqProfile = PhotoIqEngine.PhotoIqProfile.AUTO_IQ,
                multiFrameMode = null,
                showPcbLabDialog = false,
                isAudioWatcherActive = false,
                audioThresholdDbfs = -25.0,
                isGyroEisEnabled = true,
                isRetroScanning = false,
                isTimeLapseRecording = false,
                timeLapseIntervalMs = 2000L,
                isCapturing = false,
                isRecordingVideo = false,
                lastStatusMessage = null,
                telemetry = telemetry,
                onToggleTorch = {},
                onToggleDrawer = {},
                onCloseDrawer = {},
                onFlipCamera = {},
                onOpenOverflowMenu = {},
                onDismissOverflowMenu = {},
                onOpenSettings = {},
                onToggleProPanel = {},
                onToggleHistogram = {},
                onToggleZebra = {},
                onToggleFocusPeaking = {},
                onToggleFalseColor = {},
                onToggleDocumentMode = {},
                onToggleDcpDehaze = {},
                onToggleAutoShutter = {},
                onToggleFocusBracketing = {},
                onCycleTimer = {},
                onToggleVoiceControl = {},
                onActivateStealth = {},
                onOpenCamouflage = {},
                onToggleAssistMode = {},
                onCyclePhotoIq = {},
                onCycleMultiFrame = {},
                onOpenPcbLab = {},
                onToggleAudioWatcher = {},
                onToggleGyroEis = { eisToggled = true },
                onTriggerRetroScan = { retroTriggered = true },
                onToggleTimeLapse = { timeLapseToggled = true },
                onOpenSelfTest = { selfTestOpened = true },
                onOpenVikaSheet = {},
                onOpenPanicShred = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("retro_reflection_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_eis_toggle").performClick()
        composeTestRule.onNodeWithTag("drawer_retro_scan_btn").performClick()
        composeTestRule.onNodeWithTag("drawer_timelapse_toggle").performClick()
        composeTestRule.onNodeWithTag("drawer_selftest_btn").performClick()
        composeTestRule.waitForIdle()

        assertTrue(eisToggled)
        assertTrue(retroTriggered)
        assertTrue(timeLapseToggled)
        assertTrue(selfTestOpened)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h915dp")
    fun ghostSettingsDialog_exposesPartBEisTimeLapseAndCrashBreadcrumbControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ZverPrefs.recordLastCrashBreadcrumb(context, "[main] TestCrash: sample trace")

        composeTestRule.setContent {
            GhostSettingsDialog(
                onDismiss = {},
                onStatusMessage = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_eis_switch").performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_eis_switch").assertIsDisplayed().performClick()

        composeTestRule.onNodeWithTag("settings_timelapse_interval_${TimeLapseRecorder.SUPPORTED_INTERVALS_MS[3]}")
            .performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_timelapse_interval_${TimeLapseRecorder.SUPPORTED_INTERVALS_MS[3]}")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("clear_crash_breadcrumb_button").performScrollTo()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("clear_crash_breadcrumb_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertTrue(ZverPrefs.isGyroEisEnabled(context))
        assertEquals(5000L, ZverPrefs.getTimeLapseIntervalMs(context))
        assertNull(ZverPrefs.getLastCrashBreadcrumb(context))
    }
}
