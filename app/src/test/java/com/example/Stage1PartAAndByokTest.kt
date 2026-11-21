package com.example

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.example.assist.ByokManager
import com.example.assist.GeminiByokClient
import com.example.camera.StorageWatchdog
import com.example.crypto.EncryptedMediaDataSource
import com.example.crypto.GcfChunkedCodec
import com.example.crypto.GhostCryptoVault
import com.example.crypto.VaultIndexEngine
import com.example.crypto.ZverBackupEngine
import com.example.privacy.LocalSymbiosisCvEngine
import com.example.privacy.OfflinePrivacyEngine
import com.example.security.SelfTestEngine
import com.example.security.ZverPrefs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Stage1PartAAndByokTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "stage1_test_${System.nanoTime()}").apply { mkdirs() }
        GhostCryptoVault.cryptoShred(context)
    }

    @After
    fun tearDown() {
        GhostCryptoVault.cryptoShred(context)
        tempDir.deleteRecursively()
    }

    private fun randomAesKey(): javax.crypto.SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    @Test
    fun gcfChunkedCodec_and_mediaDataSource_supportCrossBoundarySeekAndRejectBadKey() {
        val key = randomAesKey()
        val wrongKey = randomAesKey()
        val payloadSize = GcfChunkedCodec.DEFAULT_CHUNK_SIZE * 2 + 17_321 // ~541 KB (3 chunks)
        val plain = ByteArray(payloadSize)
        SecureRandom().nextBytes(plain)

        val src = File(tempDir, "sample_video.mp4").apply { writeBytes(plain) }
        val gcf2 = File(tempDir, "vid_test.gcf")

        val header = GcfChunkedCodec.encryptFileAtomically(src, gcf2, key)
        assertEquals(3, header.chunkCount)
        assertEquals(payloadSize.toLong(), header.totalPlainSize)

        val ds = EncryptedMediaDataSource(gcf2, key)
        try {
            assertEquals(payloadSize.toLong(), ds.size)
            // Read spanning chunk 0 -> chunk 1 boundary
            val boundaryPos = GcfChunkedCodec.DEFAULT_CHUNK_SIZE.toLong() - 19L
            val buf = ByteArray(128)
            val read = ds.readAt(boundaryPos, buf, 0, buf.size)
            assertEquals(128, read)
            assertArrayEquals(
                plain.copyOfRange(boundaryPos.toInt(), boundaryPos.toInt() + 128),
                buf
            )

            // Read near tail (chunk 2)
            val tailBuf = ByteArray(100)
            val tailPos = (payloadSize - 40).toLong()
            val tailRead = ds.readAt(tailPos, tailBuf, 0, tailBuf.size)
            assertEquals(40, tailRead)
            assertArrayEquals(
                plain.copyOfRange(tailPos.toInt(), payloadSize),
                tailBuf.copyOfRange(0, 40)
            )
            assertEquals(-1, ds.readAt(payloadSize.toLong(), tailBuf, 0, 10))
        } finally {
            ds.close()
        }

        // Wrong key must raise AEADBadTagException
        var rejected = false
        try {
            GcfChunkedCodec.decryptToBytes(gcf2, wrongKey)
        } catch (_: AEADBadTagException) {
            rejected = true
        }
        assertTrue("Expected AEADBadTagException for wrong key", rejected)
    }

    @Test
    fun ghostCryptoVault_storesVideosAsGcf2_and_recoversViaEphemeralAndCopy() {
        GhostCryptoVault.setupVaultPins(context, "1357".toCharArray(), "2468".toCharArray())
        assertEquals(
            GhostCryptoVault.UnlockOutcome.MAIN,
            GhostCryptoVault.unlockWithPinOutcome(context, "1357".toCharArray())
        )

        val videoBytes = ByteArray(310_000) { i -> (i * 31).toByte() }
        val stageSrc = File(tempDir, "rec.mp4").apply { writeBytes(videoBytes) }
        val storedGcf = GhostCryptoVault.encryptFileToVault(context, stageSrc, GhostCryptoVault.MEDIA_VIDEO)

        assertTrue(GhostCryptoVault.isGcf2File(storedGcf))
        val hdr = GhostCryptoVault.inspectGcfHeader(storedGcf)
        assertTrue(hdr.hasValidMagic)
        assertTrue(hdr.formatVersion.startsWith("GCF2"))

        val diag = GhostCryptoVault.diagnoseDecryptKeySource(context, storedGcf)
        assertEquals(GhostCryptoVault.KeySourceKind.ACTIVE_MAIN_PIN, diag.matchedKeyKind)
        assertEquals(videoBytes.size, diag.plaintextBytesLength)

        val eph = GhostCryptoVault.decryptToEphemeralFile(context, storedGcf)
        assertArrayEquals(videoBytes, eph.readBytes())

        val recovered = File(tempDir, "rec_out.mp4")
        GhostCryptoVault.recoverGcfFileToCopy(context, storedGcf, recovered, reencryptTargetKey = null)
        assertArrayEquals(videoBytes, recovered.readBytes())
    }

    @Test
    fun zverBackupEngine_portableRoundtrip_worksAcrossWipeAndNewPinKey() {
        GhostCryptoVault.setupVaultPins(context, "1111".toCharArray(), null)
        GhostCryptoVault.unlockWithPinOutcome(context, "1111".toCharArray())

        val photoPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5)
        val photoFile = GhostCryptoVault.encryptBytesToVault(context, photoPayload, GhostCryptoVault.MEDIA_PHOTO)

        val vidPayload = ByteArray(280_000) { (it and 0xFF).toByte() }
        val stageVid = File(tempDir, "zvb_vid.mp4").apply { writeBytes(vidPayload) }
        val vidFile = GhostCryptoVault.encryptFileToVault(context, stageVid, GhostCryptoVault.MEDIA_VIDEO)

        VaultIndexEngine.setTagsAndNote(
            context,
            GhostCryptoVault.VaultProfile.MAIN,
            photoFile.name,
            tags = listOf("плата", "чек"),
            note = "Тест переноса"
        )
        // Expired item that should be skipped upon import
        val expiredPayload = byteArrayOf(9, 8, 7, 6)
        val expiredFile = GhostCryptoVault.encryptBytesToVault(context, expiredPayload, GhostCryptoVault.MEDIA_PHOTO)
        VaultIndexEngine.setTtl(
            context,
            GhostCryptoVault.VaultProfile.MAIN,
            expiredFile.name,
            durationMs = 1L,
            nowMs = System.currentTimeMillis() - 10_000L
        )

        val archiveOut = ByteArrayOutputStream()
        val exportRes = ZverBackupEngine.exportProfileBackup(
            context = context,
            profile = GhostCryptoVault.VaultProfile.MAIN,
            masterPassword = "StrongMasterPassword!".toCharArray(),
            outStream = archiveOut,
            iterations = 10_000
        )
        assertEquals(3, exportRes.fileCount)
        val archiveBytes = archiveOut.toByteArray()

        // Wipe vault and create brand new key / PIN (simulating migration from debug APK to release APK)
        GhostCryptoVault.cryptoShred(context)
        GhostCryptoVault.setupVaultPins(context, "9876".toCharArray(), null)
        GhostCryptoVault.unlockWithPinOutcome(context, "9876".toCharArray())
        assertEquals(0, GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.MAIN).size)

        // Wrong password must fail cleanly
        var badPwdCaught = false
        try {
            ZverBackupEngine.importProfileBackup(
                context = context,
                targetProfile = GhostCryptoVault.VaultProfile.MAIN,
                masterPassword = "WrongPassword".toCharArray(),
                inStream = ByteArrayInputStream(archiveBytes)
            )
        } catch (_: AEADBadTagException) {
            badPwdCaught = true
        }
        assertTrue("Expected AEADBadTagException on wrong backup password", badPwdCaught)
        assertEquals(0, GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.MAIN).size)

        // Correct password restores photo + video + metadata and skips expired entry
        val importRes = ZverBackupEngine.importProfileBackup(
            context = context,
            targetProfile = GhostCryptoVault.VaultProfile.MAIN,
            masterPassword = "StrongMasterPassword!".toCharArray(),
            inStream = ByteArrayInputStream(archiveBytes)
        )
        assertEquals(2, importRes.importedFilesCount)
        assertEquals(1, importRes.skippedExpiredCount)

        val restoredFiles = GhostCryptoVault.listVaultFiles(context, GhostCryptoVault.VaultProfile.MAIN)
        assertEquals(2, restoredFiles.size)
        val restoredPhoto = restoredFiles.first { !GhostCryptoVault.isVideoFile(it) }
        val restoredVid = restoredFiles.first { GhostCryptoVault.isVideoFile(it) }
        assertArrayEquals(photoPayload, GhostCryptoVault.decryptToRam(context, restoredPhoto))
        assertArrayEquals(vidPayload, GhostCryptoVault.decryptToRam(context, restoredVid))

        val restoredMeta = VaultIndexEngine.getMeta(context, GhostCryptoVault.VaultProfile.MAIN, restoredPhoto.name)
        assertEquals(listOf("плата", "чек"), restoredMeta.tags)
        assertEquals("Тест переноса", restoredMeta.note)
    }

    @Test
    fun vaultIndexEngine_filtersAndPurgesExpiredTtlFiles() {
        GhostCryptoVault.setupVaultPins(context, "2222".toCharArray(), null)
        GhostCryptoVault.unlockWithPinOutcome(context, "2222".toCharArray())

        val f1 = GhostCryptoVault.encryptBytesToVault(context, byteArrayOf(1, 2, 3), GhostCryptoVault.MEDIA_PHOTO)
        val f2 = GhostCryptoVault.encryptBytesToVault(context, byteArrayOf(4, 5, 6), GhostCryptoVault.MEDIA_VIDEO)

        VaultIndexEngine.setTagsAndNote(context, GhostCryptoVault.VaultProfile.MAIN, f1.name, listOf("паспорт"), "Документ А4")
        val now = 1_700_000_000_000L
        VaultIndexEngine.setTtl(context, GhostCryptoVault.VaultProfile.MAIN, f1.name, 600_000L, nowMs = now)

        val idx = VaultIndexEngine.loadIndex(context, GhostCryptoVault.VaultProfile.MAIN)
        assertTrue(VaultIndexEngine.matchesFilter(f1, idx[f1.name], "паспорт", VaultIndexEngine.VaultFilterType.ALL))
        assertTrue(VaultIndexEngine.matchesFilter(f1, idx[f1.name], "документ", VaultIndexEngine.VaultFilterType.PHOTO))
        assertFalse(VaultIndexEngine.matchesFilter(f1, idx[f1.name], "", VaultIndexEngine.VaultFilterType.VIDEO))
        assertTrue(VaultIndexEngine.matchesFilter(f1, idx[f1.name], "", VaultIndexEngine.VaultFilterType.TTL_ONLY))
        assertFalse(VaultIndexEngine.matchesFilter(f2, idx[f2.name], "", VaultIndexEngine.VaultFilterType.TTL_ONLY))

        // Before expiry: nothing purged
        assertEquals(0, VaultIndexEngine.purgeExpiredFiles(context, GhostCryptoVault.VaultProfile.MAIN, now + 500_000L))
        assertTrue(f1.exists())

        // After expiry: f1 shredded, f2 stays
        assertEquals(1, VaultIndexEngine.purgeExpiredFiles(context, GhostCryptoVault.VaultProfile.MAIN, now + 601_000L))
        assertFalse(f1.exists())
        assertTrue(f2.exists())
    }

    @Test
    fun storageWatchdog_selfTestEngine_and_volumePrefs_behaveAccurately() {
        val critical = StorageWatchdog.evaluateBytes(200L * 1024 * 1024, 32_000L * 1024 * 1024)
        assertEquals(StorageWatchdog.StorageLevel.CRITICAL_STOP, critical.level)
        assertFalse(critical.canRecord)
        assertTrue(critical.shouldStopRecording)

        val warn = StorageWatchdog.evaluateBytes(350L * 1024 * 1024, 32_000L * 1024 * 1024)
        assertEquals(StorageWatchdog.StorageLevel.LOW_WARNING, warn.level)
        assertTrue(warn.canRecord)
        assertFalse(warn.shouldStopRecording)

        val ok = StorageWatchdog.evaluateBytes(2_000L * 1024 * 1024, 32_000L * 1024 * 1024)
        assertEquals(StorageWatchdog.StorageLevel.OK, ok.level)

        ZverPrefs.setVolumeKeyAction(context, ZverPrefs.VolumeKeyAction.STEALTH_BLACKOUT)
        assertEquals(ZverPrefs.VolumeKeyAction.STEALTH_BLACKOUT, ZverPrefs.getVolumeKeyAction(context))

        val report = SelfTestEngine.runFullDiagnostics(
            context = context,
            boundUseCasesSummary = "PHOTO: Preview + ImageCapture",
            eglRecordableOk = true
        )
        assertTrue(report.items.size >= 7)
        assertNotNull(report.items.firstOrNull { it.id == "byok_channel" })
        assertTrue(report.formattedTextReport.contains("ZVER SELF-TEST REPORT"))
    }

    @Test
    fun byokEncryptedTransport_and_localSymbiosis6Nn_produceDistinctContextAwareOutputs() {
        val direct = ByokManager.ProxyTransportConfig(ByokManager.TransportMode.DIRECT_TLS)
        assertNull(GeminiByokClient.resolveEffectiveProxy(direct))

        val tor = ByokManager.ProxyTransportConfig(ByokManager.TransportMode.TOR_ORBOT_SOCKS5)
        val torProxy = GeminiByokClient.resolveEffectiveProxy(tor)
        assertNotNull(torProxy)
        assertEquals(Proxy.Type.SOCKS, torProxy!!.type())
        val addr = torProxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", addr.hostString)
        assertEquals(9050, addr.port)

        ByokManager.saveTransportConfig(context, tor)
        assertEquals(ByokManager.TransportMode.TOR_ORBOT_SOCKS5, ByokManager.getTransportConfig(context).mode)

        // Mock report for 6-NN formatter
        val sampleReport = LocalSymbiosisCvEngine.SymbiosisReport(
            faces = listOf(LocalSymbiosisCvEngine.FaceSummary(RectF(0.1f, 0.1f, 0.5f, 0.5f), yawDeg = 5f, rollDeg = 1f)),
            objects = listOf(LocalSymbiosisCvEngine.DetectedObjectSummary(RectF(0.05f, 0.05f, 0.8f, 0.8f), "Document", 0.91f)),
            sceneTags = listOf(LocalSymbiosisCvEngine.SceneTag("Paper", 0.92f), LocalSymbiosisCvEngine.SceneTag("Desk", 0.85f)),
            pose = LocalSymbiosisCvEngine.PoseSummary(detected = false, visibleLandmarks = 0, postureRu = "Нет скелета", torsoRect = null),
            extractedText = "Чек #4812 Итого 450 грн",
            osintReport = OfflinePrivacyEngine.OsintRiskReport(warnings = listOf("Обнаружен номер/сумма в тексте"), riskScore = 55),
            quality = LocalSymbiosisCvEngine.FrameQuality(avgLuma = 128, isLowLight = false, sharpnessScore = 72, isBlurry = false),
            foregroundCoveragePct = 42f,
            inferenceMs = 38L
        )

        val ocrOut = LocalSymbiosisCvEngine.buildOfflineVikaReport(sampleReport, "Вытащи OCR текст с документа")
        val osintOut = LocalSymbiosisCvEngine.buildOfflineVikaReport(sampleReport, "Проверь OSINT риски и палево")
        val dozorOut = LocalSymbiosisCvEngine.buildOfflineVikaReport(sampleReport, "Режим Дозор оценка угрозы")
        val opticsOut = LocalSymbiosisCvEngine.buildOfflineVikaReport(sampleReport, "Оцени форензик резкость и смаз")

        assertNotEquals(ocrOut, osintOut)
        assertNotEquals(osintOut, dozorOut)
        assertNotEquals(dozorOut, opticsOut)
        assertTrue(ocrOut.contains("OCR ЭКСТРАКТОР") && ocrOut.contains("Чек #4812"))
        assertTrue(osintOut.contains("OSINT-ПАЛЕВО"))
        assertTrue(dozorOut.contains("КИБЕР-ДОЗОР"))
        assertTrue(opticsOut.contains("ОПТИКА И ФОРЕНЗИК"))

        val textTor = LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Как работает канал через Tor и ключ?")
        val textZvb = LocalSymbiosisCvEngine.buildOfflineTextOnlyReply(context, "Как перенести бэкап .zvb на другой телефон?")
        assertNotEquals(textTor, textZvb)
        assertTrue(textTor.contains("SOCKS5"))
        assertTrue(textZvb.contains("GCF2"))
    }
}
