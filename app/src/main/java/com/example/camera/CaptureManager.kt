package com.example.camera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.example.crypto.GhostCryptoVault
import com.example.crypto.MonoVaultEngine
import com.example.security.ZverPrefs
import kotlinx.coroutines.Dispatchers
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

    data class CaptureResult(
        val encryptedFile: File?,
        val encryptedSizeBytes: Long,
        val strippedSegmentsCount: Int,
        val durationMs: Long,
        val isRamOnly: Boolean = false,
        val isClean: Boolean = true,
        val formatLabel: String = "WEBP"
    )

    private data class RawShot(val bytes: ByteArray, val rotationDegrees: Int)

    suspend fun takeSecurePhoto(
        applyDehaze: Boolean = false,
        forceDocumentBw: Boolean = false
    ): CaptureResult = captureMutex.withLock {
        val startTime = System.currentTimeMillis()
        val rawShot = captureRawJpeg()
        val preset = if (forceDocumentBw) {
            ExifStripper.CompressionPreset.DOCUMENT_BW
        } else {
            ZverPrefs.getCompressionPreset(appContext)
        }
        val antiPrnu = ZverPrefs.isAntiPrnuEnabled(appContext)
        val ramOnly = ZverPrefs.isRamOnlyMode(appContext)

        var cleanBytes: ByteArray? = null
        try {
            withContext(Dispatchers.IO) {
                var strippedCount = 0
                val audit: ExifStripper.AuditReport

                val useFastBytePath = (preset == ExifStripper.CompressionPreset.HIGH_QUALITY) &&
                    !antiPrnu &&
                    !applyDehaze &&
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
                        antiPrnu = antiPrnu
                    )
                    cleanBytes = processed.cleanJpegBytes
                    audit = processed.audit ?: ExifStripper.auditBytes(cleanBytes!!)
                    strippedCount = 1
                }

                val payload = cleanBytes!!

                if (ramOnly) {
                    val shot = GhostCryptoVault.addRamShot(payload.clone(), isVideo = false)
                    CaptureResult(
                        encryptedFile = null,
                        encryptedSizeBytes = shot.bytes.size.toLong(),
                        strippedSegmentsCount = strippedCount,
                        durationMs = System.currentTimeMillis() - startTime,
                        isRamOnly = true,
                        isClean = audit.isClean,
                        formatLabel = audit.format
                    )
                } else {
                    val (targetFile, cos) = GhostCryptoVault.openEncryptedOutputStream(
                        appContext,
                        prefix = GhostCryptoVault.MEDIA_PHOTO
                    )
                    try {
                        cos.use { stream ->
                            stream.write(payload)
                            stream.flush()
                        }

                        // Mirror stripped payload into Monolithic VeraCrypt Container
                        try {
                            val volume = if (GhostCryptoVault.activeProfile == GhostCryptoVault.VaultProfile.MAIN) {
                                MonoVaultEngine.VolumeType.MASTER
                            } else {
                                MonoVaultEngine.VolumeType.DECOY
                            }
                            MonoVaultEngine.appendRecord(appContext, volume, payload)
                        } catch (_: Exception) {}

                        CaptureResult(
                            encryptedFile = targetFile,
                            encryptedSizeBytes = targetFile.length(),
                            strippedSegmentsCount = strippedCount,
                            durationMs = System.currentTimeMillis() - startTime,
                            isRamOnly = false,
                            isClean = audit.isClean,
                            formatLabel = audit.format
                        )
                    } catch (e: Exception) {
                        GhostCryptoVault.shredFile(targetFile)
                        throw IOException("Streaming capture failed", e)
                    }
                }
            }
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
            cleanBytes?.let { Arrays.fill(it, 0.toByte()) }
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
        val detectedFacesCount: Int = 0
    )

    /**
     * Two-stage Vision Pipeline (Edge CV -> Gemini BYOK):
     * 1) Decodes raw camera shot strictly in RAM & runs bundled ML Kit Face Detection on-device.
     * 2) Encodes EXIF-stripped full frame + optional 40%-padded CLAHE face crop (if faces > 0).
     * 3) Immediately zero-wipes raw camera buffer. Zero disk writes.
     */
    suspend fun captureTwoStageFrameForAi(
        applyClahe: Boolean = false,
        detectFaceCrop: Boolean = true
    ): TwoStageAiFrame = captureMutex.withLock {
        val rawShot = captureRawJpeg()
        var decoded: android.graphics.Bitmap? = null
        var upright: android.graphics.Bitmap? = null
        try {
            val res = withContext(Dispatchers.Default) {
                val processedFull = ExifStripper.processAndPurgeExif(
                    rawJpeg = rawShot.bytes,
                    rotationDegrees = rawShot.rotationDegrees,
                    applyDehaze = applyClahe,
                    preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                    antiPrnu = false
                )
                val fullMime = if (processedFull.isWebP) "image/webp" else "image/jpeg"

                var cropBytes: ByteArray? = null
                var cropMime = "image/webp"
                var facesCount = 0

                if (detectFaceCrop) {
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
                        val faces = com.example.privacy.OfflinePrivacyEngine.detectFacesOffline(upright!!)
                        facesCount = faces.size
                        val primaryFace = faces.maxByOrNull { it.rect.width() * it.rect.height() }
                        if (primaryFace != null) {
                            val cropRes = ExifStripper.extractRegionCropClean(
                                sourceBitmap = upright!!,
                                normalizedRect = primaryFace.rect,
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
                }

                TwoStageAiFrame(
                    fullBytes = processedFull.cleanJpegBytes,
                    fullMimeType = fullMime,
                    faceCropBytes = cropBytes,
                    faceCropMimeType = cropMime,
                    detectedFacesCount = facesCount
                )
            }
            res
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
            upright?.let { if (it !== decoded && !it.isRecycled) it.recycle() }
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Captures a compact, EXIF-stripped WebP frame strictly in RAM for user-triggered BYOK AI analysis.
     * Never touches disk or MediaStore, and zeroes out the raw JPEG camera buffer immediately.
     */
    suspend fun captureCleanFrameForAi(): Pair<ByteArray, String> {
        val frame = captureTwoStageFrameForAi(applyClahe = false, detectFaceCrop = false)
        return Pair(frame.fullBytes, frame.fullMimeType)
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
