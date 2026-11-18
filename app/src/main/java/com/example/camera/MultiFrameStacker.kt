package com.example.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MultiFrameStacker (P2):
 * CPU/RAM-friendly Multi-Frame Computational Photography engine for MT6769Z (4 GB RAM):
 * - Translational center-ROI frame alignment (SAD coarse-to-fine search in +/-8 px).
 * - Night Stacking & Multi-Frame Denoising with motion-ghost rejection.
 * - Multi-Frame HDR Exposure Fusion + Detail Recovery.
 * - Smart Adaptive Compression Quality estimation based on edge density & noise.
 */
object MultiFrameStacker {

    enum class StackMode(val labelRu: String, val badgeRu: String) {
        NIGHT_DENOISE("Ночной стек (Шумодав + Детали)", "Ночь-стек"),
        HDR_FUSION("HDR Бёрст (Тени + Света)", "HDR-стек"),
        DETAIL_RECOVERY("Супер-детализация (Анти-смаз)", "Детали+")
    }

    data class StackStats(
        val mode: StackMode,
        val frameCount: Int,
        val alignedOffsets: List<Pair<Int, Int>>,
        val rejectedGhostPercent: Int,
        val recommendedQuality: Int,
        val durationMs: Long
    )

    data class StackOutput(
        val bitmap: Bitmap,
        val stats: StackStats
    )

