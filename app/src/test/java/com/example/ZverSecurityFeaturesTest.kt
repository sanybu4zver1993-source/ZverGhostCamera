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
}
