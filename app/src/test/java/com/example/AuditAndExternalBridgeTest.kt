package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.camera.ExifStripper
import com.example.camera.Mp4Sanitizer
import com.example.crypto.GhostCryptoVault
import com.example.crypto.VaultCleanExporter
import com.example.security.BridgeManager
import com.example.security.ZverPrefs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuditAndExternalBridgeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
        BridgeManager.setBridgeEnabled(context, false)
    }

    @After
    fun tearDown() {
        BridgeManager.setBridgeEnabled(context, false)
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.lockSession()
    }

    @Test
    fun testBridgeAliasesResolveAndToggleCleanly() {
        val verified = BridgeManager.verifyBridgeAliases(context)
        assertEquals(2, verified.size)
        assertTrue(verified[BridgeManager.CAMERA_BRIDGE_ALIAS] == true)
        assertTrue(verified[BridgeManager.PICKER_BRIDGE_ALIAS] == true)

        assertFalse(BridgeManager.isAnyBridgeAliasEnabled(context))
        assertFalse(ZverPrefs.isExternalBridgeEnabled(context))

        BridgeManager.setBridgeEnabled(context, true)
        assertTrue(BridgeManager.isAnyBridgeAliasEnabled(context))
        assertTrue(ZverPrefs.isExternalBridgeEnabled(context))

        val pm = context.packageManager
        val camComp = BridgeManager.getComponentName(context, BridgeManager.CAMERA_BRIDGE_ALIAS)
        val pickComp = BridgeManager.getComponentName(context, BridgeManager.PICKER_BRIDGE_ALIAS)
        @Suppress("DEPRECATION")
        assertNotNull(pm.getActivityInfo(camComp, PackageManager.MATCH_DISABLED_COMPONENTS))
        @Suppress("DEPRECATION")
        assertNotNull(pm.getActivityInfo(pickComp, PackageManager.MATCH_DISABLED_COMPONENTS))

        BridgeManager.setBridgeEnabled(context, false)
        assertFalse(BridgeManager.isAnyBridgeAliasEnabled(context))
        assertFalse(ZverPrefs.isExternalBridgeEnabled(context))
    }

    @Test
    fun testBridgeIntentModeAndMimeResolution() {
        assertEquals(
            BridgeLaunchMode.CAPTURE_PHOTO,
            ExternalBridgeActivity.resolveBridgeMode(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        )
        assertEquals(
            BridgeLaunchMode.CAPTURE_VIDEO,
            ExternalBridgeActivity.resolveBridgeMode(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
        )
        assertEquals(
            BridgeLaunchMode.PICK_FROM_VAULT,
            ExternalBridgeActivity.resolveBridgeMode(Intent(Intent.ACTION_GET_CONTENT))
        )
        assertEquals(
            BridgeLaunchMode.PICK_FROM_VAULT,
            ExternalBridgeActivity.resolveBridgeMode(Intent(Intent.ACTION_PICK))
        )

        val imgOnly = ExternalBridgeActivity.resolveAllowedMediaTypes(
            Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        )
        assertEquals(true to false, imgOnly)

        val vidOnly = ExternalBridgeActivity.resolveAllowedMediaTypes(
            Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }
        )
        assertEquals(false to true, vidOnly)

        val multiExtra = ExternalBridgeActivity.resolveAllowedMediaTypes(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
        )
        assertEquals(true to true, multiExtra)
    }

    @Test
    fun testPinSessionTrackingAndKeyPinnedVideoEncryptionPreventsSplitBrain() {
        assertFalse(GhostCryptoVault.hasUnlockedPinSession())

        GhostCryptoVault.setupVaultPins(
            context = context,
            mainPin = "778899".toCharArray(),
            decoyPin = null,
            duressPin = null
        )
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "778899".toCharArray())
        )
        assertTrue(GhostCryptoVault.hasUnlockedPinSession())

        // Simulate capturing key before MainActivity.onStop() invokes lockSession()
        val pinnedKey = GhostCryptoVault.getActiveVaultKey(context)
        val pinnedProfile = GhostCryptoVault.activeProfile
        val stageMp4 = File(GhostCryptoVault.getVideoStageDir(context), "pinned_test.mp4")
        val samplePayload = "CLEAN_VIDEO_STREAM_PAYLOAD_ZVER".toByteArray(Charsets.UTF_8)
        stageMp4.writeBytes(samplePayload)

        GhostCryptoVault.lockSession()
        assertFalse(GhostCryptoVault.hasUnlockedPinSession())

        val encrypted = GhostCryptoVault.encryptFileToVaultWithKey(
            context = context,
            sourceFile = stageMp4,
            explicitKey = pinnedKey,
            explicitProfile = pinnedProfile,
            prefix = GhostCryptoVault.MEDIA_VIDEO
        )
        assertFalse(stageMp4.exists())
        assertTrue(encrypted.exists())

        // Unlock again with MAIN PIN -> MUST decrypt cleanly without KeyStore split-brain
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "778899".toCharArray())
        )
        val decrypted = GhostCryptoVault.decryptToRam(context, encrypted)
        assertArrayEquals(samplePayload, decrypted)
    }

    @Test
    fun testVaultCleanExporterProducesZeroExifAndSupportsPurge() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        bmp.recycle()
        val cleanBytes = ExifStripper.processBitmapClean(
            sourceBitmap = android.graphics.BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size()),
            recycleSource = true,
            preset = ExifStripper.CompressionPreset.HIGH_QUALITY,
            antiPrnu = false
        ).cleanBytes

        val res = VaultCleanExporter.exportBytesToSystemGallery(
            context = context,
            plainBytes = cleanBytes,
            isVideo = false
        )
        assertTrue(res.displayName.startsWith("zver_clean_"))
        assertTrue(res.bytesWritten > 0)

        val written = context.contentResolver.openInputStream(res.uri)?.use { it.readBytes() }
        assertNotNull(written)
        val audit = ExifStripper.auditBytes(written!!)
        assertTrue(audit.isClean)
        assertEquals(0, audit.metadataBytes)

        val purged = VaultCleanExporter.purgeExportedGalleryCopies(context)
        assertTrue(purged >= 1)
    }

    @Test
    fun testMp4SanitizerRejectsMissingOrCorruptCleanly() {
        val missing = File(context.cacheDir, "nonexistent_zver_video.mp4")
        val out = File(context.cacheDir, "nonexistent_zver_video_clean.mp4")
        assertFalse(Mp4Sanitizer.sanitizeMp4File(missing, out))
        assertFalse(out.exists())
    }
}
