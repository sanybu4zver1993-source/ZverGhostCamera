package com.example.visor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * YcbcrSkinAnalyzer ("Цвет человека"):
 * Direct YCbCr chrominance human-skin detector tuned for Android YUV_420_888 U(Cb)/V(Cr) planes
 * and ARGB bitmaps without external model weights.
 *
 * Combines:
 * - Chai/Ngan & Kovac YCbCr skin chrominance bounds (Cb ~ 77..127, Cr ~ 133..173)
 * - Luminance-adaptive relaxation in shadows/bright highlights + clipping guard (Y in 20..246)
 * - Achromatic rejection (|Cb - 128| + |Cr - 128| >= 18) to prevent white/gray walls matching
 * - Spatial compactness metric (skinPixels / bboxArea) to distinguish coherent skin blobs from scattered warm noise
 */
object YcbcrSkinAnalyzer {

    data class SkinMetrics(
        val totalSamples: Int,
        val skinSamples: Int,
        val skinFraction: Float,   // 0f..1f
        val compactness: Float,    // 0f..1f (skinSamples / bboxSamples)
        val clippedFraction: Float,
        val meanCb: Int,
        val meanCr: Int,
        val normBboxLeft: Float,
        val normBboxTop: Float,
        val normBboxRight: Float,
        val normBboxBottom: Float
    ) {
        val isCoherentSkinPresent: Boolean
            get() = skinFraction in 0.018f..0.85f && compactness >= 0.18f
    }

    /**
     * Precomputed 256x256 YCbCr chrominance LUT (64 KB, branch-free O(1) lookup per pixel).
     * Indexed by ((cb and 0xFF) shl 8) or (cr and 0xFF).
     */
    private val SKIN_CBCR_LUT = BooleanArray(65536).apply {
        for (cb in 0..255) {
            for (cr in 0..255) {
                val chromaMag = abs(cb - 128) + abs(cr - 128)
                val isSkin = chromaMag >= 18 &&
                    cb in 77..127 &&
                    cr in 133..175 &&
                    (cr - cb) >= 12
                this[(cb shl 8) or cr] = isSkin
            }
        }
    }

    /**
     * Exact integer YCbCr skin predicate for 8-bit unsigned components (Y, Cb, Cr in 0..255).
     * Note: U plane in Android YUV_420_888 = Cb; V plane = Cr.
     */
    fun isHumanSkinYCbCr(y: Int, cb: Int, cr: Int): Boolean {
        if (y < 20 || y > 246) return false
        if (y in 68..210) {
            return SKIN_CBCR_LUT[((cb and 0xFF) shl 8) or (cr and 0xFF)]
        }

        val chromaMag = abs(cb - 128) + abs(cr - 128)
        if (chromaMag < 18) return false

        // Luminance-compensated skin chrominance band in shadows/highlights
        var cbMin = 77
        var cbMax = 127
        var crMin = 133
        var crMax = 175

        if (y < 68) {
            cbMin -= 4
            cbMax += 4
            crMin -= 3
            crMax += 5
        } else {
            cbMax += 4
            crMin -= 4
        }

        if (cb !in cbMin..cbMax || cr !in crMin..crMax) return false
        if ((cr - cb) < 12) return false
        return true
    }

    /**
     * Branch-free LUT lookup with optional scene white-balance adaptation offset (cbShift/crShift in -5..5).
     */
    fun isHumanSkinYCbCrFast(y: Int, cb: Int, cr: Int, cbShift: Int = 0, crShift: Int = 0): Boolean {
        if (y < 20 || y > 246) return false
        if (cbShift == 0 && crShift == 0) {
            return if (y in 68..210) {
                SKIN_CBCR_LUT[((cb and 0xFF) shl 8) or (cr and 0xFF)]
            } else {
                isHumanSkinYCbCr(y, cb, cr)
            }
        }
        val adjCb = (cb - cbShift).coerceIn(0, 255)
        val adjCr = (cr - crShift).coerceIn(0, 255)
        return SKIN_CBCR_LUT[(adjCb shl 8) or adjCr]
    }

    fun isHumanSkinRgb(r: Int, g: Int, b: Int): Boolean {
        // ITU-R BT.601 full-range YCbCr conversion using fast integer arithmetic
        val y = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
        val cb = (128 + ((-43 * r - 85 * g + 128 * b) shr 8)).coerceIn(0, 255)
        val cr = (128 + ((128 * r - 107 * g - 21 * b) shr 8)).coerceIn(0, 255)
        return isHumanSkinYCbCr(y, cb, cr)
    }

    /**
     * Fast subsampled skin analysis for ARGB Bitmap (used by 6-NN symbiosis & pre-capture checks).
     */
    fun analyzeBitmap(bitmap: Bitmap, gridStepTarget: Int = 64): SkinMetrics {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) {
            return SkinMetrics(0, 0, 0f, 0f, 0f, 128, 128, 0f, 0f, 0f, 0f)
        }
        val stepX = max(1, w / gridStepTarget)
        val stepY = max(1, h / gridStepTarget)

        var total = 0
        var skin = 0
        var clipped = 0
        var sumCb = 0L
        var sumCr = 0L

        var minGx = Int.MAX_VALUE
        var maxGx = -1
        var minGy = Int.MAX_VALUE
        var maxGy = -1

        val cols = (w + stepX - 1) / stepX
        val rows = (h + stepY - 1) / stepY

        var gy = 0
        var y = 0
        while (y < h) {
            var gx = 0
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val luma = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
                val cb = (128 + ((-43 * r - 85 * g + 128 * b) shr 8)).coerceIn(0, 255)
                val cr = (128 + ((128 * r - 107 * g - 21 * b) shr 8)).coerceIn(0, 255)

                if (r > 242 || luma > 246) clipped++
                if (isHumanSkinYCbCr(luma, cb, cr)) {
                    skin++
                    sumCb += cb
                    sumCr += cr
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                }
                total++
                x += stepX
                gx++
            }
            y += stepY
            gy++
        }

        if (total == 0) return SkinMetrics(0, 0, 0f, 0f, 0f, 128, 128, 0f, 0f, 0f, 0f)

        val skinFrac = skin.toFloat() / total.toFloat()
        val clippedFrac = clipped.toFloat() / total.toFloat()
        val bboxGridArea = if (skin > 0 && maxGx >= minGx && maxGy >= minGy) {
            (maxGx - minGx + 1) * (maxGy - minGy + 1)
        } else {
            0
        }
        val compactness = if (bboxGridArea > 0) (skin.toFloat() / bboxGridArea.toFloat()).coerceIn(0f, 1f) else 0f

        return SkinMetrics(
            totalSamples = total,
            skinSamples = skin,
            skinFraction = skinFrac,
            compactness = compactness,
            clippedFraction = clippedFrac,
            meanCb = if (skin > 0) (sumCb / skin).toInt() else 128,
            meanCr = if (skin > 0) (sumCr / skin).toInt() else 128,
            normBboxLeft = if (skin > 0) (minGx.toFloat() / max(1, cols)).coerceIn(0f, 1f) else 0f,
            normBboxTop = if (skin > 0) (minGy.toFloat() / max(1, rows)).coerceIn(0f, 1f) else 0f,
            normBboxRight = if (skin > 0) min(1f, (maxGx + 1).toFloat() / max(1, cols)) else 0f,
            normBboxBottom = if (skin > 0) min(1f, (maxGy + 1).toFloat() / max(1, rows)) else 0f
        )
    }
}
