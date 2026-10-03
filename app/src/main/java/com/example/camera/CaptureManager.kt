package com.example.camera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.example.crypto.GhostCryptoVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Arrays
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CaptureManager v2.0 (Level: God):
 * - ZERO Bitmap decoding: Memory allocation is strictly bounded by the raw JPEG buffer from CameraX.
 * - Byte-level streaming EXIF stripping: Skips APP0-APP15 (0xFFE0..0xFFEF) and COM (0xFFFE).
 * - Direct streaming into CipherOutputStream (AES-256-GCM hardware Keystore).
 * - Immediate RAM zero-wipe of input buffers.
 */
class CaptureManager(
    context: Context,
    private val imageCapture: ImageCapture,
    private val captureExecutor: Executor
) {
    private val appContext = context.applicationContext
    private val captureMutex = Mutex()

    data class CaptureResult(
        val encryptedFile: File,
        val encryptedSizeBytes: Long,
        val strippedSegmentsCount: Int,
        val durationMs: Long
    )

    private data class RawShot(val bytes: ByteArray, val rotationDegrees: Int)

    suspend fun takeSecurePhoto(): CaptureResult = captureMutex.withLock {
        val startTime = System.currentTimeMillis()
        val rawShot = captureRawJpeg()
        try {
            withContext(Dispatchers.IO) {
                val (targetFile, cos) = GhostCryptoVault.openEncryptedOutputStream(appContext)
                var strippedCount = 0

                try {
                    cos.use { stream ->
                        strippedCount = streamStrippedJpeg(rawShot.bytes, stream)
                        stream.flush()
                    }

                    CaptureResult(
                        encryptedFile = targetFile,
                        encryptedSizeBytes = targetFile.length(),
                        strippedSegmentsCount = strippedCount,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                } catch (e: Exception) {
                    GhostCryptoVault.shredFile(targetFile)
                    throw IOException("Streaming capture failed", e)
                }
            }
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
        }
    }

    /**
     * Reads raw JPEG byte array, inspects marker headers without full image decode.
     * Drops APP0..APP15 and COM metadata. Streams structural tables (DQT, DHT, SOF)
     * and scan entropy data (SOS to EOF) directly into target OutputStream.
     */
    private fun streamStrippedJpeg(input: ByteArray, out: OutputStream): Int {
        if (input.size < 4 || u(input[0]) != 0xFF || u(input[1]) != 0xD8) {
            throw IOException("Corrupted camera buffer: Missing JPEG SOI (0xFFD8)")
        }

        // Write SOI
        out.write(0xFF)
        out.write(0xD8)

        var p = 2
        var strippedSegments = 0

        while (p < input.size - 1) {
            // Seek next 0xFF marker prefix
            if (u(input[p]) != 0xFF) {
                p++
                continue
            }

            // Handle consecutive 0xFF fill bytes
            var markerPos = p
            while (markerPos < input.size && u(input[markerPos]) == 0xFF) {
                markerPos++
            }
            if (markerPos >= input.size) break

            val marker = u(input[markerPos])
            p = markerPos + 1

            if (marker == 0x00 || marker == 0xFF) {
                // Byte stuffing or padding
                continue
            }

            // Start of Scan (SOS 0xDA):
            // Everything from here to EOF is the entropy-coded scan data.
            // Stream the marker and ALL remaining bytes directly to cipher output.
            if (marker == 0xDA) {
                out.write(0xFF)
                out.write(0xDA)
                if (p < input.size) {
                    out.write(input, p, input.size - p)
                }
                return strippedSegments
            }

            // Segment length is big-endian 16-bit
            if (p + 1 >= input.size) break
            val length = (u(input[p]) shl 8) or u(input[p + 1])
            if (length < 2 || p + length > input.size) {
                // Irregular segment length encountered; safely stream remainder and terminate
                if (p < input.size) {
                    out.write(input, p - 2, input.size - (p - 2))
                }
                return strippedSegments
            }

            val isMetadata = (marker in 0xE0..0xEF) || (marker == 0xFE)

            if (isMetadata) {
                // EXIF, JFIF, XMP, ICC, IPTC or COM: Skip completely!
                strippedSegments++
                p += length
            } else {
                // Essential structural segment: DQT, DHT, SOF0..SOF3, DRI
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
                            bufferBytes = null // Handed off cleanly
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

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
