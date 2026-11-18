package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.camera.ExifStripper
import com.example.crypto.GhostCryptoVault
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P4ChainOfCustodyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testDetachedProofVerificationRoundtripAndTamperDetection() {
        val media = "ZVER_CLEAN_MEDIA_BYTES_PAYLOAD".toByteArray(Charsets.UTF_8)
        val audit = ExifStripper.AuditReport(
            format = "WEBP",
            totalBytes = media.size,
            metadataBytes = 0,
            segments = listOf("VP8X", "VP8 "),
            isClean = true
        )

        // Generate detached proof (signature may be empty in Robolectric if AndroidKeyStore is absent)
        val baseProofJson = GhostCryptoVault.generateDetachedProofJson(media, audit)

        // Inject real software ECDSA secp256r1 signature over the SHA-256 digest to test end-to-end verification
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val kp = kpg.generateKeyPair()

        val digest = MessageDigest.getInstance("SHA-256").digest(media)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(kp.private)
        signer.update(digest)
        val sigBytes = signer.sign()

        val proofObj = JSONObject(baseProofJson).apply {
            put("ecdsa_sha256_sig_b64", Base64.encodeToString(sigBytes, Base64.NO_WRAP))
            put("ecdsa_pub_spki_b64", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
        }
        val signedProofJson = proofObj.toString(2)

        val verifyFn: (ByteArray, ByteArray) -> Boolean = { d, s ->
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(kp.public)
            v.update(d)
            v.verify(s)
        }

        // 1. Valid media + valid proof + valid signature
        val okRes = GhostCryptoVault.verifyDetachedProofJson(media, signedProofJson, verifyFn)
        assertTrue(okRes.valid)
        assertTrue(okRes.sha256Matches)
        assertTrue(okRes.signatureValid)

        // 2. Flip 1 byte in media -> SHA-256 mismatch
        val tamperedMedia = media.clone()
        tamperedMedia[0] = (tamperedMedia[0].toInt() xor 0x01).toByte()
        val mediaTamperRes = GhostCryptoVault.verifyDetachedProofJson(tamperedMedia, signedProofJson, verifyFn)
        assertFalse(mediaTamperRes.valid)
        assertFalse(mediaTamperRes.sha256Matches)

        // 3. Tamper signature -> signature mismatch
        val badSigBytes = sigBytes.clone()
        badSigBytes[badSigBytes.lastIndex] = (badSigBytes[badSigBytes.lastIndex].toInt() xor 0xFF).toByte()
        val badSigProof = JSONObject(signedProofJson)
            .put("ecdsa_sha256_sig_b64", Base64.encodeToString(badSigBytes, Base64.NO_WRAP))
            .toString()
        val sigTamperRes = GhostCryptoVault.verifyDetachedProofJson(media, badSigProof, verifyFn)
        assertFalse(sigTamperRes.valid)
        assertTrue(sigTamperRes.sha256Matches)
        assertFalse(sigTamperRes.signatureValid)

        // 4. Malformed JSON fails gracefully
        val badJsonRes = GhostCryptoVault.verifyDetachedProofJson(media, "{not-valid-json", verifyFn)
        assertFalse(badJsonRes.valid)
        assertFalse(badJsonRes.sha256Matches)
    }

    @Test
    fun testVaultChainManifestIsDeterministicAndNonDestructive() {
        val dir = GhostCryptoVault.VaultProfile.MAIN.getDirectory(context)
        val f1 = File(dir, "shot_001.gcf")
        val f2 = File(dir, "shot_002.gcf")
        val b1 = byteArrayOf(0x47, 0x43, 0x46, 0x31, 1, 2, 3, 4)
        val b2 = byteArrayOf(0x47, 0x43, 0x46, 0x31, 9, 8, 7, 6)

        try {
            f1.writeBytes(b1)
            f1.setLastModified(1_700_000_001_000L)
            f2.writeBytes(b2)
            f2.setLastModified(1_700_000_002_000L)

            val manifestJson = GhostCryptoVault.generateVaultChainManifestJson(
                context,
                GhostCryptoVault.VaultProfile.MAIN
            )
            val obj = JSONObject(manifestJson)
            assertEquals("zver-chain-manifest-v1", obj.getString("schema"))
            assertEquals("MAIN", obj.getString("profile"))
            assertEquals(2, obj.getInt("total_files"))

            val items = obj.getJSONArray("items")
            assertEquals(2, items.length())
            assertEquals("shot_001.gcf", items.getJSONObject(0).getString("file_name"))
            assertEquals("shot_002.gcf", items.getJSONObject(1).getString("file_name"))

            // Independently recompute chained SHA-256 head
            val md = MessageDigest.getInstance("SHA-256")
            val h1 = md.digest(b1).joinToString("") { "%02x".format(it) }
            val c1 = md.digest(("0".repeat(64) + h1 + "shot_001.gcf").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val h2 = md.digest(b2).joinToString("") { "%02x".format(it) }
            val c2 = md.digest((c1 + h2 + "shot_002.gcf").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            assertEquals(c1, items.getJSONObject(0).getString("chain_hash"))
            assertEquals(c2, items.getJSONObject(1).getString("chain_hash"))
            assertEquals(c2, obj.getString("chain_head_sha256"))

            // Originals remain strictly untouched
            assertArrayEquals(b1, f1.readBytes())
            assertArrayEquals(b2, f2.readBytes())
        } finally {
            f1.delete()
            f2.delete()
        }
    }
}
