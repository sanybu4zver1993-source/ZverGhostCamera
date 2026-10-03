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
import java.util.Arrays
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CaptureManager(
    context: Context,
    private val imageCapture: ImageCapture,
    private val captureExecutor: Executor
) {
    private val appContext = context.applicationContext
    private val captureMutex = Mutex()

    data class CaptureResult(
        val encryptedFile: File,
        val originalDimensions: Pair<Int, Int>,
        val finalDimensions: Pair<Int, Int>,
        val encryptedSizeBytes: Long,
        val durationMs: Long
    )

    private data class RawShot(val bytes: ByteArray, val rotationDegrees: Int)

    /**
     * Executes air-gapped single shot:
     * 1. Captures JPEG into RAM buffer
     * 2. Zero-EXIF & JPEG marker purge via ExifStripper
     * 3. AES-256-GCM encryption directly into internal vault
     * 4. Memory buffer wipe
     */
    suspend fun takeSecurePhoto(): CaptureResult = captureMutex.withLock {
        val startTime = System.currentTimeMillis()
        val rawShot = captureRawJpeg()
        try {
            withContext(Dispatchers.IO) {
                // Purge EXIF, normalize rotation, strip APP segments
                val processed = ExifStripper.processAndPurgeExif(
                    rawJpeg = rawShot.bytes,
                    rotationDegrees = rawShot.rotationDegrees
                )

                try {
                    // Encrypt directly into private vault .gcf
                    val encryptedFile = GhostCryptoVault.encryptAndSave(
                        context = appContext,
                        plaintext = processed.cleanJpegBytes
                    )

                    CaptureResult(
                        encryptedFile = encryptedFile,
                        originalDimensions = Pair(processed.originalWidth, processed.originalHeight),
                        finalDimensions = Pair(processed.finalWidth, processed.finalHeight),
                        encryptedSizeBytes = encryptedFile.length(),
                        durationMs = System.currentTimeMillis() - startTime
                    )
                } finally {
                    Arrays.fill(processed.cleanJpegBytes, 0.toByte())
                }
            }
        } finally {
            Arrays.fill(rawShot.bytes, 0.toByte())
        }
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
                            throw IOException("Empty buffer from CameraX")
                        }

                        bufferBytes = ByteArray(remaining)
                        buffer.get(bufferBytes)

                        val rotation = image.imageInfo.rotationDegrees

                        if (cont.isActive) {
                            cont.resume(RawShot(bufferBytes, rotation))
                            bufferBytes = null // Handed off
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
}
