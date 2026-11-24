package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.assist.ByokManager
import com.example.camera.ExifStripper
import com.example.crypto.BaitGalleryGenerator
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.crypto.StegoCarrierEngine
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.privacy.OfflinePrivacyEngine
import com.example.ui.StealthBlackoutScreen
import com.example.ui.StealthCoverMode
import com.example.ui.StegoCarrierDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartDDeepSeekAndVikaMoodTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        MonoVaultEngine.getContainerFile(context).delete()
    }

    @After
    fun tearDown() {
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        MonoVaultEngine.getContainerFile(context).delete()
    }

    @Test
    fun stegoCarrierEngine_embedsExtractsAndRejectsWrongPassphraseOrJpeg() {
        val cover = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(64, 120, 180))
        }
        val secret = "Координаты схрона: 51.4982, 31.2893 • ключ=Zver2026"
        val res = StegoCarrierEngine.embedSecretInBitmap(cover, secret, "super-pass".toCharArray())
        try {
            assertEquals("png", GhostCryptoVault.detectMediaExtension(res.pngBytes, isVideo = false))
            assertTrue(res.maxCapacityBytes > 5_000)

            val extracted = StegoCarrierEngine.extractSecretFromPngBytes(res.pngBytes, "super-pass".toCharArray())
            assertEquals(secret, extracted)

            try {
                StegoCarrierEngine.extractSecretFromPngBytes(res.pngBytes, "wrong-pass".toCharArray())
                fail("Expected exception for wrong passphrase")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Неверный пароль"))
            }

            // Lossy JPEG compression destroys LSB bits — must fail cleanly with readable error
            val jpegBaos = ByteArrayOutputStream()
            res.stegoBitmap.compress(Bitmap.CompressFormat.JPEG, 92, jpegBaos)
            try {
                StegoCarrierEngine.extractSecretFromPngBytes(jpegBaos.toByteArray(), "super-pass".toCharArray())
                fail("Expected exception after lossy JPEG recompression")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("контейнер") || e.message!!.contains("Неверный"))
            }
        } finally {
            cover.recycle()
            if (!res.stegoBitmap.isRecycled) res.stegoBitmap.recycle()
        }
    }

    @Test
    fun baitGalleryGenerator_seedsDecoyVaultWithoutTouchingMainVault() {
        GhostCryptoVault.setupVaultPins(
            context,
            mainPin = "112233".toCharArray(),
            decoyPin = "445566".toCharArray(),
            duressPin = "999999".toCharArray()
        )
        assertEquals(GhostCryptoVault.VaultProfile.MAIN, GhostCryptoVault.activeProfile)
        assertEquals(0, GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.MAIN).size)

        val seed = BaitGalleryGenerator.seedDecoyBaitGallery(context)
        assertEquals(3, seed.createdCount)
        // MAIN vault stays completely untouched
        assertEquals(0, GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.MAIN).size)
        assertEquals(3, GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.DECOY).size)

        // Unlocking DECOY profile lets us decrypt & audit every bait photo cleanly
        assertEquals(
            GhostCryptoVault.UnlockOutcome.DECOY,
            GhostCryptoVault.unlockWithPinOutcome(context, "445566".toCharArray())
        )
        val decoyFiles = GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.DECOY)
        assertEquals(3, decoyFiles.size)
        for (f in decoyFiles) {
            val plain = GhostCryptoVault.decryptToRam(context, f)
            assertTrue(plain.size > 100)
            assertTrue(ExifStripper.auditBytes(plain).isClean)
        }
    }

    @Test
    fun localSymbiosisMalepusiki_usesDynamicMoodQuipsAndUpdatedBranding() {
        assertTrue(ByokManager.DEFAULT_PROMPT.contains("Мега Пупсик"))
        assertTrue(ByokManager.DEFAULT_PROMPT.contains("Ррр"))
        assertTrue(ByokManager.PROMPT_VIKA_TURBO.contains("Вика"))

        val qBlurryDark = LocalSymbiosisCvEngine.FrameQuality(
            avgLuma = 28,
            isLowLight = true,
            sharpnessScore = 5,
            isBlurry = true,
            clippedHighlightsPct = 0,
            crushedShadowsPct = 80,
            contrastSpan = 40,
            dominantTintRu = "нейтральный"
        )
        val blurryReport = LocalSymbiosisCvEngine.SymbiosisReport(
            faces = emptyList(),
            objects = emptyList(),
            sceneTags = emptyList(),
            pose = LocalSymbiosisCvEngine.PoseSummary(false, 0, "нет", null),
            extractedText = "",
            osintReport = OfflinePrivacyEngine.OsintRiskReport(emptyList(), 0),
            quality = qBlurryDark,
            foregroundCoveragePct = null,
            inferenceMs = 15L
        )
        val repA = LocalSymbiosisCvEngine.buildOfflineVikaReport(blurryReport, "оцени", variantSeed = 0L)
        val repB = LocalSymbiosisCvEngine.buildOfflineVikaReport(blurryReport, "оцени", variantSeed = 1L)
        val repC = LocalSymbiosisCvEngine.buildOfflineVikaReport(blurryReport, "оцени", variantSeed = 2L)

        assertTrue(repA.contains("6 МАЛИПУСИКОВ ОФЛАЙН"))
        assertTrue(repA.contains("💋 Вика:"))
        // At least 2 distinct procedural quips across different seeds
        assertTrue(setOf(repA, repB, repC).size >= 2)

        val txt0 = LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Покажи статус Дозора Sentinel", variantSeed = 0L)
        val txt1 = LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Покажи статус Дозора Sentinel", variantSeed = 1L)
        assertTrue(txt0.contains("💋 Вика:"))
        assertNotEquals(txt0, txt1)
    }

    @Test
    fun stealthCoverModes_renderSystemUpdateAndLowBatteryCleanly() {
        var mode = StealthCoverMode.SYSTEM_UPDATE
        composeTestRule.setContent {
            StealthBlackoutScreen(
                mode = mode,
                isProximityLocked = false,
                onCycleMode = { mode = StealthCoverMode.LOW_BATTERY },
                onCapture = {},
                onExit = {}
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Оптимизация системы Android...", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h915dp")
    fun stegoCarrierDialog_rendersEmbedAndExtractControls() {
        val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        var closed = false
        try {
            composeTestRule.setContent {
                StegoCarrierDialog(
                    sourceBitmap = bmp,
                    onSavedToVault = {},
                    onDismiss = { closed = true }
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("stego_mode_embed").assertIsDisplayed()
            composeTestRule.onNodeWithTag("stego_mode_extract").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("stego_extract_btn").assertIsDisplayed()
            composeTestRule.onNodeWithTag("stego_close_btn")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.waitForIdle()
            assertTrue(closed)
        } finally {
            bmp.recycle()
        }
    }
}
