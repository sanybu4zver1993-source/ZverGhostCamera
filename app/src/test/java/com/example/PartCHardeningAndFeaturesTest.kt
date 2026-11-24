package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.privacy.OfflinePrivacyEngine
import com.example.security.SelfTestEngine
import com.example.security.SentinelEngine
import com.example.security.ZverPrefs
import com.example.ui.DecoyCalculatorScreen
import com.example.ui.PrivacyEditorDialog
import com.example.ui.SentinelControlCard
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartCHardeningAndFeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        MonoVaultEngine.getContainerFile(context).delete()
        SentinelEngine.clearSession()
        SentinelEngine.updateConfig { SentinelEngine.SentinelConfig() }
    }

    @After
    fun tearDown() {
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        MonoVaultEngine.getContainerFile(context).delete()
        SentinelEngine.clearSession()
        SentinelEngine.updateConfig { SentinelEngine.SentinelConfig() }
    }

    @Test
    fun offlinePrivacyEngine_selectiveOsintBlocksFilterOnlySensitiveText() {
        val benignRegion = OfflinePrivacyEngine.NormalizedRegion(
            id = "txt_0",
            rect = android.graphics.RectF(0.1f, 0.1f, 0.3f, 0.2f),
            kind = OfflinePrivacyEngine.RegionKind.TEXT
        )
        val plateRegion = OfflinePrivacyEngine.NormalizedRegion(
            id = "txt_1",
            rect = android.graphics.RectF(0.4f, 0.2f, 0.7f, 0.3f),
            kind = OfflinePrivacyEngine.RegionKind.TEXT
        )
        val secretRegion = OfflinePrivacyEngine.NormalizedRegion(
            id = "txt_2",
            rect = android.graphics.RectF(0.1f, 0.5f, 0.8f, 0.6f),
            kind = OfflinePrivacyEngine.RegionKind.TEXT
        )
        val cardRegion = OfflinePrivacyEngine.NormalizedRegion(
            id = "txt_3",
            rect = android.graphics.RectF(0.1f, 0.7f, 0.8f, 0.8f),
            kind = OfflinePrivacyEngine.RegionKind.TEXT
        )

        val entries = listOf(
            benignRegion to "МАГАЗИН 24 ЧАСА ВХОД",
            plateRegion to "CB 1234 AA",
            secretRegion to "api_key=AIzaSyTestSecretToken1234567890",
            cardRegion to "4111 1111 1111 1111"
        )

        val hits = OfflinePrivacyEngine.analyzeSensitiveOsintBlocksFromEntries(entries)
        assertEquals(3, hits.size)
        assertFalse(hits.any { it.region.id == "txt_0" })
        assertTrue(hits.any { it.region.id == "txt_1" && it.matchedLabels.contains("Госномер") })
        assertTrue(hits.any { it.region.id == "txt_2" && it.matchedLabels.contains("Секрет/пароль") })
        assertTrue(hits.any { it.region.id == "txt_3" && it.matchedLabels.contains("Карта (Luhn)") })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h915dp")
    fun privacyEditorDialog_exposesAutoOsintCloseTouchTargetInvertAndReset() {
        val bmp = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        var dismissed = false

        try {
            composeTestRule.setContent {
                PrivacyEditorDialog(
                    sourceBitmap = bmp,
                    onDismiss = { dismissed = true },
                    onSavedToVault = {}
                )
            }

            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("obscura_auto_osint_btn").assertIsDisplayed()
            composeTestRule.onNodeWithTag("obscura_invert_btn").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("ИНВЕРСИЯ: ВКЛ").assertIsDisplayed()
            composeTestRule.onNodeWithTag("obscura_reset_btn").assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("obscura_close_btn")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeTestRule.waitForIdle()

            assertTrue(dismissed)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun vaultAndMonolith_writeCopyRestoreStatsAndNonDestructiveRecovery() {
        GhostCryptoVault.setupVaultPins(context, "112233".toCharArray(), "445566".toCharArray(), null)
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "112233".toCharArray())
        )

        val statsMissing = MonoVaultEngine.getZoneStats(context, MonoVaultEngine.VolumeType.MASTER)
        assertFalse(statsMissing.containerExists)
        assertEquals(0, statsMissing.recordCount)
        assertEquals(0L, statsMissing.containerFileBytes)

        val restoredPayload = "MONOLITH_RESTORED_MEDIA_BYTES".toByteArray(Charsets.UTF_8)
        val restoredGcf = GhostCryptoVault.writeDecryptedBytesToGcfCopy(
            context = context,
            plainBytes = restoredPayload,
            prefix = GhostCryptoVault.MEDIA_PHOTO
        )
        assertTrue(restoredGcf.exists())
        assertArrayEquals(restoredPayload, GhostCryptoVault.decryptToRam(context, restoredGcf))
        // Must NOT create or mutate storage_block.bin when restoring back into .GCF
        assertFalse(MonoVaultEngine.getContainerFile(context).exists())

        // Create a sparse storage_block.bin container to verify zone stats capacity/bytes safely
        val monoFile = MonoVaultEngine.getContainerFile(context)
        RandomAccessFile(monoFile, "rw").use { it.setLength(2048L) }
        val statsPresent = MonoVaultEngine.getZoneStats(context, MonoVaultEngine.VolumeType.MASTER)
        assertTrue(statsPresent.containerExists)
        assertEquals(2048L, statsPresent.containerFileBytes)
        assertEquals(MonoVaultEngine.ZONE_CAPACITY_BYTES, statsPresent.zoneCapacityBytes)

        // Verify copy-only recovery leaves original intact and creates active-key readable copy
        assertEquals(
            GhostCryptoVault.UnlockOutcome.DECOY,
            GhostCryptoVault.unlockWithPinOutcome(context, "445566".toCharArray())
        )
        val decoyPayload = "LEGACY_DECOY_PHOTO_BYTES".toByteArray(Charsets.UTF_8)
        val decoyFile = GhostCryptoVault.encryptBytesToVault(context, decoyPayload, GhostCryptoVault.MEDIA_PHOTO)
        val mainDir = GhostCryptoVault.VaultProfile.MAIN.getDirectory(context)
        val legacyFile = File(mainDir, "shot_legacy_recover.gcf")
        decoyFile.copyTo(legacyFile, overwrite = true)

        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "112233".toCharArray())
        )
        val origBytesBefore = legacyFile.readBytes()
        val recoveredCopy = File(mainDir, "shot_recovered_copy.gcf")
        GhostCryptoVault.recoverGcfFileToCopy(
            context = context,
            sourceEncryptedFile = legacyFile,
            outputCopyFile = recoveredCopy,
            reencryptTargetKey = GhostCryptoVault.getActiveVaultKey(context)
        )
        assertArrayEquals(origBytesBefore, legacyFile.readBytes())
        assertArrayEquals(decoyPayload, GhostCryptoVault.decryptToRam(context, recoveredCopy))
    }

    @Test
    fun sentinelEngine_configPersistsThroughZverPrefsAndLogControlsWork() {
        val updated = SentinelEngine.updateAndPersistConfig(context) {
            it.copy(
                lingerThresholdSec = 8,
                knockTriggerEnabled = false,
                autoNightClaheEnabled = false,
                twoStageFaceCropEnabled = false
            )
        }
        assertEquals(8, updated.lingerThresholdSec)

        SentinelEngine.clearSession()
        val reloaded = SentinelEngine.loadPersistedConfig(context)
        assertEquals(8, reloaded.lingerThresholdSec)
        assertFalse(reloaded.knockTriggerEnabled)
        assertFalse(reloaded.autoNightClaheEnabled)
        assertFalse(reloaded.twoStageFaceCropEnabled)

        SentinelEngine.setArmed(true, nowMs = 1_000_000L)
        SentinelEngine.logEvent(SentinelEngine.EventKind.INFO, "Событие 1", nowMs = 1_001_000L)
        SentinelEngine.logEvent(SentinelEngine.EventKind.IGNORED, "Событие 2", nowMs = 1_002_000L)
        SentinelEngine.logEvent(SentinelEngine.EventKind.LINGER_ALARM, "Событие 3", nowMs = 1_003_000L)
        val reportText = SentinelEngine.buildReportText()
        assertTrue(reportText.contains("ZVER SENTINEL LOG"))
        assertTrue(reportText.contains("Событие 3"))

        var cropToggled = false
        var clearedClicked = false
        var armClicked = false
        val eventsSnapshot = SentinelEngine.getEvents()
        assertTrue(eventsSnapshot.size >= 4)

        composeTestRule.setContent {
            SentinelControlCard(
                armed = true,
                config = reloaded,
                motionScore = 15,
                avgLuma = 85,
                isNight = false,
                stepLabel = "НА ПОСТУ",
                hasByokKey = false,
                events = eventsSnapshot,
                onToggleArmed = { armClicked = true },
                onCycleLinger = {},
                onToggleKnock = {},
                onToggleClaheNight = {},
                onToggleTwoStageCrop = { cropToggled = true },
                onToggleAiTriage = {},
                onClearEvents = { clearedClicked = true },
                onStealthGuard = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sentinel_crop_chip").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("sentinel_expand_log_btn").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Свернуть лог").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sentinel_clear_log_btn").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("sentinel_arm_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertTrue(cropToggled)
        assertTrue(clearedClicked)
        assertTrue(armClicked)

        SentinelEngine.clearEvents()
        assertTrue(SentinelEngine.getEvents().isEmpty())
    }

    @Test
    fun selfTestAndDecoyCalculator_includeAntiForensicsAndHideUnlockCheckingTell() {
        val report = SelfTestEngine.runFullDiagnostics(context)
        assertTrue(report.items.any { it.id == "anti_forensics" && it.title.contains("Анти-Форензика") })

        var unlocked = false
        composeTestRule.setContent {
            DecoyCalculatorScreen(onUnlocked = { unlocked = true })
        }
        composeTestRule.waitForIdle()

        // Invalid candidate when unconfigured: behaves like normal calculator, never shows "Проверка..."
        listOf("1", "2", "3", "4", "=").forEach { key ->
            composeTestRule.onNodeWithTag("calc_btn_$key").performClick()
        }
        composeTestRule.waitForIdle()
        assertFalse(unlocked)
        composeTestRule.onNodeWithText("Проверка...").assertDoesNotExist()

        // Default unconfigured unlock sequence: AC -> 1337 -> =
        listOf("AC", "1", "3", "3", "7", "=").forEach { key ->
            composeTestRule.onNodeWithTag("calc_btn_$key").performClick()
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000L) { unlocked }
        assertTrue(unlocked)
    }
}
