package com.example.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * PhotoIqEngine:
 * Lightweight ARM64/CPU-friendly image quality enhancement engine designed for 4GB RAM Helio G85 (MT6769Z).
 * Works strictly in RAM with precomputed 256-entry LUTs and integer unsharp masking (no heavy native libs).
 */
object PhotoIqEngine {

    enum class PhotoIqProfile(val labelRu: String, val badgeRu: String) {
        OFF("ВЫКЛ (Чистый кадр)", "Обычный"),
        AUTO_IQ("Photo IQ Авто (Баланс + Резкость)", "Авто IQ"),
        TEXT_CRISP("Документ / Знаки (Чёткость + Антиблик)", "Текст+"),
        NIGHT_LIFT("Ночной буст (Тени + Шумодав)", "Ночь+")
    }

    data class PhotoAdjustments(
        val brightness: Float = 0f,     // -0.45f .. +0.45f
        val contrast: Float = 1.0f,     // 0.6f .. 1.6f
        val saturation: Float = 1.0f,   // 0.0f .. 1.8f
        val sharpness: Float = 0f,      // 0.0f .. 1.0f
        val dehazeClahe: Boolean = false
    )

    fun enhanceBitmap(
        source: Bitmap,
        profile: PhotoIqProfile,
        recycleSource: Boolean = false
    ): Bitmap {
        if (profile == PhotoIqProfile.OFF) {
            return if (recycleSource) source else source.copy(Bitmap.Config.ARGB_8888, false)
        }

        var working = when (profile) {
            PhotoIqProfile.AUTO_IQ -> {
                val contrasted = applyToneCurveLut(source, shadowLift = 0.14f, contrastBoost = 1.08f, recycleInput = recycleSource)
                val sharpened = applyNoiseGatedSharpen(contrasted, strength = 0.38f, noiseThreshold = 10)
                if (sharpened !== contrasted && !contrasted.isRecycled) contrasted.recycle()
                sharpened
            }
            PhotoIqProfile.TEXT_CRISP -> {
                val dehazed = ExifStripper.applyContrastDehaze(source)
                if (recycleSource && !source.isRecycled && dehazed !== source) source.recycle()
                val sharpened = applyNoiseGatedSharpen(dehazed, strength = 0.62f, noiseThreshold = 8)
                if (sharpened !== dehazed && !dehazed.isRecycled) dehazed.recycle()
                sharpened
            }
            PhotoIqProfile.NIGHT_LIFT -> {
                val denoised = applyChromaSmoothAndShadowLift(source, shadowLift = 0.30f, recycleInput = recycleSource)
                val sharpened = applyNoiseGatedSharpen(denoised, strength = 0.25f, noiseThreshold = 16)
                if (sharpened !== denoised && !denoised.isRecycled) denoised.recycle()
                sharpened
            }
            PhotoIqProfile.OFF -> source
        }
        return working
    }

