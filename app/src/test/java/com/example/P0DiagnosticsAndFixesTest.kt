package com.example

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.assist.ByokManager
import com.example.crypto.GhostCryptoVault
import com.example.security.CamouflageManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P0DiagnosticsAndFixesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        ByokManager.clearSessionMemory()
        ByokManager.saveDiscoveredModels(context, emptyList())
        ByokManager.saveModelName(context, ByokManager.DEFAULT_MODEL_FALLBACK)
    }

    @After
    fun tearDown() {
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        ByokManager.clearSessionMemory()
    }

    @Test
    fun testCamouflageManifestAliasesResolveAndToggleWithoutCrash() {
        val aliasCheck = CamouflageManager.verifyManifestAliases(context)
        assertEquals(3, aliasCheck.size)
        for ((mode, exists) in aliasCheck) {
            assertTrue("Expected manifest <activity-alias> for $mode to resolve", exists)
            val component = mode.getComponentName(context)
            assertEquals("com.aistudio.ghostsuite.vkxpqz", component.packageName)
            assertTrue(
                "Component class ${component.className} must use com.example.<Alias>",
                component.className.startsWith("com.example.")
            )
            @Suppress("DEPRECATION")
            val info = context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            assertNotNull(info)
        }

        // Toggle CALCULATOR -> SYSTEM_TOOLS -> ZVER and verify state tracking
        CamouflageManager.setCamouflage(context, CamouflageManager.CamouflageMode.CALCULATOR)
        assertEquals(CamouflageManager.CamouflageMode.CALCULATOR, CamouflageManager.getCurrentCamouflage(context))

        CamouflageManager.setCamouflage(context, CamouflageManager.CamouflageMode.SYSTEM_TOOLS)
        assertEquals(CamouflageManager.CamouflageMode.SYSTEM_TOOLS, CamouflageManager.getCurrentCamouflage(context))

        CamouflageManager.setCamouflage(context, CamouflageManager.CamouflageMode.ZVER)
        assertEquals(CamouflageManager.CamouflageMode.ZVER, CamouflageManager.getCurrentCamouflage(context))
    }

    @Test
    fun testVaultReadOnlyDiagnosticsStrictNoFallbackBackupAndCopyRecovery() {
        GhostCryptoVault.setupVaultPins(
            context = context,
            mainPin = "112233".toCharArray(),
            decoyPin = "445566".toCharArray(),
            duressPin = null
        )

        // Unlock both MAIN and DECOY so both PBKDF2 session keys exist for read-only key diagnosis
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "112233".toCharArray())
        )
        val mainPayload = "MAIN_SECRET_FRAME_PAYLOAD_01".toByteArray(Charsets.UTF_8)
        val mainFile = GhostCryptoVault.encryptBytesToVault(context, mainPayload, GhostCryptoVault.MEDIA_PHOTO)

        assertEquals(
            GhostCryptoVault.UnlockOutcome.DECOY,
            GhostCryptoVault.unlockWithPinOutcome(context, "445566".toCharArray())
        )
        val decoyPayload = "MISMATCHED_KEY_FRAME_PAYLOAD_02".toByteArray(Charsets.UTF_8)
        val decoyFile = GhostCryptoVault.encryptBytesToVault(context, decoyPayload, GhostCryptoVault.MEDIA_PHOTO)

        // Move decoy-encrypted copy into MAIN directory to simulate legacy key mismatch inside MAIN vault
        val mainDir = GhostCryptoVault.VaultProfile.MAIN.getDirectory(context)
        val legacyMismatchFile = File(mainDir, "shot_legacy_mismatch.gcf")
        decoyFile.copyTo(legacyMismatchFile, overwrite = true)

        // Switch session active profile back to MAIN
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "112233".toCharArray())
        )
        val origHashBefore = sha256Hex(legacyMismatchFile)
        val origLenBefore = legacyMismatchFile.length()

        // 1. Read-only header inspection succeeds and does not alter file
        val header = GhostCryptoVault.inspectGcfHeader(legacyMismatchFile)
        assertTrue(header.hasValidMagic)
        assertEquals(24, header.ivHex.length)
        assertTrue(header.ciphertextBytes > 16L)
        assertEquals(origHashBefore, sha256Hex(legacyMismatchFile))

        // 2. Normal decrypt MUST FAIL strictly with AEADBadTagException (NO silent auto-fallback)
        assertThrows(AEADBadTagException::class.java) {
            GhostCryptoVault.decryptToRam(context, legacyMismatchFile)
        }
        assertEquals(origHashBefore, sha256Hex(legacyMismatchFile))

        // 3. Read-only directory diagnosis identifies each file's true key source without mutating originals
        val diagnostics = GhostCryptoVault.diagnoseVaultDirectory(context, GhostCryptoVault.VaultProfile.MAIN)
        val mainDiag = diagnostics.first { it.file.name == mainFile.name }
        val mismatchDiag = diagnostics.first { it.file.name == legacyMismatchFile.name }
        assertEquals(GhostCryptoVault.KeySourceKind.ACTIVE_MAIN_PIN, mainDiag.matchedKeyKind)
        assertEquals(GhostCryptoVault.KeySourceKind.ACTIVE_DECOY_PIN, mismatchDiag.matchedKeyKind)
        assertEquals(origHashBefore, sha256Hex(legacyMismatchFile))
        assertEquals(origLenBefore, legacyMismatchFile.length())

        // 4. Read-only encrypted backup copies GCF files + key/salt metadata cleanly while preserving originals
        val backupDir = File(context.noBackupFilesDir, "test_backup_ro")
        backupDir.deleteRecursively()
        val backupRes = GhostCryptoVault.backupVaultDirectoryReadOnly(
            context = context,
            profile = GhostCryptoVault.VaultProfile.MAIN,
            backupDir = backupDir
        )
        assertEquals(2, backupRes.copiedFilesCount)
        assertEquals(2, backupRes.metadataFilesCount)
        val backedUpMismatch = File(backupDir, legacyMismatchFile.name)
        assertTrue(backedUpMismatch.exists())
        assertEquals(origHashBefore, sha256Hex(backedUpMismatch))
        assertEquals(origHashBefore, sha256Hex(legacyMismatchFile))

        // 5. Copy-only recovery refuses to overwrite the source original
        assertThrows(IllegalArgumentException::class.java) {
            GhostCryptoVault.recoverGcfFileToCopy(
                context = context,
                sourceEncryptedFile = legacyMismatchFile,
                outputCopyFile = legacyMismatchFile,
                reencryptTargetKey = GhostCryptoVault.getActiveVaultKey(context)
            )
        }

        // 6. Copy-only recovery produces a valid decrypted copy AND a re-encrypted copy readable under active MAIN key
        val recoveredWorkDir = File(context.noBackupFilesDir, "test_recovered_copies")
        recoveredWorkDir.deleteRecursively()

        val plainCopy = File(recoveredWorkDir, "plain_copy.bin")
        val usedKind1 = GhostCryptoVault.recoverGcfFileToCopy(
            context = context,
            sourceEncryptedFile = legacyMismatchFile,
            outputCopyFile = plainCopy,
            reencryptTargetKey = null
        )
        assertEquals(GhostCryptoVault.KeySourceKind.ACTIVE_DECOY_PIN, usedKind1)
        assertArrayEquals(decoyPayload, plainCopy.readBytes())

        val reencryptedCopy = File(recoveredWorkDir, "reencrypted_main.gcf")
        val usedKind2 = GhostCryptoVault.recoverGcfFileToCopy(
            context = context,
            sourceEncryptedFile = legacyMismatchFile,
            outputCopyFile = reencryptedCopy,
            reencryptTargetKey = GhostCryptoVault.getActiveVaultKey(context)
        )
        assertEquals(GhostCryptoVault.KeySourceKind.ACTIVE_DECOY_PIN, usedKind2)
        val decryptedFromReencrypted = GhostCryptoVault.decryptToRam(context, reencryptedCopy)
        assertArrayEquals(decoyPayload, decryptedFromReencrypted)

        // Original remains 100% untouched throughout
        assertEquals(origHashBefore, sha256Hex(legacyMismatchFile))
    }

    @Test
    fun testByokLogSanitizationAndNoSilentModelSubstitution() {
        val fakeKey = "AIzaSyD987654321TestSecretKeyValue99"
        val dirtyMsg = "GET https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key=$fakeKey failed with header x-goog-api-key=$fakeKey"
        val cleanMsg = ByokManager.sanitizeForLog(dirtyMsg, fakeKey)

        assertFalse("Log sanitizer must strip raw API key", cleanMsg.contains(fakeKey))
        assertFalse("Log sanitizer must strip AIza prefix leak", cleanMsg.contains("AIzaSy"))
        assertTrue(cleanMsg.contains("***REDACTED***"))

        ByokManager.recordHttpLog(
            method = "GET",
            endpoint = "/v1beta/models?key=$fakeKey",
            httpCode = 200,
            latencyMs = 142L,
            detail = "ok token=$fakeKey",
            secretKey = fakeKey
        )
        val logs = ByokManager.httpLogsFlow.value
        assertEquals(1, logs.size)
        assertFalse(logs.first().endpoint.contains(fakeKey))
        assertFalse(logs.first().detail.contains(fakeKey))
        assertEquals(200, logs.first().httpCode)

        // User picks a custom/experimental model not in the fetched list -> MUST NOT be silently replaced
        val chosenModel = "gemini-exp-custom-user-choice"
        ByokManager.saveModelName(context, chosenModel)
        assertEquals(chosenModel, ByokManager.selectedModelFlow.value)

        val discoveredWithoutUserChoice = listOf(
            ByokManager.DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash", "", 1_000_000, 65_536),
            ByokManager.DiscoveredModel("gemini-2.5-pro", "Gemini 2.5 Pro", "", 1_000_000, 65_536)
        )
        ByokManager.saveDiscoveredModels(context, discoveredWithoutUserChoice)

        assertEquals(chosenModel, ByokManager.getModelName(context))
        assertEquals(chosenModel, ByokManager.selectedModelFlow.value)
        assertEquals(2, ByokManager.discoveredModelsFlow.value.size)
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
