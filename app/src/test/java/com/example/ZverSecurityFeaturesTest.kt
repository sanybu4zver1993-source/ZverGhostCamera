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
}