    fun applyManualAdjustments(
        source: Bitmap,
        adj: PhotoAdjustments
    ): Bitmap {
        val base = if (adj.dehazeClahe) {
            ExifStripper.applyContrastDehaze(source)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val needsColorMatrix = adj.brightness != 0f || adj.contrast != 1.0f || adj.saturation != 1.0f
        val graded = if (needsColorMatrix) {
            val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix()
            if (adj.saturation != 1.0f) {
                val sat = ColorMatrix().apply { setSaturation(adj.saturation.coerceIn(0f, 2f)) }
                cm.postConcat(sat)
            }
            val c = adj.contrast.coerceIn(0.5f, 2.0f)
            val t = ((1f - c) * 128f) + (adj.brightness.coerceIn(-0.6f, 0.6f) * 255f)
            val bc = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(bc)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(base, 0f, 0f, paint)
            if (!base.isRecycled) base.recycle()
            out
        } else {
            base
        }

        return if (adj.sharpness > 0.02f) {
            val sharpened = applyNoiseGatedSharpen(graded, strength = adj.sharpness.coerceIn(0f, 1.2f), noiseThreshold = 9)
            if (sharpened !== graded && !graded.isRecycled) graded.recycle()
            sharpened
        } else {
            graded
        }
    }

    /**
     * S-curve + shadow gamma lift using 256-entry integer LUT. O(N) with zero floating point in pixel loop.
     */
    fun applyToneCurveLut(
        source: Bitmap,
        shadowLift: Float = 0.14f,
        contrastBoost: Float = 1.08f,
        recycleInput: Boolean = false
    ): Bitmap {
        val w = source.width
        val h = source.height
        val lut = IntArray(256)
        val gamma = (1.0f - shadowLift.coerceIn(0f, 0.55f)).coerceIn(0.45f, 1.0f)
        for (i in 0..255) {
            val norm = i / 255.0
            val lifted = norm.pow(gamma.toDouble())
            val s = 0.5 + (lifted - 0.5) * contrastBoost
            lut[i] = (s * 255.0).toInt().coerceIn(0, 255)
        }

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        if (recycleInput && !source.isRecycled) source.recycle()

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = lut[(p ushr 16) and 0xFF]
            val g = lut[(p ushr 8) and 0xFF]
            val b = lut[p and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Integer 3x3 Unsharp Mask with noise-floor coring (prevents amplifying flat sensor grain).
     */
    fun applyNoiseGatedSharpen(
        source: Bitmap,
        strength: Float = 0.40f,
        noiseThreshold: Int = 10
    ): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 4 || h < 4 || strength <= 0.01f) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        System.arraycopy(src, 0, dst, 0, src.size)

        val gainQ8 = (strength.coerceIn(0f, 1.5f) * 256f).toInt()
        for (y in 1 until h - 1) {
            val row = y * w
            val rowUp = row - w
            val rowDn = row + w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val cLum = (cr * 77 + cg * 150 + cb * 29) ushr 8

                val u = src[rowUp + x]
                val d = src[rowDn + x]
                val l = src[idx - 1]
                val r = src[idx + 1]

                val avgNeighborLum = (
                    (((u ushr 16) and 0xFF) * 77 + ((u ushr 8) and 0xFF) * 150 + (u and 0xFF) * 29) +
                        (((d ushr 16) and 0xFF) * 77 + ((d ushr 8) and 0xFF) * 150 + (d and 0xFF) * 29) +
                        (((l ushr 16) and 0xFF) * 77 + ((l ushr 8) and 0xFF) * 150 + (l and 0xFF) * 29) +
                        (((r ushr 16) and 0xFF) * 77 + ((r ushr 8) and 0xFF) * 150 + (r and 0xFF) * 29)
                    ) ushr 10 // / 256 / 4

                val detail = cLum - avgNeighborLum
                if (kotlin.math.abs(detail) <= noiseThreshold) continue

                val delta = (detail * gainQ8) shr 8
                val nr = (cr + delta).coerceIn(0, 255)
                val ng = (cg + delta).coerceIn(0, 255)
                val nb = (cb + delta).coerceIn(0, 255)
                dst[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Night Lift: Lifts dark zones while applying gentle spatial smoothing in deep shadow pixels
     * so night scenes on Helio G85 don't look speckled.
     */
    fun applyChromaSmoothAndShadowLift(
        source: Bitmap,
        shadowLift: Float = 0.30f,
        recycleInput: Boolean = false
    ): Bitmap {
        val lifted = applyToneCurveLut(source, shadowLift = shadowLift, contrastBoost = 1.05f, recycleInput = recycleInput)
        val w = lifted.width
        val h = lifted.height
        if (w < 4 || h < 4) return lifted

        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        lifted.getPixels(src, 0, w, 0, 0, w, h)
        System.arraycopy(src, 0, dst, 0, src.size)
        if (!lifted.isRecycled) lifted.recycle()

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val lum = (cr * 77 + cg * 150 + cb * 29) ushr 8
                if (lum < 72) {
                    val u = src[idx - w]
                    val d = src[idx + w]
                    val l = src[idx - 1]
                    val r = src[idx + 1]
                    val sr = (cr * 2 + ((u ushr 16) and 0xFF) + ((d ushr 16) and 0xFF) + ((l ushr 16) and 0xFF) + ((r ushr 16) and 0xFF)) / 6
                    val sg = (cg * 2 + ((u ushr 8) and 0xFF) + ((d ushr 8) and 0xFF) + ((l ushr 8) and 0xFF) + ((r ushr 8) and 0xFF)) / 6
                    val sb = (cb * 2 + (u and 0xFF) + (d and 0xFF) + (l and 0xFF) + (r and 0xFF)) / 6
                    dst[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Computes a 32-bin luma histogram [0..1] from any Bitmap for the Vault Photo Editor.
     */
    fun computeBitmapHistogram32(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val bins = IntArray(32)
        val stepX = max(1, w / 96)
        val stepY = max(1, h / 96)
        var maxBin = 1
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val lum = ((r * 77 + g * 150 + b * 29) ushr 8).coerceIn(0, 255)
                val binIdx = min(31, lum ushr 3)
                val v = ++bins[binIdx]
                if (v > maxBin) maxBin = v
            }
        }
        return FloatArray(32) { i -> bins[i].toFloat() / maxBin.toFloat() }
    }
}
