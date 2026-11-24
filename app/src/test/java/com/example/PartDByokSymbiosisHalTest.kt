package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.assist.AutomationRouter
import com.example.assist.ByokManager
import com.example.assist.GeminiByokClient
import com.example.camera.CameraCapabilityProfiler
import com.example.camera.PhotoIqEngine
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.privacy.OfflinePrivacyEngine
import com.example.security.SentinelEngine
import com.example.ui.ProCameraControlPanel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartDByokSymbiosisHalTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ByokManager.clearSessionMemory()
        ByokManager.setContextMemoryEnabled(context, true)
        ByokManager.saveTransportConfig(context, ByokManager.ProxyTransportConfig())
        SentinelEngine.clearSession()
        SentinelEngine.setArmed(false)
        SentinelEngine.updateConfig { SentinelEngine.SentinelConfig() }
        clearMainActivityTriggers()
    }

    @After
    fun tearDown() {
        ByokManager.clearSessionMemory()
        ByokManager.setContextMemoryEnabled(context, true)
        ByokManager.saveTransportConfig(context, ByokManager.ProxyTransportConfig())
        SentinelEngine.clearSession()
        SentinelEngine.setArmed(false)
        SentinelEngine.updateConfig { SentinelEngine.SentinelConfig() }
        clearMainActivityTriggers()
    }

    private fun clearMainActivityTriggers() {
        MainActivity.onVolumeShutterTrigger = null
        MainActivity.onVideoToggleTrigger = null
        MainActivity.onTorchToggleTrigger = null
        MainActivity.onBlackoutToggleTrigger = null
        MainActivity.onSentinelArmTrigger = null
        MainActivity.onModeSwitchTrigger = null
        MainActivity.onRamOnlyTrigger = null
        MainActivity.onLockGateTrigger = null
    }

    @Test
    fun byokClientHardensRedirectsAndBuildsMultiTurnHistory() {
        val torCfg = ByokManager.ProxyTransportConfig(
            mode = ByokManager.TransportMode.TOR_ORBOT_SOCKS5,
            host = "127.0.0.1",
            port = 9050
        )
        val client = GeminiByokClient.buildClientForTransport(torCfg)
        assertFalse("HTTP redirects must stay disabled to protect API key headers", client.followRedirects)
        assertFalse("SSL redirects must stay disabled to protect API key headers", client.followSslRedirects)
        assertNull("HTTP disk cache must stay disabled", client.cache)

        val currentParts = JSONArray().put(JSONObject().put("text", "Уточни цвет куртки"))
        val turns = listOf(
            "Что на фото?" to "Один человек у входа.",
            "Лицо видно?" to "Нет, отвёрнут влево."
        )
        val built = GeminiByokClient.buildContentsJsonArray(currentParts, turns)
        assertEquals(5, built.length())
        assertEquals("user", built.getJSONObject(0).getString("role"))
        assertEquals("model", built.getJSONObject(1).getString("role"))
        assertEquals("user", built.getJSONObject(2).getString("role"))
        assertEquals("model", built.getJSONObject(3).getString("role"))
        assertEquals("user", built.getJSONObject(4).getString("role"))
        assertEquals(
            "Уточни цвет куртки",
            built.getJSONObject(4).getJSONArray("parts").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun socks5ProxyProbeVerifiesHandshakeAgainstRealLocalSocket() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val acceptThread = thread(start = true) {
            try {
                server.accept().use { sock ->
                    val inp = sock.getInputStream()
                    val buf = ByteArray(3)
                    val n = inp.read(buf)
                    if (n == 3 && buf[0] == 0x05.toByte()) {
                        sock.getOutputStream().write(byteArrayOf(0x05, 0x00))
                        sock.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {}
        }

        val okCfg = ByokManager.ProxyTransportConfig(
            mode = ByokManager.TransportMode.CUSTOM_SOCKS5,
            host = "127.0.0.1",
            port = port
        )
        val okRes = GeminiByokClient.probeSocks5Proxy(okCfg, timeoutMs = 2000)
        acceptThread.join(1500)
        server.close()

        assertTrue(okRes.reachable)
        assertTrue(okRes.socks5HandshakeOk)
        assertTrue(okRes.detailRu.contains("SOCKS5"))

        val failRes = GeminiByokClient.probeSocks5Proxy(okCfg, timeoutMs = 600)
        assertFalse(failRes.reachable)
        assertFalse(failRes.socks5HandshakeOk)
    }

    @Test
    fun localSymbiosisProducesDistinctIntentRepliesAndColorTelemetry() {
        val clippedBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(255, 250, 245))
        }
        val quality = LocalSymbiosisCvEngine.assessFrameQuality(clippedBmp)
        assertTrue(quality.clippedHighlightsPct >= 90)
        clippedBmp.recycle()

        val warmBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(255, 190, 140))
        }
        assertEquals("тёплый/лампы", LocalSymbiosisCvEngine.assessFrameQuality(warmBmp).dominantTintRu)
        warmBmp.recycle()

        val replies = listOf(
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Покажи статус Дозора Sentinel"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Какие команды Termux и zver:// работают?"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Как настроен Tor Orbot и ключ BYOK?"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Где бэкап .zvb и сейф монолит?"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Что умеет PRO HAL и Camera2 ISO?"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Как работает маскировка калькулятор и duress?"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Сделай аудит статы"),
            LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Вытащи OCR текст с листа")
        )
        assertEquals("Every offline intent domain must produce a distinct answer", replies.size, replies.distinct().size)
        assertTrue(replies[0].contains("ДОЗОР"))
        assertTrue(replies[1].contains("zver://sentinel"))
        assertTrue(replies[2].contains("KeyStore"))
        assertTrue(replies[3].contains("МОНОЛИТ"))
        assertTrue(replies[4].contains("HAL="))
        assertTrue(replies[5].contains("Duress-PIN"))
        assertTrue(replies[6].contains("АУДИТ"))
        assertTrue(replies[7].contains("OCR_TEXT"))

        val mockReport = LocalSymbiosisCvEngine.SymbiosisReport(
            faces = emptyList(),
            objects = emptyList(),
            sceneTags = emptyList(),
            pose = LocalSymbiosisCvEngine.PoseSummary(false, 0, "нет фигуры", null),
            extractedText = "AA1234BB",
            osintReport = OfflinePrivacyEngine.OsintRiskReport(listOf("Госномер AA1234BB"), 50),
            quality = quality,
            foregroundCoveragePct = null,
            inferenceMs = 19L,
            osintBlockHits = listOf(
                OfflinePrivacyEngine.OsintTextHit(
                    region = OfflinePrivacyEngine.NormalizedRegion("txt_0", RectF(0.1f, 0.2f, 0.4f, 0.3f), OfflinePrivacyEngine.RegionKind.TEXT),
                    matchedLabels = listOf("Госномер"),
                    riskWeight = 35
                )
            )
        )
        val osintText = LocalSymbiosisCvEngine.buildOfflineVikaReport(mockReport, "Проверь OSINT палево")
        assertTrue(osintText.contains("Госномер (1)"))
    }

    @Test
    fun automationRouterParsesUrisBroadcastsAndQueuesColdLaunchActions() {
        assertEquals(
            AutomationRouter.AutomationAction.CapturePhoto,
            AutomationRouter.parseCustomUri(Uri.parse("zver://capture"))
        )
        assertEquals(
            AutomationRouter.AutomationAction.ToggleTorch(true),
            AutomationRouter.parseCustomUri(Uri.parse("zver://torch?on=1"))
        )
        assertEquals(
            AutomationRouter.AutomationAction.SetSentinelArmed(true),
            AutomationRouter.parseCustomUri(Uri.parse("zver://sentinel?arm=1"))
        )
        assertEquals(
            AutomationRouter.AutomationAction.SwitchMode("NIGHT"),
            AutomationRouter.parseCustomUri(Uri.parse("zver://mode?name=night"))
        )
        assertEquals(
            AutomationRouter.AutomationAction.SetRamOnly(false),
            AutomationRouter.parseCustomUri(Uri.parse("zver://ram?on=0"))
        )
        assertNull(AutomationRouter.parseCustomUri(Uri.parse("zver://mode?name=INVALID_SHELL")))
        assertNull(AutomationRouter.parseCustomUri(Uri.parse("http://evil.example/capture")))

        val sentinelIntent = Intent(AutomationRouter.ACTION_ARM_SENTINEL).putExtra("armed", true)
        assertEquals(
            AutomationRouter.AutomationAction.SetSentinelArmed(true),
            AutomationRouter.parseBroadcastIntent(sentinelIntent)
        )

        // Cold-launch queueing before GhostCameraScreen binds callbacks
        var capturedMode: String? = null
        MainActivity.dispatchOrQueueAction(context, AutomationRouter.AutomationAction.SwitchMode("VISOR"))
        assertNull("Should remain queued while camera screen triggers are unbound", capturedMode)

        MainActivity.onVolumeShutterTrigger = {}
        MainActivity.onModeSwitchTrigger = { capturedMode = it }
        MainActivity.flushPendingAction(context)
        assertEquals("VISOR", capturedMode)
    }

    @Test
    fun proCameraControlPanelRendersAwbLockFpsChipsAndReset() {
        val testProfile = CameraCapabilityProfiler.CameraProProfile(
            cameraId = "0",
            lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
            hardwareLevelName = "FULL",
            socModel = "Helio G85",
            isoRange = 100..3200,
            exposureTimeNsRange = 1_000_000L..250_000_000L,
            minFocusDiopters = 10f,
            isManualFocusSupported = true,
            evRange = -4..4,
            evStep = 0.5f,
            maxZoomRatio = 4.0f,
            supportsManualSensorCapability = true,
            supportsManualExposure = true,
            supportsAeLock = true,
            supportsAwbLock = true,
            availableAwbModes = listOf(
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            ),
            maxJpegRes = "4000x3000",
            maxYuvRes = "1920x1080",
            ultraHighResSupported = false,
            halSafetyNoteRu = "HAL FULL",
            availableFpsRanges = listOf(15..30, 30..30),
            hasRawCapability = true
        )

        var awbLockedClicks = 0
        var resetClicks = 0
        var selectedFps: IntRange? = 15..30

        composeRule.setContent {
            ProCameraControlPanel(
                profile = testProfile,
                exposureIndex = 2,
                onExposureIndexChange = {},
                awbPreset = CameraCapabilityProfiler.AwbPreset.AUTO,
                onAwbPresetChange = {},
                manualFocusNorm = null,
                onManualFocusChange = {},
                isoOverride = null,
                onIsoChange = {},
                shutterNsOverride = null,
                onShutterNsChange = {},
                isAeLocked = false,
                onAeLockChange = {},
                isAwbLocked = false,
                onAwbLockChange = { awbLockedClicks++ },
                targetFpsRange = selectedFps,
                onTargetFpsRangeChange = { selectedFps = it },
                onResetPro = { resetClicks++ },
                photoIqProfile = PhotoIqEngine.PhotoIqProfile.OFF,
                onPhotoIqChange = {},
                isHistogramEnabled = false,
                onToggleHistogram = {},
                isZebraEnabled = false,
                onToggleZebra = {}
            )
        }

        composeRule.onNodeWithTag("pro_toggle_awbl").assertIsDisplayed().performClick()
        assertEquals(1, awbLockedClicks)

        composeRule.onNodeWithTag("pro_reset_btn").assertIsDisplayed().performClick()
        assertEquals(1, resetClicks)

        composeRule.onNodeWithText("Фокус / ISO").performClick()
        composeRule.onNodeWithTag("pro_fps_auto").assertIsDisplayed().performClick()
        assertNull(selectedFps)
        composeRule.onNodeWithText("HAL: JPEG 4000x3000 • AF=10.0D • RAW=Да").assertIsDisplayed()
    }
}
