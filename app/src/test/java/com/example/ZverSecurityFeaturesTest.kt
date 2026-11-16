package com.example

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.example.camera.ExifStripper
import com.example.crypto.GhostCryptoVault
import com.example.privacy.OfflinePrivacyEngine
import com.example.security.ZverPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZverSecurityFeaturesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        ZverPrefs.resetFailedPinAttempts(context)
        ZverPrefs.setDeadManDays(context, 0)
    }

    @After
    fun tearDown() {
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
    }

    @Test
    fun testRamOnlyVaultAddReadShredAndExtensionDetection() {
        val fakeWebp = "RIFF\u0010\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        val fakeJpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte())

        assertEquals("webp", GhostCryptoVault.detectMediaExtension(fakeWebp, isVideo = false))
        assertEquals("jpg", GhostCryptoVault.detectMediaExtension(fakeJpg, isVideo = false))
        assertEquals("mp4", GhostCryptoVault.detectMediaExtension(fakeJpg, isVideo = true))

        val shot1 = GhostCryptoVault.addRamShot(fakeWebp.copyOf(), isVideo = false)
        val shot2 = GhostCryptoVault.addRamShot(fakeJpg.copyOf(), isVideo = true)

        val list = GhostCryptoVault.listRamShots()
        assertEquals(2, list.size)
        assertArrayEquals(fakeWebp, GhostCryptoVault.getRamShotBytes(shot1.id))
        assertArrayEquals(fakeJpg, GhostCryptoVault.getRamShotBytes(shot2.id))

        assertTrue(GhostCryptoVault.shredRamShot(shot1.id))
        assertNull(GhostCryptoVault.getRamShotBytes(shot1.id))
        assertEquals(1, GhostCryptoVault.listRamShots().size)

        val cleared = GhostCryptoVault.clearRamShots()
        assertEquals(1, cleared)
        assertTrue(GhostCryptoVault.listRamShots().isEmpty())
    }

    @Test
    fun testThreeContourPinsAndDuressShredsMainPreservesDecoy() {
        GhostCryptoVault.setupVaultPins(
            context = context,
            mainPin = "112233".toCharArray(),
            decoyPin = "445566".toCharArray(),
            duressPin = "999000".toCharArray()
        )
        assertTrue(GhostCryptoVault.isConfigured(context))
        assertTrue(GhostCryptoVault.hasDecoyConfigured(context))
        assertTrue(GhostCryptoVault.hasDuressConfigured(context))

        // Add RAM shot to verify duress shreds volatile RAM shots too
        GhostCryptoVault.addRamShot(byteArrayOf(1, 2, 3, 4), isVideo = false)
        assertEquals(1, GhostCryptoVault.listRamShots().size)

        // Wrong PIN -> INVALID
        assertEquals(
            GhostCryptoVault.UnlockOutcome.INVALID,
            GhostCryptoVault.unlockWithPinOutcome(context, "000000".toCharArray())
        )

        // Main PIN -> MAIN
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "112233".toCharArray())
        )
        assertEquals(GhostCryptoVault.VaultProfile.MAIN, GhostCryptoVault.activeProfile)

        // Duress PIN -> DURESS_WIPED, wipes MAIN + RAM + duress keys, opens DECOY
        assertEquals(
            GhostCryptoVault.UnlockOutcome.DURESS_WIPED,
            GhostCryptoVault.unlockWithPinOutcome(context, "999000".toCharArray())
        )
        assertEquals(GhostCryptoVault.VaultProfile.DECOY, GhostCryptoVault.activeProfile)
        assertFalse(GhostCryptoVault.isConfigured(context))
        assertFalse(GhostCryptoVault.hasDuressConfigured(context))
        assertTrue(GhostCryptoVault.listRamShots().isEmpty())

        // Old MAIN PIN must no longer work
        assertEquals(
            GhostCryptoVault.UnlockOutcome.INVALID,
            GhostCryptoVault.unlockWithPinOutcome(context, "112233".toCharArray())
        )

        // DECOY PIN still unlocks Decoy cleanly
        assertEquals(
            GhostCryptoVault.UnlockOutcome.DECOY,
            GhostCryptoVault.unlockWithPinOutcome(context, "445566".toCharArray())
        )
    }

    @Test
    fun testDeadManSwitchWipesVaultAfterInactivityThreshold() {
        GhostCryptoVault.setupVaultPins(
            context = context,
            mainPin = "123456".toCharArray(),
            decoyPin = null,
            duressPin = null
        )
        ZverPrefs.setDeadManDays(context, 7)

        val now = System.currentTimeMillis()
        // Within 7 days -> should NOT wipe
        assertFalse(GhostCryptoVault.checkDeadManSwitch(context, nowMs = now + 3L * 86_400_000L))
        assertTrue(GhostCryptoVault.isConfigured(context))

        // Past 7 days -> MUST wipe
        assertTrue(GhostCryptoVault.checkDeadManSwitch(context, nowMs = now + 8L * 86_400_000L))
        assertFalse(GhostCryptoVault.isConfigured(context))
    }

    @Test
    fun testPinLockoutEscalationAndPreferences() {
        ZverPrefs.setCompressionPreset(context, ExifStripper.CompressionPreset.DOCUMENT_BW)
        assertEquals(ExifStripper.CompressionPreset.DOCUMENT_BW, ZverPrefs.getCompressionPreset(context))

        assertEquals(1, ZverPrefs.incrementFailedPinAttempts(context))
        assertEquals(2, ZverPrefs.incrementFailedPinAttempts(context))
        assertEquals(0L, ZverPrefs.getLockoutUntilMs(context))

        // 3rd failure triggers 15s lockout
        assertEquals(3, ZverPrefs.incrementFailedPinAttempts(context))
        assertTrue(ZverPrefs.getLockoutUntilMs(context) > System.currentTimeMillis())

        ZverPrefs.resetFailedPinAttempts(context)
        assertEquals(0, ZverPrefs.getFailedPinAttempts(context))
        assertEquals(0L, ZverPrefs.getLockoutUntilMs(context))
    }

    @Test
    fun testOfflineOsintRiskLinterDetectsLeaks() {
        val clean = OfflinePrivacyEngine.assessOsintRisks(
            OfflinePrivacyEngine.DetectionResult(emptyList(), emptyList(), "")
        )
        assertEquals(0, clean.riskScore)
        assertTrue(clean.warnings.isEmpty())

        val risky = OfflinePrivacyEngine.assessOsintRisks(
            OfflinePrivacyEngine.DetectionResult(
                faces = listOf(
                    OfflinePrivacyEngine.NormalizedRegion(
                        "face_0",
                        RectF(0.1f, 0.1f, 0.4f, 0.4f),
                        OfflinePrivacyEngine.RegionKind.FACE
                    )
                ),
                textBlocks = listOf(
                    OfflinePrivacyEngine.NormalizedRegion(
                        "txt_0",
                        RectF(0.5f, 0.5f, 0.9f, 0.7f),
                        OfflinePrivacyEngine.RegionKind.TEXT
                    )
                ),
                extractedText = "CB 1234 AA +380501234567 51.498200 contact@example.org"
            )
        )
        assertEquals(100, risky.riskScore)
        assertTrue(risky.warnings.any { it.contains("Лица") })
        assertTrue(risky.warnings.any { it.contains("госномер") })
        assertTrue(risky.warnings.any { it.contains("телефон") })
        assertTrue(risky.warnings.any { it.contains("координат") })
        assertTrue(risky.warnings.any { it.contains("e-mail") })
    }

    @Test
    fun testByokModelSortingAndVaultAnalytics() {
        val rawModels = listOf(
            com.example.assist.ByokManager.DiscoveredModel("gemma-3-27b-it", "Gemma 3", "", 32000, 8192),
            com.example.assist.ByokManager.DiscoveredModel("gemini-1.5-pro", "Gemini 1.5 Pro", "", 1000000, 8192),
            com.example.assist.ByokManager.DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash", "", 1000000, 65536),
            com.example.assist.ByokManager.DiscoveredModel("gemini-4-hydrogen", "Gemini 4 Hydrogen", "", 1000000, 65536)
        )
        val sorted = com.example.assist.GeminiByokClient.sortDiscoveredModels(rawModels)
        assertEquals("gemini-4-hydrogen", sorted.first().id)
        assertEquals("gemma-3-27b-it", sorted.last().id)

        com.example.assist.ByokManager.clearSessionMemory()
        com.example.assist.ByokManager.recordStats("gemini-4-hydrogen", 120, 80, 200, 340L, success = true)
        val stats = com.example.assist.ByokManager.getStats()
        assertEquals(1, stats.queriesCount)
        assertEquals(200, stats.totalTokens)
        assertEquals(340L, stats.lastLatencyMs)

        GhostCryptoVault.addRamShot(byteArrayOf(1, 2, 3, 4, 5, 6), isVideo = false)
        val snap = com.example.security.VaultAnalytics.captureSnapshot(context)
        assertEquals(1, snap.ramShotsCount)
        assertTrue(snap.readinessMax >= 7)
        val summary = com.example.security.VaultAnalytics.buildTelemetrySummaryForAi(context)
        assertTrue(summary.contains("gemini-4-hydrogen") || summary.contains("ОЗУ-сейф"))
    }

    @Test
    fun testSentinelEngineLingerKnockAndClahePipeline() {
        com.example.security.SentinelEngine.clearSession()
        com.example.security.SentinelEngine.updateConfig {
            it.copy(lingerThresholdSec = 4, cooldownSec = 25, motionPercentThreshold = 8)
        }
        val t0 = 1_000_000L
        com.example.security.SentinelEngine.setArmed(true, nowMs = t0)
        assertTrue(com.example.security.SentinelEngine.isArmed())

        // 1. Short neighbor pass (1.5s motion -> quiet) should be ignored, NOT trigger alarm
        assertTrue(com.example.security.SentinelEngine.evaluateMotionFrame(t0 + 100, 15, 45) is com.example.security.SentinelEngine.SentinelStepResult.Tracking)
        assertTrue(com.example.security.SentinelEngine.evaluateMotionFrame(t0 + 1_600, 18, 45) is com.example.security.SentinelEngine.SentinelStepResult.Tracking)
        val ignored = com.example.security.SentinelEngine.evaluateMotionFrame(t0 + 2_600, 0, 45)
        assertTrue(ignored is com.example.security.SentinelEngine.SentinelStepResult.NeighborIgnored)

        // 2. Sustained linger >= 4s in low light (avgLuma=45) triggers alarm with nightClahe=true
        val tLinger = t0 + 3_000
        assertTrue(com.example.security.SentinelEngine.evaluateMotionFrame(tLinger, 22, 45) is com.example.security.SentinelEngine.SentinelStepResult.Tracking)
        val alarm = com.example.security.SentinelEngine.evaluateMotionFrame(tLinger + 4_100, 25, 45)
        assertTrue(alarm is com.example.security.SentinelEngine.SentinelStepResult.TriggerLingerCapture)
        assertTrue((alarm as com.example.security.SentinelEngine.SentinelStepResult.TriggerLingerCapture).nightClahe)

        // 3. During 25s cooldown, knock impulse & motion are throttled (anti-spam)
        assertTrue(com.example.security.SentinelEngine.evaluateMotionFrame(tLinger + 6_000, 30, 45) is com.example.security.SentinelEngine.SentinelStepResult.Cooldown)
        assertFalse(com.example.security.SentinelEngine.evaluateKnockImpulse(tLinger + 7_000, 9.5f))

        // 4. After cooldown, knock triggers immediately
        assertTrue(com.example.security.SentinelEngine.evaluateKnockImpulse(tLinger + 30_000, 10.2f))

        // 5. Verify CLAHE anti-glare + region crop pipeline (ShadowBitmap in Robolectric produces PNG mock container -> verify 0 metadataBytes & non-empty crop)
        val bmp = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val v = ((x + y) / 2).coerceIn(10, 120)
                bmp.setPixel(x, y, android.graphics.Color.rgb(v, v, v))
            }
        }
        val claheBmp = ExifStripper.applyContrastDehaze(bmp)
        assertEquals(128, claheBmp.width)
        assertEquals(128, claheBmp.height)
        val crop = ExifStripper.extractRegionCropClean(
            sourceBitmap = claheBmp,
            normalizedRect = RectF(0.2f, 0.2f, 0.6f, 0.6f),
            paddingRatio = 0.40f
        )
        assertNotNull(crop)
        assertTrue(crop!!.cleanBytes.isNotEmpty())
        assertEquals(0, crop.audit.metadataBytes)
        bmp.recycle()
        claheBmp.recycle()

        // 6. Verify AntiBlurSensorEngine 12x12 Y-plane motion & Day/Night luma detector
        val sensorEngine = com.example.camera.AntiBlurSensorEngine(context)
        val frameDark = java.nio.ByteBuffer.wrap(ByteArray(96 * 96) { 25.toByte() })
        val frameShifted = java.nio.ByteBuffer.wrap(ByteArray(96 * 96) { 180.toByte() })
        val m1 = sensorEngine.computeMotionAndLuma(frameDark, 96, 96, 96, null)
        assertEquals(0, m1.motionScore)
        assertTrue(m1.isLowLight)
        val m2 = sensorEngine.computeMotionAndLuma(frameShifted, 96, 96, 96, m1.grid)
        assertEquals(100, m2.motionScore)
        assertFalse(m2.isLowLight)

        com.example.security.SentinelEngine.clearSession()
    }

    @Test
    fun testMultiNnSymbiosisAndQuotaFallback() {
        val sampleBmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val c = if ((x + y) % 4 == 0) 200 else 30
                sampleBmp.setPixel(x, y, android.graphics.Color.rgb(c, c, c))
            }
        }
        val q = com.example.privacy.LocalSymbiosisCvEngine.assessFrameQuality(sampleBmp)
        assertTrue(q.sharpnessScore > 0)
        sampleBmp.recycle()

        val mockReport = com.example.privacy.LocalSymbiosisCvEngine.SymbiosisReport(
            faces = listOf(
                com.example.privacy.LocalSymbiosisCvEngine.FaceSummary(
                    rect = RectF(0.2f, 0.2f, 0.5f, 0.5f),
                    yawDeg = 6f,
                    rollDeg = -2f
                )
            ),
            objects = listOf(
                com.example.privacy.LocalSymbiosisCvEngine.DetectedObjectSummary(
                    rect = RectF(0.1f, 0.1f, 0.7f, 0.8f),
                    label = "одежда/аксессуар",
                    confidence = 0.88f
                )
            ),
            sceneTags = listOf(
                com.example.privacy.LocalSymbiosisCvEngine.SceneTag("Cat", 0.91f),
                com.example.privacy.LocalSymbiosisCvEngine.SceneTag("Foliage", 0.76f)
            ),
            pose = com.example.privacy.LocalSymbiosisCvEngine.PoseSummary(
                detected = true,
                visibleLandmarks = 18,
                postureRu = "фигура человека (18/33 точек)",
                torsoRect = RectF(0.15f, 0.15f, 0.65f, 0.9f)
            ),
            extractedText = "AA 1234 BB",
            osintReport = com.example.privacy.OfflinePrivacyEngine.OsintRiskReport(
                warnings = listOf("Лица в кадре: 1"),
                riskScore = 40
            ),
            quality = q,
            foregroundCoveragePct = 34.5f,
            inferenceMs = 42L
        )

        assertEquals("лицо", mockReport.bestRoiKindLabel())
        val dossier = com.example.privacy.LocalSymbiosisCvEngine.buildCompactGeminiDossier(mockReport)
        assertTrue(dossier.contains("6-NN"))
        assertTrue(dossier.contains("Cat(91%)"))
        assertTrue(dossier.contains("AA 1234 BB"))

        val offlineReply = com.example.privacy.LocalSymbiosisCvEngine.buildOfflineVikaReport(
            report = mockReport,
            userTask = "посмотри чё там",
            fallbackReason = "Лимит 429"
        )
        assertTrue(offlineReply.contains("МАЛЫШЕЙ ОФЛАЙН"))
        assertTrue(offlineReply.contains("Cat 91%"))
        assertTrue(offlineReply.contains("AA 1234 BB"))

        assertTrue(com.example.assist.GeminiByokClient.isQuotaOrNetworkFailure("Лимит запросов исчерпан (429)"))
        assertTrue(com.example.assist.GeminiByokClient.isQuotaOrNetworkFailure("Нет сети или DNS не отвечает"))
        assertFalse(com.example.assist.GeminiByokClient.isQuotaOrNetworkFailure("Неверный синтаксис промпта"))

        assertEquals(120, com.example.camera.VideoKeyframeExtractor.RECOMMENDED_MAX_SECONDS)
        assertEquals(5, com.example.camera.VideoKeyframeExtractor.MAX_KEYFRAMES)
    }

    @Test
    fun testCyberVisorRppgAndScreenshotsUnlockedByDefault() {
        // 1. Screenshots and screen video recording MUST be unlocked by default
        assertFalse(ZverPrefs.isScreenRecBlockEnabled(context))

        // 2. Pulse BPM + HRV persistence for CyberVisor ("Био-Визор")
        ZverPrefs.saveLastPulseBpm(context, 74, 1_700_000_000_000L, hrvMs = 52)
        assertEquals(74, ZverPrefs.getLastPulseBpm(context))
        assertEquals(52, ZverPrefs.getLastHrvMs(context))
        assertEquals(1_700_000_000_000L, ZverPrefs.getLastPulseTimestampMs(context))
        ZverPrefs.clearLastPulseBpm(context)
        assertEquals(0, ZverPrefs.getLastPulseBpm(context))
        assertEquals(0, ZverPrefs.getLastHrvMs(context))

        // 3. Synthetic ~72 BPM (1.2 Hz) photoplethysmographic skin oscillation with slight respiratory IBI variability at 30 FPS (180 frames = 6s)
        val tracker = com.example.visor.PosRppgTracker()
        var lastResult = Triple<Int?, Int?, Float>(null, null, 0f)
        for (i in 0 until 180) {
            val tSec = i / 30.0
            val tsNs = (tSec * 1_000_000_000.0).toLong()
            val phaseMod = 0.08 * kotlin.math.sin(2.0 * kotlin.math.PI * 0.25 * tSec)
            val pulse = kotlin.math.sin(2.0 * kotlin.math.PI * 1.2 * tSec + phaseMod) // ~72 BPM with RSA HRV
            val meanR = 175.0 + 0.35 * pulse
            val meanG = 132.0 + 1.45 * pulse
            val meanB = 115.0 + 0.45 * pulse
            lastResult = tracker.pushSample(tsNs, meanR, meanG, meanB)
        }
        val detectedBpm = lastResult.first
        val detectedHrv = lastResult.second
        val quality = lastResult.third
        assertNotNull("Expected valid BPM estimate for clean ~72 BPM pulse wave", detectedBpm)
        assertTrue("Expected ~72 BPM, got $detectedBpm", detectedBpm!! in 68..76)
        assertNotNull("Expected non-null HRV RMSSD for modulated pulse train", detectedHrv)
        assertTrue("Expected physiological HRV range, got $detectedHrv", detectedHrv!! in 5..220)
        assertTrue("Expected solid SNR quality, got $quality", quality >= 0.45f)

        // 4. Eulerian Temporal Band-Pass Amplifier: tiny +4 green delta must be strongly magnified (> +30)
        val euler = com.example.visor.EulerianTemporalAmplifier(gain = 20f)
        val basePixel = (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100
        val pulsePixel = (0xFF shl 24) or (100 shl 16) or (104 shl 8) or 100
        val srcInit = IntArray(4) { basePixel }
        val dst = IntArray(4)
        euler.amplifyInto(srcInit, dst, 2, 2)
        val srcPulse = IntArray(4) { pulsePixel }
        euler.amplifyInto(srcPulse, dst, 2, 2)
        val amplifiedGreen = (dst[0] ushr 8) and 0xFF
        assertTrue("Expected amplified green > 130 from +4 change, got $amplifiedGreen", amplifiedGreen > 130)
    }

    @Test
    fun testYcbcrSkinAnalyzerAndSymbiosisIntegration() {
        // Warm human skin matches; neutral gray and green foliage are rejected
        assertTrue(com.example.visor.YcbcrSkinAnalyzer.isHumanSkinRgb(195, 145, 118))
        assertFalse(com.example.visor.YcbcrSkinAnalyzer.isHumanSkinRgb(140, 140, 140))
        assertFalse(com.example.visor.YcbcrSkinAnalyzer.isHumanSkinRgb(50, 175, 60))
        assertFalse(com.example.visor.YcbcrSkinAnalyzer.isHumanSkinYCbCr(10, 105, 150)) // deep black clipping guard
        // 256x256 LUT matches exact predicate and adaptive scene offset works
        assertEquals(
            com.example.visor.YcbcrSkinAnalyzer.isHumanSkinYCbCr(120, 105, 150),
            com.example.visor.YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(120, 105, 150)
        )
        assertTrue(com.example.visor.YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(120, 109, 154, cbShift = 4, crShift = 4))

        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val color = if (x in 20..43 && y in 16..47) {
                    android.graphics.Color.rgb(196, 142, 115)
                } else {
                    android.graphics.Color.rgb(45, 52, 58)
                }
                bmp.setPixel(x, y, color)
            }
        }
        val skin = com.example.visor.YcbcrSkinAnalyzer.analyzeBitmap(bmp)
        assertTrue("Expected coherent skin blob", skin.isCoherentSkinPresent)
        assertTrue("Expected compactness >= 0.7, got ${skin.compactness}", skin.compactness >= 0.7f)
        bmp.recycle()

        val skinOnlyReport = com.example.privacy.LocalSymbiosisCvEngine.SymbiosisReport(
            faces = emptyList(),
            objects = emptyList(),
            sceneTags = emptyList(),
            pose = com.example.privacy.LocalSymbiosisCvEngine.PoseSummary(false, 0, "нет", null),
            extractedText = "",
            osintReport = OfflinePrivacyEngine.OsintRiskReport(emptyList(), 0),
            quality = com.example.privacy.LocalSymbiosisCvEngine.FrameQuality(120, false, 20, false),
            foregroundCoveragePct = null,
            inferenceMs = 12L,
            skinMetrics = skin
        )
        assertEquals("кожа(YCbCr)", skinOnlyReport.bestRoiKindLabel())
        assertNotNull(skinOnlyReport.bestRoiRegion())
        val dossier = com.example.privacy.LocalSymbiosisCvEngine.buildCompactGeminiDossier(skinOnlyReport)
        assertTrue(dossier.contains("Кожа(YCbCr)"))
        assertTrue(dossier.contains("скрыто"))
    }

    @Test
    fun testPosRppgAeJumpResilienceAndGreenFallback() {
        // 1. +40% AE jump at frame 90 must NOT destroy 72 BPM (1.2 Hz) lock
        val aeTracker = com.example.visor.PosRppgTracker()
        var resAe = Triple<Int?, Int?, Float>(null, null, 0f)
        for (i in 0 until 180) {
            val tSec = i / 30.0
            val tsNs = (tSec * 1_000_000_000.0).toLong()
            val gainMul = if (i >= 90) 1.40 else 1.0
            val pulse = kotlin.math.sin(2.0 * kotlin.math.PI * 1.2 * tSec)
            val r = (140.0 + 0.30 * pulse) * gainMul
            val g = (108.0 + 1.25 * pulse) * gainMul
            val b = (92.0 + 0.35 * pulse) * gainMul
            resAe = aeTracker.pushSample(tsNs, r, g, b, greenFallback = false)
        }
        assertNotNull("Expected valid BPM across +40% AE step jump", resAe.first)
        assertTrue("Expected ~72 BPM after AE rebase, got ${resAe.first}", resAe.first!! in 66..78)

        // 2. Finger-on-lens / Red-clipped green fallback (~78 BPM = 1.3 Hz)
        val fingerTracker = com.example.visor.PosRppgTracker()
        var resFinger = Triple<Int?, Int?, Float>(null, null, 0f)
        for (i in 0 until 180) {
            val tSec = i / 30.0
            val tsNs = (tSec * 1_000_000_000.0).toLong()
            val pulse = kotlin.math.sin(2.0 * kotlin.math.PI * 1.3 * tSec)
            resFinger = fingerTracker.pushSample(tsNs, 250.0, 95.0 + 1.6 * pulse, 70.0, greenFallback = true)
        }
        assertNotNull("Expected valid BPM in green fallback mode", resFinger.first)
        assertTrue("Expected ~78 BPM in green fallback, got ${resFinger.first}", resFinger.first!! in 72..84)
    }

    @Test
    fun testEulerStabilizationAntiPrnuMeshAndJpegAudit() {
        // 1. Euler 1D stabilization on 32x32 frame
        val euler = com.example.visor.EulerianTemporalAmplifier(gain = 18f)
        val n = 32 * 32
        val f1 = IntArray(n) { i ->
            val v = if ((i % 32) in 10..20) 150 else 70
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val f2 = IntArray(n) { i ->
            val x = i % 32
            val v = if (x in 11..21) 154 else 70
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = IntArray(n)
        euler.amplifyInto(f1, out, 32, 32)
        euler.amplifyInto(f2, out, 32, 32)
        assertTrue(euler.lastStability in 0.1f..1f)

        // 2. ExifStripper Anti-PRNU elastic mesh + honest JPEG audit
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(80, 120, 160))
        val cleanRes = ExifStripper.processBitmapClean(
            sourceBitmap = bmp,
            recycleSource = true,
            preset = ExifStripper.CompressionPreset.BALANCED,
            antiPrnu = true
        )
        assertTrue(cleanRes.cleanBytes.isNotEmpty())
        assertEquals(0, cleanRes.audit.metadataBytes)

        val minimalJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x04, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x11, 0x22,
            0xFF.toByte(), 0xD9.toByte()
        )
        val stripped = ExifStripper.stripJpegSegments(minimalJpeg)
        val audit = ExifStripper.auditBytes(stripped)
        assertEquals("JPEG", audit.format)
        assertEquals(0, audit.metadataBytes)
        assertTrue(audit.notes.any { it.contains("DQT") })
    }

    @Test
    fun testOsintLuhnCardSecretsJitteredMosaicAndSecureClipboard() {
        // Luhn valid card (4111111111111111) + secret leak
        val leakReport = OfflinePrivacyEngine.assessOsintRisks(
            OfflinePrivacyEngine.DetectionResult(
                faces = emptyList(),
                textBlocks = emptyList(),
                extractedText = "Card 4111 1111 1111 1111 password=MySecret99"
            )
        )
        assertTrue(leakReport.warnings.any { it.contains("Luhn") })
        assertTrue(leakReport.warnings.any { it.contains("пароль") })

        // Luhn invalid card (4111111111111112) must NOT flag Luhn card warning
        val nonCardReport = OfflinePrivacyEngine.assessOsintRisks(
            OfflinePrivacyEngine.DetectionResult(
                faces = emptyList(),
                textBlocks = emptyList(),
                extractedText = "Serial 4111 1111 1111 1112"
            )
        )
        assertFalse(nonCardReport.warnings.any { it.contains("Luhn") })

        // Jittered PIXELATE redaction mutates pixels cleanly
        val src = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                src.setPixel(x, y, android.graphics.Color.rgb((x * 5) % 255, (y * 5) % 255, 100))
            }
        }
        val redacted = OfflinePrivacyEngine.applyRedactions(
            source = src,
            regions = listOf(
                OfflinePrivacyEngine.NormalizedRegion(
                    "r1",
                    RectF(0.1f, 0.1f, 0.9f, 0.9f),
                    OfflinePrivacyEngine.RegionKind.FACE
                )
            ),
            style = OfflinePrivacyEngine.RedactionStyle.PIXELATE,
            invertMode = false
        )
        assertEquals(48, redacted.width)
        src.recycle()
        redacted.recycle()

        // SecureClipboard sensitive flag & owned clear
        com.example.security.SecureClipboard.copySensitive(context, "Zver Test", "74 BPM", ttlMs = 0L)
        assertTrue(com.example.security.SecureClipboard.isCurrentlyOwnedByZver(context))
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertTrue(
            cm.primaryClipDescription?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        )
        assertTrue(com.example.security.SecureClipboard.clearIfOwned(context))
        assertFalse(com.example.security.SecureClipboard.isCurrentlyOwnedByZver(context))
    }
}
