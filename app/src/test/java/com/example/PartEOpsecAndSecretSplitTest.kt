package com.example

import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.crypto.SecretSplitEngine
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.security.OpsecSurfaceScanner
import com.example.security.SelfTestEngine
import com.example.ui.SecretSplitDialog
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartEOpsecAndSecretSplitTest {

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
    fun secretSplitEngine_splits2Of3AndReconstructsAnyPairAndRejectsSingleOrCorruptedShare() {
        val secret = "ZverMasterPass#2026_Чернигов!"
        val shares = SecretSplitEngine.splitSecret(secret, totalShares = 3, threshold = 2)
        assertEquals(3, shares.size)
        assertTrue(shares.all { it.encoded.startsWith("ZVS1-2-") })

        // Every combination of 2 out of 3 shares reconstructs exact UTF-8 secret
        assertEquals(secret, SecretSplitEngine.combineShares(listOf(shares[0].encoded, shares[1].encoded)))
        assertEquals(secret, SecretSplitEngine.combineShares(listOf(shares[0].encoded, shares[2].encoded)))
        assertEquals(secret, SecretSplitEngine.combineShares(listOf(shares[1].encoded, shares[2].encoded)))

        // Single share must fail
        try {
            SecretSplitEngine.combineShares(listOf(shares[0].encoded))
            fail("Expected failure with only 1 share")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Недостаточно"))
        }

        // Duplicate same share must NOT pass threshold
        try {
            SecretSplitEngine.combineShares(listOf(shares[1].encoded, shares[1].encoded))
            fail("Expected failure with duplicate shares")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Недостаточно"))
        }

        // Tampered payload triggers CRC-8 mismatch
        val tampered = shares[0].encoded.dropLast(2) + (if (shares[0].encoded.endsWith("00")) "FF" else "00")
        try {
            SecretSplitEngine.combineShares(listOf(tampered, shares[2].encoded))
            fail("Expected CRC mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CRC-8"))
        }
    }

    @Test
    fun secretSplitEngine_3Of5SchemeWorks() {
        val secret = "StegoKey!@#999"
        val shares = SecretSplitEngine.splitSecret(secret, totalShares = 5, threshold = 3)
        assertEquals(5, shares.size)
        assertEquals(secret, SecretSplitEngine.combineShares(listOf(shares[4].encoded, shares[2].encoded, shares[0].encoded)))
    }

    @Test
    fun opsecSurfaceScanner_andSelfTestEngine_reportNewOpsecChecksAndOfflineIntent() {
        val surface = OpsecSurfaceScanner.scanSurface(context)
        val summary = surface.formatCompactSummaryRu()
        assertTrue(summary.contains("СКАНЕР ПОВЕРХНОСТИ УГРОЗ"))
        assertTrue(summary.contains("Accessibility"))

        val diag = SelfTestEngine.runFullDiagnostics(context, boundUseCasesSummary = "Preview+ImageCapture")
        val ids = diag.items.map { it.id }
        assertTrue(ids.contains("opsec_displays_ui"))
        assertTrue(ids.contains("opsec_usb_network"))

        val intent = LocalSymbiosisCvEngine.classifyOfflineIntent("Проверь OPSEC поверхность угроз: дисплеи, adb, dns")
        assertEquals(LocalSymbiosisCvEngine.OfflineTaskIntent.OPSEC_SCAN, intent)

        // Ensure no regression on Sentinel Дозор routing
        assertEquals(
            LocalSymbiosisCvEngine.OfflineTaskIntent.SENTINEL_DOZOR,
            LocalSymbiosisCvEngine.classifyOfflineIntent("Покажи статус Дозора Sentinel")
        )

        val offlineReply0 = LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(
            context,
            "Проверь OPSEC сканер",
            variantSeed = 0L
        )
        val offlineReply1 = LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(
            context,
            "Проверь OPSEC сканер",
            variantSeed = 1L
        )
        assertTrue(offlineReply0.contains("СКАНЕР ПОВЕРХНОСТИ УГРОЗ"))
        assertNotEquals(offlineReply0, offlineReply1)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h915dp")
    fun secretSplitDialog_createsAndReconstructsInUi() {
        var closed = false
        composeTestRule.setContent {
            SecretSplitDialog(
                onStatusMessage = {},
                onDismiss = { closed = true }
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("split_secret_input").performTextInput("Abc123!@#")
        composeTestRule.onNodeWithTag("split_generate_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ДОЛЯ #1", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("ДОЛЯ #2", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("ДОЛЯ #3", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("split_mode_combine").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("split_reconstruct_btn").assertIsDisplayed()

        composeTestRule.onNodeWithTag("split_key_close_btn")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.waitForIdle()
        assertFalse(!closed)
        assertTrue(closed)
    }
}
