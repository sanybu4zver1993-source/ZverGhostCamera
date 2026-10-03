package com.example.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays

/**
 * ExifStripper:
 * 1. Bounded decode to protect RAM on 4GB devices (Redmi 13C) from OOM.
 * 2. Proper rotation correction in RAM.
 * 3. Fresh JPEG re-encoding which strips manufacturer camera HAL metadata, GPS, lens tags.
 * 4. Structural JPEG marker stripping to remove APP0-APP15 (EXIF, JFIF, XMP, ICC) and COM segments.
 * 5. Memory wipe of intermediate byte buffers.
 */
object ExifStripper {
    // 8 MegaPixels limit to ensure memory consumption stays < 32MB during transformation
    private const val MAX_PIXELS = 8_000_000L
    private const val MAX_JPEG_INPUT_BYTES = 25 * 1024 * 1024 // 25 MB max input buffer

    data class ProcessedImage(
        val cleanJpegBytes: ByteArray,
        val originalWidth: Int,
        val originalHeight: Int,
        val finalWidth: Int,
        val finalHeight: Int
    )

    /**
     * Takes raw camera JPEG bytes, decodes safely, applies rotation,
     * re-encodes pixels cleanly, and strips metadata markers.
     */
    fun processAndPurgeExif(rawJpeg: ByteArray, rotationDegrees: Int): ProcessedImage {
        if (rawJpeg.isEmpty() || rawJpeg.size > MAX_JPEG_INPUT_BYTES) {
            throw IOException("Invalid JPEG input size: ${rawJpeg.size} bytes")
        }

        // Step 1: Decode dimensions safely
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) {
            throw IOException("Corrupted JPEG stream from camera")
        }

        // Calculate sample size to fit MAX_PIXELS
        var sampleSize = 1
        while ((origW.toLong() / sampleSize) * (origH.toLong() / sampleSize) > MAX_PIXELS) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decodedBitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, decodeOptions)
            ?: throw IOException("Failed to decode JPEG bytes into Bitmap")

        var rotatedBitmap: Bitmap? = null
        var recompressedJpeg: ByteArray? = null

        try {
            val uprightBitmap = if (rotationDegrees % 360 == 0) {
                decodedBitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                rotatedBitmap = Bitmap.createBitmap(
                    decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height,
                    matrix, true
                )
                rotatedBitmap
            }

            val finalW = uprightBitmap.width
            val finalH = uprightBitmap.height

            // Step 2: Fresh re-compression of pixel data throws away any camera maker-notes
            val baos = ByteArrayOutputStream()
            val compressedOk = uprightBitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            if (!compressedOk) {
                throw IOException("JPEG re-compression failed")
            }
            recompressedJpeg = baos.toByteArray()

            // Step 3: Strip structural JPEG metadata segments (APP0..APP15 and COM)
            val cleanStrippedBytes = stripJpegSegments(recompressedJpeg)

            return ProcessedImage(
                cleanJpegBytes = cleanStrippedBytes,
                originalWidth = origW,
                originalHeight = origH,
                finalWidth = finalW,
                finalHeight = finalH
            )
        } finally {
            decodedBitmap.recycle()
            rotatedBitmap?.recycle()
            if (recompressedJpeg != null) {
                Arrays.fill(recompressedJpeg, 0.toByte())
            }
        }
    }

    /**
     * Scans JPEG byte stream from SOI (0xFF 0xD8) to SOS (0xFF 0xDA).
     * Filters out APP0-APP15 (0xE0 - 0xEF) and COM (0xFE).
     * Preserves DQT (0xDB), DHT (0xC4), SOF (0xC0-0xC3), DRI (0xDD), SOS and raw entropy data.
     */
    private fun stripJpegSegments(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 4 || u(jpeg[0]) != 0xFF || u(jpeg[1]) != 0xD8) {
            throw IOException("Invalid JPEG: Missing SOI marker")
        }

        val out = ByteArrayOutputStream(jpeg.size)
        // Write SOI
        out.write(jpeg, 0, 2)
        var p = 2

        try {
            while (p + 3 < jpeg.size) {
                // Seek next 0xFF
                if (u(jpeg[p]) != 0xFF) {
                    p++
                    continue
                }

                // Handle fill bytes (consecutive 0xFF)
                var markerPos = p
                while (markerPos < jpeg.size && u(jpeg[markerPos]) == 0xFF) {
                    markerPos++
                }
                if (markerPos >= jpeg.size) break

                val marker = u(jpeg[markerPos])
                p = markerPos + 1

                if (marker == 0x00 || marker == 0xFF) {
                    // Stuffing or stray byte
                    continue
                }

                // If Start of Scan (SOS 0xDA), remainder is entropy-coded image data till EOI
                if (marker == 0xDA) {
                    // Write SOS marker
                    out.write(0xFF)
                    out.write(0xDA)
                    // Write remaining entropy stream directly to the end
                    out.write(jpeg, p, jpeg.size - p)
                    return out.toByteArray()
                }

                // Read segment length (2 bytes, big-endian)
                if (p + 1 >= jpeg.size) break
                val length = (u(jpeg[p]) shl 8) or u(jpeg[p + 1])
                if (length < 2 || p + length > jpeg.size) {
                    break
                }

                val segmentStart = markerPos - 1 // points to 0xFF before marker
                val segmentEnd = p + length

                // Filter out: APP0-APP15 (0xE0..0xEF) and COM (0xFE)
                val isMetadata = (marker in 0xE0..0xEF) || (marker == 0xFE)

                if (!isMetadata) {
                    // Retain quantization, huffman, frame headers
                    out.write(0xFF)
                    out.write(marker)
                    out.write(jpeg, p, length)
                }

                p = segmentEnd
            }
        } catch (_: Exception) {
            // Fallback: If segment scanner encounters unexpected OEM stream anomaly,
            // return the fresh re-encoded JPEG which is already free of GPS/device info.
            return jpeg.clone()
        }

        // If SOS was reached and handled above, we returned. If reached EOF unexpectedly:
        return if (out.size() > 4) out.toByteArray() else jpeg.clone()
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
