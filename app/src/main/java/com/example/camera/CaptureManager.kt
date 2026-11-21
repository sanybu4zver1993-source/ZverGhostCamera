package com.example.camera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.security.ZverPrefs
import com.example.visor.CyberVisorEngine
import com.example.visor.VisorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Arrays
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CaptureManager v3.0:
 * - Honors ZverPrefs CompressionPreset (Ultra-Tiny WebP, Balanced WebP, Document Adaptive B&W, High-Quality JPEG).
 * - Honors Anti-PRNU Cascade and Ephemeral RAM-Only Contour (0 bytes on flash).
 * - Fast zero-decode JPEG stream stripping when High-Quality + 0° + no dehaze + no PRNU warp.
 * - Mandatory EXIF/XMP/COM purge + self-audit + immediate RAM zero-wipe of raw buffers.
 */
class CaptureManager(
    context: Context,
    private val imageCapture: ImageCapture,
    private val captureExecutor: Executor
) {
    private val appContext = context.applicationContext
    private val captureMutex = Mutex()

    data class CaptureStageTimings(
        val captureRawMs: Long = 0L,
        val processEncodeMs: Long = 0L,
        val vaultEncryptMs: Long = 0L,
        val monoVaultMs: Long = 0L,
        val totalMs: Long = 0L
    ) {
        fun formatCompactRu(): String =
            "raw=${captureRawMs}ms proc=${processEncodeMs}ms aes=${vaultEncryptMs}ms mono=${monoVaultMs}ms"
    }

    companion object {
        @Volatile
        var lastCaptureStageTimings: CaptureStageTimings? = null
            private set

        @OptIn(ExperimentalCoroutinesApi::class)
        private val monoVaultSerialDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val monoVaultScope = CoroutineScope(SupervisorJob() + monoVaultSerialDispatcher)

        internal fun enqueueMonoVaultAppendAsync(
            context: Context,
            volume: MonoVaultEngine.VolumeType,
            payloadCopy: ByteArray
        ): Job {
            val appCtx = context.applicationContext
            return monoVaultScope.launch {
                try {
                    MonoVaultEngine.appendRecord(appCtx, volume, payloadCopy)
                } catch (_: Exception) {
                } finally {
                    Arrays.fill(payloadCopy, 0.toByte())
                }
            }
        }
    }

    data class CaptureResult(
        val encryptedFile: File?,
        val encryptedSizeBytes: Long,
        val strippedSegmentsCount: Int,
        val durationMs: Long,
        val isRamOnly: Boolean = false,
        val isClean: Boolean = true,
        val formatLabel: String = "WEBP",
        val iqLabel: String = "",
        val stageTimings: CaptureStageTimings = CaptureStageTimings()
    )

    data class StackCaptureResult(
        val captureResult: CaptureResult,
        val stackStats: MultiFrameStacker.StackStats,
        val appliedQuality: Int
    )

    private data class RawShot(val bytes: ByteArray, val rotationDegrees: Int)

    suspend fun takeSecurePhoto(
        applyDehaze: Boolean = false,
        forceDocumentBw: Boolean = false,
        zoomRatio: Float = 1.0f,
        photoIqOverride: PhotoIqEngine.PhotoIqProfile? = null,
        visorMode: VisorMode = VisorMode.NORMAL,
        visorEngine: CyberVisorEngine? = null
    ): CaptureResult = captureMutex.withLock {
        val startTime = System.currentTimeMillis()
        val preset = if (forceDocumentBw) {
            ExifStripper.CompressionPreset.DOCUMENT_BW
        } else {
            ZverPrefs.getCompressionPreset(appContext)
        }
        val antiPrnu = ZverPrefs.isAntiPrnuEnabled(appContext)
        val ramOnly = ZverPrefs.isRamOnlyMode(appContext)
        val smartCompression = ZverPrefs.isSmartCompressionEnabled(appContext)
        val effectiveIq = if (forceDocumentBw || visorMode != VisorMode.NORMAL) {
            PhotoIqEngine.PhotoIqProfile.OFF
        } else {
            photoIqOverride ?: ZverPrefs.getPhotoIqProfile(appContext)
        }
        val effectiveZoomIq = if (ZverPrefs.isZoomIqEnabled(appContext) && zoomRatio >= 1.8f) zoomRatio else 1.0f

        // Temporal Bio-Visor modes (EULER_AMP, BIO_RPPG) preserve live pulse/motion state from active analyzer frame
        if (!forceDocumentBw && (visorMode == VisorMode.EULER_AMP || visorMode == VisorMode.BIO_RPPG)) {
            val liveSnap = visorEngine?.snapshotLatestBitmapCopy()
            if (liveSnap != null) {
                var snapClean: ByteArray? = null
                try {
                    return@withLock withContext(Dispatchers.IO) {
                        val proc = ExifStripper.processBitmapClean(
                            sourceBitmap = liveSnap,
                            recycleSource = true,
                            preset = preset,
                            antiPrnu = antiPrnu,
                            applyDehaze = false,
                            photoIqProfile = PhotoIqEngine.PhotoIqProfile.OFF,
                            zoomRatio = 1.0f
                        )
                        snapClean = proc.cleanBytes
                        persistEncryptedCaptureResult(
                            payload = snapClean!!,
                            ramOnly = ramOnly,
                            strippedCount = 1,
                            startTime = startTime,
                            audit = proc.audit,
                            iqTag = "ВИЗОР:${visorMode.badgeRu}"
                        )
                    }
                } finally {
                    if (!liveSnap.isRecycled) liveSnap.recycle()
                    snapClean?.let { Arrays.fill(it, 0.toByte()) }
                }
            }
        }

        val rawCaptureStart = System.currentTimeMillis()
        val rawShot = captureRawJpeg()
        val captureRawMs = (System.currentTimeMillis() - rawCaptureStart).coerceAtLeast(0L)
        var cleanBytes: ByteArray? = null
        var decodedForVisor: android.graphics.Bitmap? = null
        var renderedVisorBmp: android.graphics.Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                val procStart = System.currentTimeMillis()
                var strippedCount = 0
                val audit: ExifStripper.AuditReport

                if (!forceDocumentBw && visorMode != VisorMode.NORMAL) {
                    decodedForVisor = decodeBoundedUprightBitmap(rawShot.bytes, rawShot.rotationDegrees, maxPixels = 2_400_000L)
                        ?: throw IOException("Не удалось декодировать кадр для Био-Визора")
                    val engine = visorEngine ?: CyberVisorEngine()
                    renderedVisorBmp = engine.applyVisorEffectToBitmap(decodedForVisor!!, visorMode)
                    decodedForVisor?.let { if (!it.isRecycled) it.recycle() }
                    decodedForVisor = null

                    val proc = ExifStripper.processBitmapClean(
                        sourceBitmap = renderedVisorBmp!!,
                        recycleSource = true,
                        preset = preset,
                        antiPrnu = antiPrnu,
                        applyDehaze = false,
                        photoIqProfile = PhotoIqEngine.PhotoIqProfile.OFF,
                        zoomRatio = effectiveZoomIq
                    )
                    renderedVisorBmp = null
                    cleanBytes = proc.cleanBytes
                    audit = proc.audit
                    strippedCount = 1
                } else {
                    val useFastBytePath = (preset == ExifStripper.CompressionPreset.HIGH_QUALITY) &&
                        !antiPrnu &&
                        !applyDehaze &&
                        effectiveIq == PhotoIqEngine.PhotoIqProfile.OFF &&
                        effectiveZoomIq < 1.8f &&
                        (rawShot.rotationDegrees % 360 == 0)

                    if (useFastBytePath) {
                        val baos = ByteArrayOutputStream(rawShot.bytes.size)
                        strippedCount = streamStrippedJpeg(rawShot.bytes, baos)
                        cleanBytes = baos.toByteArray()
                        audit = ExifStripper.auditBytes(cleanBytes!!)
                    } else {
                        val processed = ExifStripper.processAndPurgeExif(
                            rawJpeg = rawShot.bytes,
                            rotationDegrees = rawShot.rotationDegrees,
                            applyDehaze = applyDehaze,
                            preset = preset,
                            antiPrnu = antiPrnu,
                            photoIqProfile = effectiveIq,
                            zoomRatio = effectiveZoomIq,
                            useSmartCompression = smartCompression
                        )
                        cleanBytes = processed.cleanJpegBytes
                        audit = processed.audit ?: ExifStripper.auditBytes(cleanBytes!!)
                        strippedCount = 1
                    }
                }
                val processEncodeMs = (System.currentTimeMillis() - procStart).coerceAtLeast(0L)

                val iqTag = buildList {
                    if (visorMode != VisorMode.NORMAL && !forceDocumentBw) add("ВИЗОР:${visorMode.badgeRu}")
                    if (effectiveIq != PhotoIqEngine.PhotoIqProfile.OFF) add(effectiveIq.badgeRu)
                    if (effectiveZoomIq >= 1.8f) add("Z${effectiveZoomIq.toInt()}x")
                }.joinToString("+")

                persistEncryptedCaptureResult(
                    payload = cleanBytes!!,
                    ramOnly = ramOnly,
                    strippedCount = strippedCount,
                    startTime = startTime,
                    audit = audit,
                    iqTag = iqTag,
                    captureRawMs = captureRawMs,
                    processEncodeMs = processEncodeMs
                )
            }
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
            cleanBytes?.let { Arrays.fill(it, 0.toByte()) }
            decodedForVisor?.let { if (!it.isRecycled) it.recycle() }
            renderedVisorBmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Persists a pre-rendered Bitmap (e.g. Bio-Visor snapshot button) through ExifStripper + Vault + MonoVault.
     */
    suspend fun saveExternalBitmapToVault(
        bitmap: android.graphics.Bitmap,
        recycleBitmap: Boolean = true,
        iqTag: String = ""
    ): CaptureResult = captureMutex.withLock {
        val startTime = System.currentTimeMillis()
        val preset = ZverPrefs.getCompressionPreset(appContext)
        val antiPrnu = ZverPrefs.isAntiPrnuEnabled(appContext)
        val ramOnly = ZverPrefs.isRamOnlyMode(appContext)
        var cleanBytes: ByteArray? = null
        try {
            withContext(Dispatchers.IO) {
                val proc = ExifStripper.processBitmapClean(
                    sourceBitmap = bitmap,
                    recycleSource = recycleBitmap,
                    preset = preset,
                    antiPrnu = antiPrnu
                )
                cleanBytes = proc.cleanBytes
                persistEncryptedCaptureResult(
                    payload = cleanBytes!!,
                    ramOnly = ramOnly,
                    strippedCount = 1,
                    startTime = startTime,
                    audit = proc.audit,
                    iqTag = iqTag
                )
            }
        } finally {
            if (recycleBitmap && !bitmap.isRecycled) bitmap.recycle()
            cleanBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * P2 Multi-Frame Computational Capture Pipeline:
     * Captures [frameCount] (2..4) frames under a single captureMutex lock, decodes with RAM-safe
     * subsampling for 4GB Helio G85 devices, aligns/fuses via MultiFrameStacker, applies Smart
     * Compression + EXIF/Anti-PRNU sanitization, stores encrypted in Vault/RAM + MonoVault,
     * and zero-wipes all temporary raw buffers.
     */
    suspend fun captureMultiFrameStack(
        mode: MultiFrameStacker.StackMode,
        frameCount: Int = 3,
        applyDehaze: Boolean = false,
        zoomRatio: Float = 1.0f,
        photoIqOverride: PhotoIqEngine.PhotoIqProfile? = null,
        useSmartCompression: Boolean = ZverPrefs.isSmartCompressionEnabled(appContext),
        visorMode: VisorMode = VisorMode.NORMAL,
        visorEngine: CyberVisorEngine? = null
    ): StackCaptureResult = captureMutex.withLock {
        val startTime = System.currentTimeMillis()
        val targetFrames = frameCount.coerceIn(2, 4)
        val preset = ZverPrefs.getCompressionPreset(appContext)
        val antiPrnu = ZverPrefs.isAntiPrnuEnabled(appContext)
        val ramOnly = ZverPrefs.isRamOnlyMode(appContext)
        val effectiveZoomIq = if (ZverPrefs.isZoomIqEnabled(appContext) && zoomRatio >= 1.8f) zoomRatio else 1.0f
        val effectiveIq = photoIqOverride ?: ZverPrefs.getPhotoIqProfile(appContext)

        val decodedFrames = ArrayList<android.graphics.Bitmap>(targetFrames)
        var stackedBitmap: android.graphics.Bitmap? = null
        var visorBitmap: android.graphics.Bitmap? = null
        var cleanBytes: ByteArray? = null

        try {
            val maxPixelsPerFrame = (6_000_000L / targetFrames).coerceAtLeast(800_000L)
            for (idx in 0 until targetFrames) {
                val shot = captureRawJpeg()
                try {
                    val bmp = withContext(Dispatchers.Default) {
                        decodeBoundedUprightBitmap(shot.bytes, shot.rotationDegrees, maxPixelsPerFrame)
                    }
                    if (bmp != null) {
                        decodedFrames.add(bmp)
                    }
                } finally {
                    Arrays.fill(shot.bytes, 0.toByte())
                }
            }

            if (decodedFrames.isEmpty()) {
                throw IOException("Не удалось декодировать ни одного кадра для Multi-Frame стека")
            }

            return@withLock withContext(Dispatchers.IO) {
                val stackOutput = MultiFrameStacker.processBurst(
                    frames = decodedFrames,
                    mode = mode,
                    recycleInputs = true
                )
                decodedFrames.clear()
                stackedBitmap = stackOutput.bitmap

                val bitmapForExport = if (visorMode != VisorMode.NORMAL) {
                    val engine = visorEngine ?: CyberVisorEngine()
                    val applied = engine.applyVisorEffectToBitmap(stackedBitmap!!, visorMode)
                    stackedBitmap?.let { if (!it.isRecycled) it.recycle() }
                    stackedBitmap = null
                    visorBitmap = applied
                    applied
                } else {
                    stackedBitmap!!
                }

                val smartQuality = if (useSmartCompression) {
                    MultiFrameStacker.estimateSmartCompressionQuality(bitmapForExport, preset)
                } else {
                    preset.quality
                }

                // Avoid double-applying NIGHT_LIFT if mode is already NIGHT_DENOISE or visor filter is active
                val residualIq = if (visorMode != VisorMode.NORMAL) {
                    PhotoIqEngine.PhotoIqProfile.OFF
                } else if (mode == MultiFrameStacker.StackMode.NIGHT_DENOISE && effectiveIq == PhotoIqEngine.PhotoIqProfile.NIGHT_LIFT) {
                    PhotoIqEngine.PhotoIqProfile.OFF
                } else if (effectiveIq == PhotoIqEngine.PhotoIqProfile.TEXT_CRISP) {
                    effectiveIq
                } else {
                    PhotoIqEngine.PhotoIqProfile.OFF
                }

                val processed = ExifStripper.processBitmapClean(
                    sourceBitmap = bitmapForExport,
                    recycleSource = true,
                    preset = preset,
                    antiPrnu = antiPrnu,
                    applyDehaze = if (visorMode == VisorMode.NORMAL) applyDehaze else false,
                    photoIqProfile = residualIq,
                    zoomRatio = effectiveZoomIq,
                    qualityOverride = smartQuality
                )
                stackedBitmap = null
                visorBitmap = null
                cleanBytes = processed.cleanBytes

                val iqTag = buildList {
                    if (visorMode != VisorMode.NORMAL) add("ВИЗОР:${visorMode.badgeRu}")
                    add("${mode.badgeRu}×${stackOutput.stats.frameCount}")
                    if (useSmartCompression) add("Q$smartQuality")
                    if (effectiveZoomIq >= 1.8f) add("Z${effectiveZoomIq.toInt()}x")
                }.joinToString("+")

                val capRes = persistEncryptedCaptureResult(
                    payload = cleanBytes!!,
                    ramOnly = ramOnly,
                    strippedCount = targetFrames,
                    startTime = startTime,
                    audit = processed.audit,
                    iqTag = iqTag
                )
                StackCaptureResult(
                    captureResult = capRes,
                    stackStats = stackOutput.stats,
                    appliedQuality = smartQuality
                )
            }
        } finally {
            decodedFrames.forEach { if (!it.isRecycled) it.recycle() }
            stackedBitmap?.let { if (!it.isRecycled) it.recycle() }
            visorBitmap?.let { if (!it.isRecycled) it.recycle() }
            cleanBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun decodeBoundedUprightBitmap(
        rawJpeg: ByteArray,
        rotationDegrees: Int,
        maxPixels: Long
    ): android.graphics.Bitmap? {
        if (rawJpeg.isEmpty()) return null
        val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, boundsOpts)
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) return null

        var sampleSize = 1
        while ((w.toLong() / sampleSize) * (h.toLong() / sampleSize) > maxPixels) {
            sampleSize *= 2
        }
        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decoded = android.graphics.BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, decodeOpts)
            ?: return null
        if (rotationDegrees % 360 == 0) {
            return decoded
        }
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = android.graphics.Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
        decoded.recycle()
        return rotated
    }

    private fun persistEncryptedCaptureResult(
        payload: ByteArray,
        ramOnly: Boolean,
        strippedCount: Int,
        startTime: Long,
        audit: ExifStripper.AuditReport,
        iqTag: String,
        captureRawMs: Long = 0L,
        processEncodeMs: Long = 0L
    ): CaptureResult {
        return if (ramOnly) {
            val t0 = System.currentTimeMillis()
            val shot = GhostCryptoVault.addRamShot(payload.clone(), isVideo = false)
            val totalMs = System.currentTimeMillis() - startTime
            val timings = CaptureStageTimings(
                captureRawMs = captureRawMs,
                processEncodeMs = processEncodeMs,
                vaultEncryptMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L),
                monoVaultMs = 0L,
                totalMs = totalMs
            )
            lastCaptureStageTimings = timings
            CaptureResult(
                encryptedFile = null,
                encryptedSizeBytes = shot.bytes.size.toLong(),
                strippedSegmentsCount = strippedCount,
                durationMs = totalMs,
                isRamOnly = true,
                isClean = audit.isClean,
                formatLabel = audit.format,
                iqLabel = iqTag,
                stageTimings = timings
            )
        } else {
            val encStart = System.currentTimeMillis()
            val targetFile = try {
                GhostCryptoVault.writeEncryptedAtomically(
                    appContext,
                    prefix = GhostCryptoVault.MEDIA_PHOTO
                ) { stream ->
                    stream.write(payload)
                }
            } catch (e: Exception) {
                throw IOException("Streaming capture failed", e)
            }
            val vaultEncryptMs = (System.currentTimeMillis() - encStart).coerceAtLeast(0L)

            val monoStart = System.currentTimeMillis()
            val volume = if (GhostCryptoVault.activeProfile == GhostCryptoVault.VaultProfile.MAIN) {
                MonoVaultEngine.VolumeType.MASTER
            } else {
                MonoVaultEngine.VolumeType.DECOY
            }
            enqueueMonoVaultAppendAsync(appContext, volume, payload.clone())
            val monoVaultMs = (System.currentTimeMillis() - monoStart).coerceAtLeast(0L)
            val totalMs = System.currentTimeMillis() - startTime
            val timings = CaptureStageTimings(
                captureRawMs = captureRawMs,
                processEncodeMs = processEncodeMs,
                vaultEncryptMs = vaultEncryptMs,
                monoVaultMs = monoVaultMs,
                totalMs = totalMs
            )
            lastCaptureStageTimings = timings

            CaptureResult(
                encryptedFile = targetFile,
                encryptedSizeBytes = targetFile.length(),
                strippedSegmentsCount = strippedCount,
                durationMs = totalMs,
                isRamOnly = false,
                isClean = audit.isClean,
                formatLabel = audit.format,
                iqLabel = iqTag,
                stageTimings = timings
            )
        }
    }

    private fun streamStrippedJpeg(input: ByteArray, out: OutputStream): Int {
        if (input.size < 4 || u(input[0]) != 0xFF || u(input[1]) != 0xD8) {
            throw IOException("Corrupted camera buffer: Missing JPEG SOI (0xFFD8)")
        }

        out.write(0xFF)
        out.write(0xD8)

        var p = 2
        var strippedSegments = 0

        while (p < input.size - 1) {
            if (u(input[p]) != 0xFF) {
                p++
                continue
            }

            var markerPos = p
            while (markerPos < input.size && u(input[markerPos]) == 0xFF) {
                markerPos++
            }
            if (markerPos >= input.size) break

            val marker = u(input[markerPos])
            p = markerPos + 1

            if (marker == 0x00 || marker == 0xFF) continue

            if (marker == 0xDA) {
                out.write(0xFF)
                out.write(0xDA)
                if (p < input.size) {
                    out.write(input, p, input.size - p)
                }
                return strippedSegments
            }

            if (p + 1 >= input.size) break
            val length = (u(input[p]) shl 8) or u(input[p + 1])
            if (length < 2 || p + length > input.size) {
                if (p < input.size) {
                    out.write(input, p - 2, input.size - (p - 2))
                }
                return strippedSegments
            }

            val isMetadata = (marker in 0xE0..0xEF) || (marker == 0xFE)

            if (isMetadata) {
                strippedSegments++
                p += length
            } else {
                out.write(0xFF)
                out.write(marker)
                out.write(input, p, length)
                p += length
            }
        }

        return strippedSegments
    }

    private suspend fun captureRawJpeg(): RawShot = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bufferBytes: ByteArray? = null
                    try {
                        val planes = image.planes
                        if (planes.isEmpty()) {
                            throw IOException("ImageProxy contains no planes")
                        }
                        val buffer = planes[0].buffer
                        val remaining = buffer.remaining()
                        if (remaining <= 0) {
                            throw IOException("Empty buffer from CameraX HAL")
                        }

                        bufferBytes = ByteArray(remaining)
                        buffer.get(bufferBytes)

                        val rotation = image.imageInfo.rotationDegrees

                        if (cont.isActive) {
                            cont.resume(RawShot(bufferBytes, rotation))
                            bufferBytes = null
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        bufferBytes?.let { Arrays.fill(it, 0.toByte()) }
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) {
                        cont.resumeWithException(exception)
                    }
                }
            }
        )
    }

    data class TwoStageAiFrame(
        val fullBytes: ByteArray,
        val fullMimeType: String,
        val faceCropBytes: ByteArray? = null,
        val faceCropMimeType: String = "image/webp",
        val detectedFacesCount: Int = 0,
        val cropKindLabel: String? = null,
        val symbiosisReport: com.example.privacy.LocalSymbiosisCvEngine.SymbiosisReport? = null,
        val backgroundIsolated: Boolean = false
    )

    /**
     * Multi-mode Vision Capture Pipeline (strictly RAM-only, zero disk writes):
     * - Full-Frame Pure Mode (runSymbiosis = false && isolateBackground = false): clean full photo only.
     * - Symbiosis Mode (runSymbiosis = true): runs 6-NN LocalSymbiosisCvEngine + picks Face/Object/Pose ROI + 40% CLAHE crop.
     * - Background Isolation Mode (isolateBackground = true): runs Selfie Segmentation to blur/dim surroundings.
     */
    suspend fun captureTwoStageFrameForAi(
        applyClahe: Boolean = false,
        detectFaceCrop: Boolean = true,
        isolateBackground: Boolean = false
    ): TwoStageAiFrame = captureMutex.withLock {
        val rawShot = captureRawJpeg()
        var decoded: android.graphics.Bitmap? = null
        var upright: android.graphics.Bitmap? = null
        var isolatedBmp: android.graphics.Bitmap? = null
        try {
            val res = withContext(Dispatchers.Default) {
                var cropBytes: ByteArray? = null
                var cropMime = "image/webp"
                var facesCount = 0
                var cropKind: String? = null
                var symReport: com.example.privacy.LocalSymbiosisCvEngine.SymbiosisReport? = null
                var isolatedApplied = false

                var fullBytesOut: ByteArray
                var fullMimeOut: String

                if (detectFaceCrop || isolateBackground) {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = 2
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                    decoded = android.graphics.BitmapFactory.decodeByteArray(rawShot.bytes, 0, rawShot.bytes.size, opts)
                    val srcBmp = decoded
                    if (srcBmp != null) {
                        upright = if (rawShot.rotationDegrees % 360 == 0) {
                            srcBmp
                        } else {
                            val m = android.graphics.Matrix().apply { postRotate(rawShot.rotationDegrees.toFloat()) }
                            android.graphics.Bitmap.createBitmap(srcBmp, 0, 0, srcBmp.width, srcBmp.height, m, true)
                        }

                        var baseForFull = upright!!
                        if (isolateBackground) {
                            val iso = com.example.privacy.LocalSymbiosisCvEngine.isolateForegroundPersonClean(
                                bitmap = upright!!,
                                blurBackground = true
                            )
                            if (iso.hasForegroundSubject) {
                                isolatedBmp = iso.bitmap
                                baseForFull = iso.bitmap
                                isolatedApplied = true
                            } else {
                                if (!iso.bitmap.isRecycled) iso.bitmap.recycle()
                            }
                        }

                        val procFull = ExifStripper.processBitmapClean(
                            sourceBitmap = if (applyClahe) ExifStripper.applyContrastDehaze(baseForFull) else baseForFull,
                            recycleSource = false,
                            preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                            antiPrnu = false
                        )
                        fullBytesOut = procFull.cleanBytes
                        fullMimeOut = if (procFull.isWebP) "image/webp" else "image/jpeg"

                        if (detectFaceCrop) {
                            val report = com.example.privacy.LocalSymbiosisCvEngine.analyzeSymbiosis(
                                bitmap = upright!!,
                                includeForegroundCoverage = false
                            )
                            symReport = report
                            facesCount = report.faces.size
                            val roi = report.bestRoiRegion()
                            if (roi != null) {
                                cropKind = report.bestRoiKindLabel()
                                val cropRes = ExifStripper.extractRegionCropClean(
                                    sourceBitmap = upright!!,
                                    normalizedRect = roi,
                                    paddingRatio = 0.40f,
                                    applyAntiGlare = true,
                                    maxCropDim = 640
                                )
                                if (cropRes != null) {
                                    cropBytes = cropRes.cleanBytes
                                    cropMime = if (cropRes.isWebP) "image/webp" else "image/jpeg"
                                }
                            }
                        }
                    } else {
                        val processedFull = ExifStripper.processAndPurgeExif(
                            rawJpeg = rawShot.bytes,
                            rotationDegrees = rawShot.rotationDegrees,
                            applyDehaze = applyClahe,
                            preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                            antiPrnu = false
                        )
                        fullBytesOut = processedFull.cleanJpegBytes
                        fullMimeOut = if (processedFull.isWebP) "image/webp" else "image/jpeg"
                    }
                } else {
                    // Pure Full-Frame mode (no local CV cropping or isolation)
                    val processedFull = ExifStripper.processAndPurgeExif(
                        rawJpeg = rawShot.bytes,
                        rotationDegrees = rawShot.rotationDegrees,
                        applyDehaze = applyClahe,
                        preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                        antiPrnu = false
                    )
                    fullBytesOut = processedFull.cleanJpegBytes
                    fullMimeOut = if (processedFull.isWebP) "image/webp" else "image/jpeg"
                }

                TwoStageAiFrame(
                    fullBytes = fullBytesOut,
                    fullMimeType = fullMimeOut,
                    faceCropBytes = cropBytes,
                    faceCropMimeType = cropMime,
                    detectedFacesCount = facesCount,
                    cropKindLabel = cropKind,
                    symbiosisReport = symReport,
                    backgroundIsolated = isolatedApplied
                )
            }
            res
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
            isolatedBmp?.let { if (!it.isRecycled) it.recycle() }
            upright?.let { if (it !== decoded && !it.isRecycled) it.recycle() }
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Captures an upright RAM-only Bitmap for purely offline 6-NN CV analysis (0 bytes to disk/network).
     */
    suspend fun captureBitmapInRamForOfflineCv(): android.graphics.Bitmap? = captureMutex.withLock {
        val rawShot = captureRawJpeg()
        try {
            withContext(Dispatchers.Default) {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(rawShot.bytes, 0, rawShot.bytes.size, opts)
                    ?: return@withContext null
                if (rawShot.rotationDegrees % 360 == 0) {
                    bmp
                } else {
                    val m = android.graphics.Matrix().apply { postRotate(rawShot.rotationDegrees.toFloat()) }
                    val rot = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    bmp.recycle()
                    rot
                }
            }
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
        }
    }

    /**
     * Captures a compact, EXIF-stripped WebP frame strictly in RAM for user-triggered BYOK AI analysis.
     * Never touches disk or MediaStore, and zeroes out the raw JPEG camera buffer immediately.
     */
    suspend fun captureCleanFrameForAi(): Pair<ByteArray, String> {
        val frame = captureTwoStageFrameForAi(applyClahe = false, detectFaceCrop = false, isolateBackground = false)
        return Pair(frame.fullBytes, frame.fullMimeType)
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