    /**
     * Estimates sharpness variance on center ROI to pick the sharpest anchor frame.
     */
    fun estimateSharpness(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return 0.0
        val x0 = w / 4
        val x1 = (w * 3) / 4
        val y0 = h / 4
        val y1 = (h * 3) / 4
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in y0 + 1 until y1 - 1 step 2) {
            for (x in x0 + 1 until x1 - 1 step 2) {
                val c = luma(bmp.getPixel(x, y))
                val l = luma(bmp.getPixel(x - 1, y))
                val r = luma(bmp.getPixel(x + 1, y))
                val u = luma(bmp.getPixel(x, y - 1))
                val d = luma(bmp.getPixel(x, y + 1))
                val lap = (l + r + u + d - 4 * c).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /**
     * Finds integer pixel shift (dx, dy) aligning [candidate] to [reference] via subsampled SAD.
     */
    fun estimateTranslationShift(
        reference: IntArray,
        candidate: IntArray,
        width: Int,
        height: Int,
        searchRadius: Int = 8
    ): Pair<Int, Int> {
        if (width < 32 || height < 32) return 0 to 0
        val rx0 = width / 4
        val rx1 = (width * 3) / 4
        val ry0 = height / 4
        val ry1 = (height * 3) / 4
        val step = max(2, min(width, height) / 48)

        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE

        // Coarse pass (step 2)
        for (dy in -searchRadius..searchRadius step 2) {
            for (dx in -searchRadius..searchRadius step 2) {
                val sad = computeRoiSad(reference, candidate, width, height, rx0, rx1, ry0, ry1, step, dx, dy)
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // Fine pass around best coarse match
        val cx = bestDx
        val cy = bestDy
        for (dy in (cy - 1)..(cy + 1)) {
            for (dx in (cx - 1)..(cx + 1)) {
                if (dx < -searchRadius || dx > searchRadius || dy < -searchRadius || dy > searchRadius) continue
                val sad = computeRoiSad(reference, candidate, width, height, rx0, rx1, ry0, ry1, step, dx, dy)
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private fun computeRoiSad(
        ref: IntArray,
        cand: IntArray,
        w: Int,
        h: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        step: Int,
        dx: Int,
        dy: Int
    ): Long {
        var sad = 0L
        var y = y0
        while (y < y1) {
            val cy = (y + dy).coerceIn(0, h - 1)
            val refRow = y * w
            val candRow = cy * w
            var x = x0
            while (x < x1) {
                val cx = (x + dx).coerceIn(0, w - 1)
                val rl = fastLum(ref[refRow + x])
                val cl = fastLum(cand[candRow + cx])
                sad += abs(rl - cl)
                x += step
            }
            y += step
        }
        return sad
    }

    /**
     * Processes a burst of Bitmaps using [mode].
     * All source frames remain untouched unless [recycleInputs] is true.
     */
    fun processBurst(
        frames: List<Bitmap>,
        mode: StackMode,
        recycleInputs: Boolean = false
    ): StackOutput {
        val t0 = System.currentTimeMillis()
        require(frames.isNotEmpty()) { "Burst frame list cannot be empty" }

        if (frames.size == 1) {
            val base = frames[0]
            val single = when (mode) {
                StackMode.NIGHT_DENOISE -> PhotoIqEngine.enhanceBitmap(base, PhotoIqEngine.PhotoIqProfile.NIGHT_LIFT, recycleSource = recycleInputs)
                StackMode.HDR_FUSION -> PhotoIqEngine.applyToneCurveLut(base, shadowLift = 0.22f, contrastBoost = 1.12f, recycleInput = recycleInputs)
                StackMode.DETAIL_RECOVERY -> PhotoIqEngine.enhanceBitmap(base, PhotoIqEngine.PhotoIqProfile.AUTO_IQ, recycleSource = recycleInputs)
            }
            val recQ = estimateSmartCompressionQuality(single, ExifStripper.CompressionPreset.BALANCED)
            return StackOutput(
                bitmap = single,
                stats = StackStats(mode, 1, listOf(0 to 0), 0, recQ, System.currentTimeMillis() - t0)
            )
        }

        // Normalize dimensions to anchor (sharpest frame)
        var anchorIdx = 0
        var bestSharp = Double.NEGATIVE_INFINITY
        for (i in frames.indices) {
            val s = estimateSharpness(frames[i])
            if (s > bestSharp) {
                bestSharp = s
                anchorIdx = i
            }
        }

        val anchor = frames[anchorIdx]
        val w = anchor.width
        val h = anchor.height
        val totalPixels = w * h

        val alignedArrays = ArrayList<IntArray>(frames.size)
        val anchorPixels = IntArray(totalPixels)
        anchor.getPixels(anchorPixels, 0, w, 0, 0, w, h)
        alignedArrays.add(anchorPixels)

        val offsets = mutableListOf(0 to 0)
        for (i in frames.indices) {
            if (i == anchorIdx) continue
            val src = frames[i]
            val sized = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
            val pix = IntArray(totalPixels)
            sized.getPixels(pix, 0, w, 0, 0, w, h)
            if (sized !== src && !sized.isRecycled) sized.recycle()
            val (dx, dy) = estimateTranslationShift(anchorPixels, pix, w, h, searchRadius = 8)
            offsets.add(dx to dy)
            alignedArrays.add(if (dx == 0 && dy == 0) pix else shiftPixels(pix, w, h, dx, dy))
        }

        if (recycleInputs) {
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }

        val outPixels = IntArray(totalPixels)
        var ghostRejectedSamples = 0L
        var totalCandidateSamples = 0L

        when (mode) {
            StackMode.NIGHT_DENOISE -> {
                // Temporal average with motion-ghost rejection against anchor
                val ghostFloor = 38
                for (i in 0 until totalPixels) {
                    val ap = anchorPixels[i]
                    val ar = (ap ushr 16) and 0xFF
                    val ag = (ap ushr 8) and 0xFF
                    val ab = ap and 0xFF
                    val aLum = (ar * 77 + ag * 150 + ab * 29) ushr 8

                    var sumR = ar * 2
                    var sumG = ag * 2
                    var sumB = ab * 2
                    var weightSum = 2

                    for (f in 1 until alignedArrays.size) {
                        val cp = alignedArrays[f][i]
                        val cr = (cp ushr 16) and 0xFF
                        val cg = (cp ushr 8) and 0xFF
                        val cb = cp and 0xFF
                        val cLum = (cr * 77 + cg * 150 + cb * 29) ushr 8
                        totalCandidateSamples++
                        if (abs(cLum - aLum) <= ghostFloor) {
                            sumR += cr
                            sumG += cg
                            sumB += cb
                            weightSum++
                        } else {
                            ghostRejectedSamples++
                        }
                    }

                    val mr = (sumR / weightSum).coerceIn(0, 255)
                    val mg = (sumG / weightSum).coerceIn(0, 255)
                    val mb = (sumB / weightSum).coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
                }
            }

            StackMode.HDR_FUSION -> {
                // Multi-frame well-exposedness weighted exposure fusion + shadow/highlight recovery
                for (i in 0 until totalPixels) {
                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    var sumW = 0.0

                    val ap = anchorPixels[i]
                    val aLum = fastLum(ap)

                    for (f in alignedArrays.indices) {
                        val cp = alignedArrays[f][i]
                        val cr = (cp ushr 16) and 0xFF
                        val cg = (cp ushr 8) and 0xFF
                        val cb = cp and 0xFF
                        val lum = (cr * 77 + cg * 150 + cb * 29) ushr 8

                        // Reject fast moving ghosts on non-anchor frames
                        if (f > 0) {
                            totalCandidateSamples++
                            if (abs(lum - aLum) > 52) {
                                ghostRejectedSamples++
                                continue
                            }
                        }

                        // Bell curve favoring midtones (128), plus highlight/shadow protection
                        val diff = (lum - 128.0) / 88.0
                        val wellExposed = kotlin.math.exp(-0.5 * diff * diff).coerceAtLeast(0.08)
                        val anchorBonus = if (f == 0) 1.35 else 1.0
                        val wPix = wellExposed * anchorBonus

                        sumR += cr * wPix
                        sumG += cg * wPix
                        sumB += cb * wPix
                        sumW += wPix
                    }

                    val inv = if (sumW > 0.001) 1.0 / sumW else 1.0
                    val mr = (sumR * inv).toInt().coerceIn(0, 255)
                    val mg = (sumG * inv).toInt().coerceIn(0, 255)
                    val mb = (sumB * inv).toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
                }
            }

            StackMode.DETAIL_RECOVERY -> {
                // High-weight anchor + median/trimmed average of aligned neighbors for crisp edges
                for (i in 0 until totalPixels) {
                    val ap = anchorPixels[i]
                    val ar = (ap ushr 16) and 0xFF
                    val ag = (ap ushr 8) and 0xFF
                    val ab = ap and 0xFF
                    val aLum = (ar * 77 + ag * 150 + ab * 29) ushr 8

                    var sumR = ar * 2
                    var sumG = ag * 2
                    var sumB = ab * 2
                    var wSum = 2
                    for (f in 1 until alignedArrays.size) {
                        val cp = alignedArrays[f][i]
                        val cr = (cp ushr 16) and 0xFF
                        val cg = (cp ushr 8) and 0xFF
                        val cb = cp and 0xFF
                        val cLum = (cr * 77 + cg * 150 + cb * 29) ushr 8
                        totalCandidateSamples++
                        if (abs(cLum - aLum) <= 28) {
                            sumR += cr
                            sumG += cg
                            sumB += cb
                            wSum++
                        } else {
                            ghostRejectedSamples++
                        }
                    }
                    outPixels[i] = (0xFF shl 24) or
                        ((sumR / wSum).coerceIn(0, 255) shl 16) or
                        ((sumG / wSum).coerceIn(0, 255) shl 8) or
                        (sumB / wSum).coerceIn(0, 255)
                }
            }
        }

        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        merged.setPixels(outPixels, 0, w, 0, 0, w, h)

        val finalBmp = when (mode) {
            StackMode.NIGHT_DENOISE -> {
                PhotoIqEngine.enhanceBitmap(merged, PhotoIqEngine.PhotoIqProfile.NIGHT_LIFT, recycleSource = true)
            }
            StackMode.HDR_FUSION -> {
                val tone = PhotoIqEngine.applyToneCurveLut(merged, shadowLift = 0.20f, contrastBoost = 1.12f, recycleInput = true)
                val sharp = PhotoIqEngine.applyNoiseGatedSharpen(tone, strength = 0.36f, noiseThreshold = 10)
                if (sharp !== tone && !tone.isRecycled) tone.recycle()
                sharp
            }
            StackMode.DETAIL_RECOVERY -> {
                val sharp = PhotoIqEngine.applyNoiseGatedSharpen(merged, strength = 0.58f, noiseThreshold = 8)
                if (sharp !== merged && !merged.isRecycled) merged.recycle()
                sharp
            }
        }

        val ghostPct = if (totalCandidateSamples > 0) {
            ((ghostRejectedSamples * 100L) / totalCandidateSamples).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val recQ = estimateSmartCompressionQuality(finalBmp, ExifStripper.CompressionPreset.BALANCED)

        return StackOutput(
            bitmap = finalBmp,
            stats = StackStats(
                mode = mode,
                frameCount = frames.size,
                alignedOffsets = offsets,
                rejectedGhostPercent = ghostPct,
                recommendedQuality = recQ,
                durationMs = System.currentTimeMillis() - t0
            )
        )
    }

    /**
     * Smart Compression: adjusts encoding quality dynamically by measuring visual edge density
     * so simple scenes compress ultra-small while text/detail-rich scenes retain legibility.
     */
    fun estimateSmartCompressionQuality(
        bitmap: Bitmap,
        preset: ExifStripper.CompressionPreset
    ): Int {
        if (preset == ExifStripper.CompressionPreset.DOCUMENT_BW) return preset.quality
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return preset.quality

        val stepX = max(2, w / 48)
        val stepY = max(2, h / 48)
        var edgeHits = 0
        var samples = 0
        for (y in stepY until h - stepY step stepY) {
            for (x in stepX until w - stepX step stepX) {
                val c = fastLum(bitmap.getPixel(x, y))
                val r = fastLum(bitmap.getPixel(x + 1, y))
                val d = fastLum(bitmap.getPixel(x, y + 1))
                if (abs(c - r) + abs(c - d) >= 24) {
                    edgeHits++
                }
                samples++
            }
        }
        val edgePct = if (samples > 0) (edgeHits * 100) / samples else 20
        val delta = when {
            edgePct >= 42 -> +8  // dense text / PCB traces / foliage -> protect sharpness
            edgePct <= 12 -> -8  // flat wall / sky / uniform dark -> save bytes
            else -> 0
        }
        return (preset.quality + delta).coerceIn(32, 95)
    }

    private fun shiftPixels(src: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val sy = (y + dy).coerceIn(0, h - 1)
            val dstRow = y * w
            val srcRow = sy * w
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0, w - 1)
                out[dstRow + x] = src[srcRow + sx]
            }
        }
        return out
    }

    private fun fastLum(c: Int): Int {
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 150 + b * 29) ushr 8
    }

    private fun luma(c: Int): Int = fastLum(c)
}
