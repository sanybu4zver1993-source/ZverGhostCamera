package com.example

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraDiagnostics
import com.example.crypto.GhostCryptoVault
import com.example.panic.PanicTriggerReceiver
import com.example.ui.shouldShowDocumentLevelOverlay
import com.example.ui.shouldShowTacticalReticle
import com.example.visor.ClaheLutBuilder
import com.example.visor.GlVisorPtsNormalizer
import com.example.visor.GlVisorShaders
import com.example.visor.VisorMode
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P6HardeningAndClaheTest {

    @Test
    fun glVisorPtsNormalizer_alignsCrossClockDomainsStrictlyMonotonically() {
        val norm = GlVisorPtsNormalizer()
        val startNs = 1_000_000_000_000L
        norm.reset(startNs)

        // Frame 1: HAL sensor timestamp in completely different domain (e.g. 5_000_000_000L)
        val p1 = norm.nextRelativePtsNs(rawSensorNs = 5_000_000_000L, nowMonoNs = startNs + 10_000_000L)
        assertEquals(10_000_000L, p1)

        // Frame 2: +33ms sensor step
        val p2 = norm.nextRelativePtsNs(rawSensorNs = 5_033_000_000L, nowMonoNs = startNs + 43_000_000L)
        assertEquals(43_000_000L, p2)

        // Frame 3: sensor timestamp suddenly drops to 0 or goes backward -> stays strictly monotonic against wall clock
        val p3 = norm.nextRelativePtsNs(rawSensorNs = 0L, nowMonoNs = startNs + 76_000_000L)
        assertTrue(p3 >= p2 + 1_000_000L)

        // Frame 4: duplicate wall clock -> strictly increases by at least 1ms
        val p4 = norm.nextRelativePtsNs(rawSensorNs = 0L, nowMonoNs = startNs + 76_000_000L)
        assertTrue(p4 >= p3 + 1_000_000L)
    }

    @Test
    fun claheLutBuilder_producesValidMonotonicTileCdfsAndShaderHasUniform() {
        val w = 64
        val h = 64
        val yBuf = ByteBuffer.allocateDirect(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Low-contrast haze range 90..150
                val v = 90 + ((x + y) % 61)
                yBuf.put(v.toByte())
            }
        }
        yBuf.flip()

        val lut = ClaheLutBuilder.build4x4TileLuts(yBuf, w, h, w)
        assertEquals(ClaheLutBuilder.LUT_BYTE_SIZE, lut.size)

        for (tile in 0 until ClaheLutBuilder.TILE_COUNT) {
            val base = tile * ClaheLutBuilder.BINS
            var prev = 0
            for (bin in 0 until ClaheLutBuilder.BINS) {
                val cur = lut[base + bin].toInt() and 0xFF
                assertTrue("Tile $tile CDF must be non-decreasing at bin $bin", cur >= prev)
                prev = cur
            }
            assertEquals(255, prev)
        }

        // Shader 300 ES must reference uDehazeLut & uHasDehazeLut
        assertTrue(GlVisorShaders.FRAGMENT_300.contains("uDehazeLut"))
        assertTrue(GlVisorShaders.FRAGMENT_300.contains("uHasDehazeLut"))
    }

    @Test
    fun overlayVisibilityRules_preventStuckTiltOrReticleAcrossTabs() {
        // Level only visible in DOCUMENTS + levelEnabled + NOT recording
        assertTrue(shouldShowDocumentLevelOverlay("DOCUMENTS", isDocumentModeActive = true, isRecordingVideo = false))
        assertFalse(shouldShowDocumentLevelOverlay("DOCUMENTS", isDocumentModeActive = true, isRecordingVideo = true))
        assertFalse(shouldShowDocumentLevelOverlay("VIDEO", isDocumentModeActive = true, isRecordingVideo = false))
        assertFalse(shouldShowDocumentLevelOverlay("VISOR", isDocumentModeActive = true, isRecordingVideo = false))

        // Tactical reticle hidden during recording, in DOCUMENTS, and in active VISOR modes
        assertTrue(shouldShowTacticalReticle("PHOTO", VisorMode.NORMAL, isRecordingVideo = false))
        assertFalse(shouldShowTacticalReticle("DOCUMENTS", VisorMode.NORMAL, isRecordingVideo = false))
        assertFalse(shouldShowTacticalReticle("VISOR", VisorMode.THERMAL_LUT, isRecordingVideo = false))
        assertFalse(shouldShowTacticalReticle("VIDEO", VisorMode.NORMAL, isRecordingVideo = true))
    }

    @Test
    fun atomicVaultWrite_preservesGcf1DecryptionAndShredsOrphanTemps() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        GhostCryptoVault.setupVaultPins(ctx, "1234".toCharArray(), null, null)
        GhostCryptoVault.unlockWithPin(ctx, "1234".toCharArray())

        val payload = "ZVER-ATOMIC-TEST-PAYLOAD-0123456789".toByteArray(Charsets.UTF_8)
        val saved = GhostCryptoVault.encryptBytesToVault(ctx, payload, GhostCryptoVault.MEDIA_PHOTO)
        assertTrue(saved.exists())
        assertTrue(saved.name.endsWith(".gcf"))
        assertArrayEquals(payload, GhostCryptoVault.decryptToRam(ctx, saved))

        // Plant orphan .gcf.tmp and 0-byte .gcf inside MAIN dir
        val mainDir = GhostCryptoVault.VaultProfile.MAIN.getDirectory(ctx)
        val orphanTmp = File(mainDir, "shot_orphan_123.gcf.tmp").apply { writeText("partial") }
        val orphanEmpty = File(mainDir, "shot_empty_123.gcf").apply { writeBytes(ByteArray(0)) }

        val cleaned = GhostCryptoVault.shredOrphanedVaultTempFiles(ctx, minAgeMs = 0L)
        assertEquals(2, cleaned)
        assertFalse(orphanTmp.exists())
        assertFalse(orphanEmpty.exists())
        assertTrue(saved.exists())

        GhostCryptoVault.cryptoShred(ctx)
    }

    @Test
    fun panicTriggerReceiver_rejectsSpoofedPackageExtraAndOnlyLocksSession() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        GhostCryptoVault.setupVaultPins(ctx, "4321".toCharArray(), null, null)
        GhostCryptoVault.unlockWithPin(ctx, "4321".toCharArray())

        val vaultFile = GhostCryptoVault.encryptBytesToVault(ctx, "TOP_SECRET".toByteArray(), GhostCryptoVault.MEDIA_PHOTO)
        GhostCryptoVault.addRamShot("RAM_SECRET".toByteArray(), isVideo = false)
        assertTrue(vaultFile.exists())
        assertEquals(1, GhostCryptoVault.listRamShots().size)

        // Malicious broadcast spoofing guardianproject package string without valid nonce or verified UID
        val spoofIntent = Intent(PanicTriggerReceiver.ACTION_TRIGGER).apply {
            putExtra("caller_package", "info.guardianproject.ripple")
        }
        PanicTriggerReceiver().onReceive(ctx, spoofIntent)

        // Disk .gcf file MUST survive, while volatile RAM shots are cleared safely
        assertTrue("Spoofed trigger must NOT wipe disk vault files!", vaultFile.exists())
        assertEquals(0, GhostCryptoVault.listRamShots().size)

        // Genuine shared-secret trigger MUST wipe vault cleanly
        val secret = PanicTriggerReceiver.getOrGenerateSharedSecret(ctx)
        val authIntent = Intent(PanicTriggerReceiver.ACTION_TRIGGER).apply {
            putExtra(PanicTriggerReceiver.EXTRA_SHARED_SECRET, secret)
        }
        PanicTriggerReceiver().onReceive(ctx, authIntent)
        assertFalse("Verified shared secret must purge vault files", vaultFile.exists())
    }

    @Test
    fun cameraDiagnostics_buildsValidZeroSecretJsonReport() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jsonStr = CameraDiagnostics.buildDiagnosticJson(ctx, "GPU VIDEO+VISOR: Preview(EGL) + ImageAnalysis")
        val obj = JSONObject(jsonStr)
        assertEquals("zver-diag-v1", obj.getString("schema"))
        assertEquals("GPU VIDEO+VISOR: Preview(EGL) + ImageAnalysis", obj.getString("bound_use_cases"))
        assertTrue(obj.has("lenses"))
    }
}
