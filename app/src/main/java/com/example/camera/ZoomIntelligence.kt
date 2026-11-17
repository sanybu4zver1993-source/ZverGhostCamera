package com.example.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max

/**
 * ZoomIntelligence:
 * Compensates softness, haze, and micro-shake blur when shooting at 2x..10x digital zoom on Redmi 13C.
 * - Categorizes zoom tier (1x optical/full, 2x..3.9x mid, 4x..5.9x tele, 6x..10x hyper-tele).
 * - Applies adaptive edge restoration + micro-contrast + slight saturation/dehaze lift without heavy ML overhead.
 */
object ZoomIntelligence {

    data class ZoomTierAdvice(
        val zoomRatio: Float,
        val badgeText: String,
        val requiresSteadyHand: Boolean,
        val sharpenStrength: Float,
        val microContrastBoost: Float
    )

    fun evaluateZoomTier(zoomRatio: Float): ZoomTierAdvice {
        return when {
            zoomRatio >= 6.0f -> ZoomTierAdvice(
                zoomRatio = zoomRatio,
                badgeText = "ZOOM IQ ${zoomRatio.toInt()}x • ТЕЛЕ-РЕСТАВРАЦИЯ",
                requiresSteadyHand = true,
                sharpenStrength = 0.72f,
                microContrastBoost = 1.16f
            )
            zoomRatio >= 3.5f -> ZoomTierAdvice(
                zoomRatio = zoomRatio,
                badgeText = "ZOOM IQ ${String.format(java.util.Locale.US, "%.1fx", zoomRatio)} • ДЕТАЛИ",
                requiresSteadyHand = true,
                sharpenStrength = 0.50f,
                microContrastBoost = 1.10f
            )
            zoomRatio >= 1.8f -> ZoomTierAdvice(
                zoomRatio = zoomRatio,
                badgeText = "ZOOM IQ ${String.format(java.util.Locale.US, "%.1fx", zoomRatio)} • ЧЁТКОСТЬ",
                requiresSteadyHand = false,
                sharpenStrength = 0.30f,
                microContrastBoost = 1.05f
            )
            else -> ZoomTierAdvice(
                zoomRatio = zoomRatio,
                badgeText = "1x БАЗОВЫЙ",
                requiresSteadyHand = false,
                sharpenStrength = 0f,
                microContrastBoost = 1.0f
            )
        }
    }

    /**
     * Enhances a digitally zoomed shot in RAM:
     * 1. Gentle contrast/dehaze lift tuned to zoom magnification.
     * 2. Noise-gated multi-pass micro-sharpening to recover letters/plates/details smeared by HAL upscaling.
     */
    fun enhanceZoomedBitmap(
        source: Bitmap,
        zoomRatio: Float,
        recycleSource: Boolean = false
    ): Bitmap {
        val advice = evaluateZoomTier(zoomRatio)
        if (advice.sharpenStrength <= 0.01f) {
            return if (recycleSource) source else source.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Step 1: Tone + slight micro-contrast lift to counter telephoto atmospheric haze
        val contrasted = PhotoIqEngine.applyToneCurveLut(
            source = source,
            shadowLift = if (zoomRatio >= 6f) 0.12f else 0.06f,
            contrastBoost = advice.microContrastBoost,
            recycleInput = recycleSource
        )

        // Step 2: Noise-gated edge sharpening
        val sharpened = PhotoIqEngine.applyNoiseGatedSharpen(
            source = contrasted,
            strength = advice.sharpenStrength,
            noiseThreshold = if (zoomRatio >= 6f) 11 else 9
        )
        if (sharpened !== contrasted && !contrasted.isRecycled) {
            contrasted.recycle()
        }
        return sharpened
    }

    /**
     * High-detail center super-crop helper (creates crisp upscaled detail snippet cleanly in RAM).
     */
    fun extractZoomDetailCrop(
        source: Bitmap,
        cropFraction: Float = 0.5f,
        maxOutDim: Int = 1600
    ): Bitmap {
        val frac = cropFraction.coerceIn(0.20f, 0.90f)
        val cw = max(64, (source.width * frac).toInt())
        val ch = max(64, (source.height * frac).toInt())
        val left = (source.width - cw) / 2
        val top = (source.height - ch) / 2
        val rawCrop = Bitmap.createBitmap(source, left, top, cw, ch)

        val targetScale = if (max(cw, ch) < maxOutDim) {
            minOf(2.0f, maxOutDim.toFloat() / max(cw, ch).toFloat())
        } else {
            1.0f
        }
        val upscaled = if (targetScale > 1.05f) {
            val out = Bitmap.createScaledBitmap(
                rawCrop,
                (cw * targetScale).toInt() and 0xFFFE,
                (ch * targetScale).toInt() and 0xFFFE,
                true
            )
            rawCrop.recycle()
            out
        } else {
            rawCrop
        }
        return enhanceZoomedBitmap(upscaled, zoomRatio = 6.0f, recycleSource = true)
    }
}
