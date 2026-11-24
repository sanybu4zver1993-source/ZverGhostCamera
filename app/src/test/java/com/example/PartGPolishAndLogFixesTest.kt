package com.example

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.assist.GeminiByokClient
import com.example.camera.AudioWatcherEngine
import com.example.camera.CameraCapabilityProfiler
import com.example.camera.ExifStripper
import com.example.camera.PhotoIqEngine
import com.example.security.SentinelEngine
import com.example.ui.CameraBottomControlDeck
import com.example.ui.CameraTelemetryState
import com.example.ui.CameraTopHudAndDrawer
import com.example.ui.PcbLabDialog
import com.example.ui.SecretSplitDialog
import com.example.ui.SelfTestDialog
import com.example.ui.VaultPhotoEditorDialog
import com.example.ui.VikaAiSheet
import com.example.visor.VisorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class PartGPolishAndLogFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun geminiByokClient_isQuotaOrNetworkFailure_matches503OverloadAndRejects400() {
        assertTrue(
            GeminiByokClient.isQuotaOrNetworkFailure(
                "HTTP 503: This model is currently experiencing high demand. Spikes in demand are usually temporary."
            )
        )
        assertTrue(GeminiByokClient.isQuotaOrNetworkFailure("502 Bad Gateway"))
        assertTrue(GeminiByokClient.isQuotaOrNetworkFailure("Service Unavailable (504)"))
        assertTrue(GeminiByokClient.isQuotaOrNetworkFailure("RESOURCE_EXHAUSTED: rate limit exceeded"))
        assertTrue(GeminiByokClient.isQuotaOrNetworkFailure("HTTP 429: Too Many Requests"))
        assertTrue(GeminiByokClient.isQuotaOrNetworkFailure("SocketTimeoutException: failed to connect"))

        assertFalse(GeminiByokClient.isQuotaOrNetworkFailure("Ошибка запроса (400): Invalid argument"))
        assertFalse(GeminiByokClient.isQuotaOrNetworkFailure("API key not valid (403)"))
    }

    @Test
    fun selfTestDialog_narrow360dp_rendersBannerAndButtonsCleanly() {
        var closed = false
        composeTestRule.setContent {
            SelfTestDialog(
                boundUseCasesSummary = "Preview + ImageCapture",
                eglRecordableOk = true,
                onStatusMessage = {},
                onDismiss = { closed = true }
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 6_000L) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("4GB RAM PROFILE")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("4GB RAM PROFILE").assertIsDisplayed()
        composeTestRule.onNodeWithText("PASS:", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("selftest_copy_btn")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("selftest_rerun_btn")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("selftest_close_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(closed)
    }

    @Test
    fun vikaAiSheet_narrow360dp_rendersTabsAndPhotoModesWithoutClipping() {
        val dummyBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        var dismissed = false
        try {
            composeTestRule.setContent {
                VikaAiSheet(
                    externalBitmap = dummyBmp,
                    onStatusMessage = {},
                    onDismiss = { dismissed = true }
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("🧠 Задачи").assertIsDisplayed()
            composeTestRule.onNodeWithText("📊 Стата").assertIsDisplayed()
            composeTestRule.onNodeWithText("🔑 Ключи").assertIsDisplayed()
            composeTestRule.onNodeWithText("✂️ БЕЗ ФОНА").assertIsDisplayed()
            composeTestRule.onNodeWithContentDescription("Закрыть")
                .assertIsDisplayed()
            composeTestRule.onNodeWithContentDescription("Закрыть").performClick()
            composeTestRule.waitForIdle()
            assertTrue(dismissed)
        } finally {
            dummyBmp.recycle()
        }
    }

    @Test
    fun pcbLabDialog_narrow360dp_rendersCenteredTabsAndInspectControls() {
        val dummyBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        var dismissed = false
        try {
            composeTestRule.setContent {
                PcbLabDialog(
                    initialBitmap = dummyBmp,
                    onSavedToVault = {},
                    onDismiss = { dismissed = true }
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("🔬 ПЛАТА").assertIsDisplayed()
            composeTestRule.onNodeWithText("🔤 OCR ЧИПЫ").assertIsDisplayed()
            composeTestRule.onNodeWithText("🎛️ SMD КОДЫ").assertIsDisplayed()
            composeTestRule.onNodeWithTag("pcb_close_btn")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.waitForIdle()
            assertTrue(dismissed)
        } finally {
            dummyBmp.recycle()
        }
    }

    @Test
    fun secretSplitDialog_narrow360dp_rendersHeaderTabsAndSchemes() {
        composeTestRule.setContent {
            SecretSplitDialog(
                onStatusMessage = {},
                onDismiss = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("СПЛИТ-КЛЮЧ GF256").assertIsDisplayed()
        composeTestRule.onNodeWithText("✂️ РАЗБИТЬ КЛЮЧ").assertIsDisplayed()
        composeTestRule.onNodeWithText("🧩 СОБРАТЬ ДОЛИ").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 из 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 из 4").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 из 5").assertIsDisplayed()
        composeTestRule.onNodeWithTag("split_key_close_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun vaultPhotoEditorDialog_narrow360dp_rendersColorLabelAnd2xCrop() {
        val dummyBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            composeTestRule.setContent {
                VaultPhotoEditorDialog(
                    sourceBitmap = dummyBmp,
                    onDismiss = {},
                    onSavedCopyToVault = {}
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("ФОТО-РЕДАКТОР СЕЙФА").assertIsDisplayed()
            composeTestRule.onNodeWithText("ЦВЕТ").assertIsDisplayed()
            composeTestRule.onNodeWithText("2x ЦЕНТР").assertIsDisplayed()
            composeTestRule.onNodeWithTag("editor_reset_btn").assertIsDisplayed()
            composeTestRule.onNodeWithTag("editor_share_copy_btn")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        } finally {
            dummyBmp.recycle()
        }
    }

    @Test
    fun cameraHudComponents_narrow360dp_rendersChromeStyleSingleRowHudAndOverflowMenus() {
        val telemetry = CameraTelemetryState()
        val modes = listOf(
            "Видео" to "VIDEO",
            "Фото" to "PHOTO",
            "Ночь" to "NIGHT",
            "Визор" to "VISOR",
            "Дозор" to "SENTINEL",
            "Документы" to "DOCUMENTS"
        )
        composeTestRule.setContent {
            val isTopMenuOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxSize()) {
                CameraTopHudAndDrawer(
                ramMode = false,
                compressionPreset = ExifStripper.CompressionPreset.BALANCED,
                isAssistModeActive = false,
                hasByokKey = true,
                isTorchActive = false,
                isToolsDrawerOpen = true,
                activeToolsCount = 2,
                isTopOverflowMenuOpen = isTopMenuOpen.value,
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
                photoIqProfile = PhotoIqEngine.PhotoIqProfile.OFF,
                multiFrameMode = null,
                showPcbLabDialog = false,
                isAudioWatcherActive = false,
                audioThresholdDbfs = -26.0,
                isGyroEisEnabled = false,
                isRetroScanning = false,
                isTimeLapseRecording = false,
                timeLapseIntervalMs = 2000L,
                isCapturing = false,
                isRecordingVideo = false,
                lastStatusMessage = null,
                telemetry = telemetry,
                selectedCameraMode = "SENTINEL",
                zoomRatio = 2.0f,
                onToggleTorch = {},
                onToggleDrawer = {},
                onCloseDrawer = {},
                onFlipCamera = {},
                onOpenOverflowMenu = { isTopMenuOpen.value = true },
                onDismissOverflowMenu = { isTopMenuOpen.value = false },
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
                onToggleGyroEis = {},
                onTriggerRetroScan = {},
                onToggleTimeLapse = {},
                onOpenSelfTest = {},
                onOpenVikaSheet = {},
                onOpenPanicShred = {}
            )

            CameraBottomControlDeck(
                modifier = Modifier.align(Alignment.BottomCenter),
                telemetry = telemetry,
                isAudioWatcherActive = false,
                audioTelemetry = AudioWatcherEngine.AcousticTelemetry(),
                audioThresholdDbfs = -26.0,
                selectedCameraMode = "SENTINEL",
                cameraModes = modes,
                sentinelArmed = false,
                sentinelConfig = SentinelEngine.SentinelConfig(),
                sentinelEvents = emptyList(),
                hasByokKey = false,
                visorMode = VisorMode.BIO_RPPG,
                isProPanelExpanded = false,
                proProfile = CameraCapabilityProfiler.CameraProProfile.fallback(CameraCharacteristics.LENS_FACING_BACK),
                exposureIndex = 0,
                awbPreset = CameraCapabilityProfiler.AwbPreset.AUTO,
                manualFocusNorm = null,
                isoOverride = null,
                shutterNsOverride = null,
                isAeLocked = false,
                photoIqProfile = PhotoIqEngine.PhotoIqProfile.OFF,
                isHistogramEnabled = false,
                isZebraEnabled = false,
                isZoomIqEnabled = false,
                zoomRatio = 1.0f,
                multiFrameMode = null,
                vaultCount = 0,
                isCapturing = false,
                countdownRemaining = null,
                isAssistModeActive = false,
                isRecordingVideo = false,
                timerSeconds = 0,
                onSelectAudioThreshold = {},
                onToggleSentinelArmed = {},
                onCycleSentinelLinger = {},
                onToggleSentinelKnock = {},
                onToggleSentinelClaheNight = {},
                onToggleSentinelTwoStageCrop = {},
                onToggleSentinelAiTriage = {},
                onClearSentinelEvents = {},
                onStealthGuard = {},
                onSelectVisorMode = {},
                onSavePulseMetrics = { _, _ -> },
                onSaveVisorSnapshot = {},
                onExposureIndexChange = {},
                onAwbPresetChange = {},
                onManualFocusChange = {},
                onIsoChange = {},
                onShutterNsChange = {},
                onAeLockChange = {},
                onPhotoIqChange = {},
                onToggleHistogram = {},
                onToggleZebra = {},
                onCloseProPanel = {},
                onSelectZoom = {},
                onCycleMultiFrame = {},
                onToggleProPanel = {},
                onCycleEvQuick = {},
                onApplyCameraMode = {},
                onOpenVault = {},
                onTriggerVideoToggle = {},
                onTriggerPhotoCapture = {},
                onOpenVikaSheet = {},
                onCycleTimer = {}
            )
            }
        }
        composeTestRule.waitForIdle()

        // Top compact HUD pill + drawer + top 3-dots button
        composeTestRule.onNodeWithText("ZVER").assertIsDisplayed()
        composeTestRule.onNodeWithText("▲ Свернуть панель").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_overflow_menu_button")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // Bottom single-row bar: Vault + Shutter + Bottom 3-dots button
        composeTestRule.onNodeWithTag("vault_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("bottom_overflow_menu_button")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // Sentinel card can collapse to save even more viewfinder space
        composeTestRule.onNodeWithTag("sentinel_card_toggle_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("▲").assertIsDisplayed()

        // Open top Chrome-style 3-dots menu
        composeTestRule.onNodeWithTag("top_overflow_menu_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("torch_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("flip_camera_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_drawer_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_menu_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Открыть PRO-панель").assertIsDisplayed()
        composeTestRule.onNodeWithText("Self-Test диагностика")
            .performScrollTo()
            .assertIsDisplayed()

        // Scroll back to quick icons and click flip camera to dismiss top menu
        composeTestRule.onNodeWithTag("flip_camera_button")
            .performScrollTo()
            .performClick()
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()

        // Open bottom Chrome-style 3-dots menu
        composeTestRule.onNodeWithTag("bottom_overflow_menu_button").performClick()
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Дозор").assertIsDisplayed()
        composeTestRule.onNodeWithText("10x").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pro_panel_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vika_ai_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_record_button")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
