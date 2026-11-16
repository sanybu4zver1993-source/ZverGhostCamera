package com.example.camera

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.example.privacy.LocalSymbiosisCvEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * VideoKeyframeExtractor (0 KB APK bloat via Android MediaMetadataRetriever):
 * Safely converts an ephemeral decrypted MP4 file into compact EXIF-clean WebP keyframes
 * + a multi-NN LocalSymbiosisCvEngine summary.
 *
 * Hard Anti-Limit Guard:
 * - Optimal ceiling is <= 2 minutes (120s) and MAX_KEYFRAMES = 5.
 * - For longer videos (> 120s), takes 5 evenly-spaced representative keyframes so API quotas
 *   and device RAM/CPU on Helio G85 do not get exhausted.
 */
object VideoKeyframeExtractor {
    const val RECOMMENDED_MAX_SECONDS = 120
    const val MAX_KEYFRAMES = 5
    private const val TARGET_MAX_DIM = 960

    data class KeyframeImage(
        val timestampSec: Int,
        val bytes: ByteArray,
        val mimeType: String,
        val sharpnessScore: Int
    )

    data class ExtractedVideoPackage(
        val durationSec: Int,
        val wasLongVideoTrimmed: Boolean,
        val keyframes: List<KeyframeImage>,
        val leadReport: LocalSymbiosisCvEngine.SymbiosisReport?,
        val focusCropBytes: ByteArray?,
        val focusCropMimeType: String,
        val focusCropKind: String?
    )

    suspend fun extractForAi(
        videoFile: File,
        extractBestRoiCrop: Boolean = true,
        applyAntiGlareIfDark: Boolean = true
    ): ExtractedVideoPackage = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val decodedFrames = mutableListOf<Pair<Int, Bitmap>>()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1000L) ?: 3000L
            val durationSec = (durMs / 1000L).toInt().coerceAtLeast(1)
            val wasTrimmed = durationSec > RECOMMENDED_MAX_SECONDS

            val targetCount = when {
                durationSec <= 6 -> 2
                durationSec <= 25 -> 3
                durationSec <= 65 -> 4
                else -> MAX_KEYFRAMES
            }

            // Sample evenly from 8% to 92% of the video so fade-in/fade-out edge frames are avoided
            val timestampsUs = List(targetCount) { idx ->
                val frac = if (targetCount == 1) 0.5 else (0.08 + (0.84 * idx / (targetCount - 1)))
                ((durMs * frac) * 1000.0).roundToInt().toLong().coerceIn(0L, durMs * 1000L)
            }

            for (tsUs in timestampsUs) {
                val raw = try {
                    retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) {
                    null
                }
                if (raw != null) {
                    val scaled = downscaleIfNeeded(raw, TARGET_MAX_DIM)
                    if (scaled !== raw) raw.recycle()
                    decodedFrames.add((tsUs / 1_000_000L).toInt() to scaled)
                }
            }

            if (decodedFrames.isEmpty()) {
                throw IllegalStateException("Не удалось извлечь ключевые кадры из видео")
            }

            val encoded = mutableListOf<KeyframeImage>()
            var sharpestIdx = 0
            var bestScore = -1

            decodedFrames.forEachIndexed { idx, (tsSec, bmp) ->
                val quality = LocalSymbiosisCvEngine.assessFrameQuality(bmp)
                if (quality.sharpnessScore > bestScore) {
                    bestScore = quality.sharpnessScore
                    sharpestIdx = idx
                }
                val workBmp = if (applyAntiGlareIfDark && quality.isLowLight) {
                    ExifStripper.applyContrastDehaze(bmp)
                } else {
                    bmp
                }
                val proc = ExifStripper.processBitmapClean(
                    sourceBitmap = workBmp,
                    recycleSource = false,
                    preset = ExifStripper.CompressionPreset.ULTRA_TINY,
                    antiPrnu = false
                )
                if (workBmp !== bmp) workBmp.recycle()

                encoded.add(
                    KeyframeImage(
                        timestampSec = tsSec,
                        bytes = proc.cleanBytes,
                        mimeType = if (proc.isWebP) "image/webp" else "image/jpeg",
                        sharpnessScore = quality.sharpnessScore
                    )
                )
            }

            val sharpestBmp = decodedFrames[sharpestIdx].second
            val leadReport = LocalSymbiosisCvEngine.analyzeSymbiosis(
                bitmap = sharpestBmp,
                includeForegroundCoverage = false
            )

            var cropBytes: ByteArray? = null
            var cropMime = "image/webp"
            var cropKind: String? = null
            if (extractBestRoiCrop) {
                val roi = leadReport.bestRoiRegion()
                if (roi != null) {
                    val cropRes = ExifStripper.extractRegionCropClean(
                        sourceBitmap = sharpestBmp,
                        normalizedRect = roi,
                        paddingRatio = 0.40f,
                        applyAntiGlare = true,
                        maxCropDim = 640
                    )
                    if (cropRes != null) {
                        cropBytes = cropRes.cleanBytes
                        cropMime = if (cropRes.isWebP) "image/webp" else "image/jpeg"
                        cropKind = leadReport.bestRoiKindLabel()
                    }
                }
            }

            ExtractedVideoPackage(
                durationSec = durationSec,
                wasLongVideoTrimmed = wasTrimmed,
                keyframes = encoded,
                leadReport = leadReport,
                focusCropBytes = cropBytes,
                focusCropMimeType = cropMime,
                focusCropKind = cropKind
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            decodedFrames.forEach { (_, b) -> if (!b.isRecycled) b.recycle() }
        }
    }

    private fun downscaleIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val curMax = max(w, h)
        if (curMax <= maxDim) return bmp
        val ratio = maxDim.toFloat() / curMax.toFloat()
        val nw = (w * ratio).roundToInt().coerceAtLeast(1)
        val nh = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }
}
