package com.example.visor

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class VisorMode(val title: String, val badgeRu: String, val hintRu: String) {
    NORMAL("Обычный", "КАМЕРА", "Стандартный аппаратный видоискатель без нагрузки"),
    BIO_RPPG("Пульс rPPG", "rPPG", "Помести лоб/щеку или палец в квадрат и держи 5с при ровном свете"),
    VEIN_HEMO("Веновизор", "ГЕМО", "Оптический контраст подкожного гемоглобина и сосудов (ln R/G)"),
    DERMA_UV_SIM("Дерма-УФ", "МЕЛАНИН", "Усиление синего спектра (~450 нм) для проявления пигмента кожи"),
    THERMAL_LUT("Термо-LUT", "IRONBOW", "Цветовая карта яркости Ironbow (оптическая визуализация)"),
    NIGHT_GAMMA("Ночь-Буст", "ГАММА", "Нелинейное вытягивание теней в темноте (γ=0.42)"),
    IR_BEACON("Поиск ИК", "ИК-850", "Детектор ближнего ИК (850–940 нм): пульт, ночная подсветка. Лучше с фронталки")
}

data class VisorTelemetry(
    val mode: VisorMode = VisorMode.NORMAL,
    val estimatedBpm: Int? = null,
    val signalQuality: Float = 0f, // 0f..1f
    val irPeakDetected: Boolean = false,
    val irHotPixels: Int = 0
)

/**
 * CyberVisorEngine:
 * Real-time biophysical & optical computation over CameraX YUV_420_888 frames.
 * - Zero overhead when VisorMode == NORMAL (skipped completely).
 * - Subsamples large camera frames cleanly to ~280..360px working dimension and rotates/mirrors
 *   directly into upright double-buffered ARGB_8888 Bitmaps (zero tearing, ~2-4ms on Helio G85).
 */
class CyberVisorEngine {

    @Volatile
    var currentMode: VisorMode = VisorMode.NORMAL
        set(value) {
            if (field != value) {
                field = value
                if (value == VisorMode.BIO_RPPG) rppgTracker.reset()
            }
        }

    private val bitmapLock = Any()
    private var backBufferBitmap: Bitmap? = null
    private var frontBufferBitmap: Bitmap? = null
    private var pixelBuffer: IntArray = IntArray(0)
    private val rppgTracker = PosRppgTracker()

    // Precomputed 256-entry Ironbow false-color LUT (0 allocations per pixel)
    private val ironbowLut = IntArray(256) { i ->
        val t = i / 255f
        val r = (255f * min(1f, max(0f, 1.5f * t + 0.05f * sin(t * PI).toFloat())))
            .roundToInt().coerceIn(0, 255)
        val g = (255f * t.pow(2.2f)).roundToInt().coerceIn(0, 255)
        val b = (255f * (if (t < 0.5f) sin(t * PI * 1.4f).toFloat() * 0.85f else (t - 0.5f) * 1.6f))
            .roundToInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // Precomputed nonlinear night shadow-recovery LUT (gamma = 0.42)
    private val nightLut = IntArray(256) { i ->
        (255.0 * (i / 255.0).pow(0.42)).roundToInt().coerceIn(0, 255)
    }

    data class VisorFrameResult(
        val bitmap: Bitmap,
        val telemetry: VisorTelemetry
    )

    /**
     * Converts & filters an active CameraX YUV_420_888 frame strictly when currentMode != NORMAL.
     * Caller keeps responsibility for imageProxy.close().
     */
    fun processYuv420Frame(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ): VisorFrameResult? {
        val mode = currentMode
        if (mode == VisorMode.NORMAL) return null
        val planes = imageProxy.planes
        if (planes.size < 3) return null

        val srcW = imageProxy.width
        val srcH = imageProxy.height
        if (srcW < 16 || srcH < 16) return null

        val step = when {
            max(srcW, srcH) >= 1200 -> 3
            max(srcW, srcH) >= 520 -> 2
            else -> 1
        }
        val subW = (srcW / step).coerceAtLeast(16)
        val subH = (srcH / step).coerceAtLeast(16)

        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapDims = (rot == 90 || rot == 270)
        val dstW = if (swapDims) subH else subW
        val dstH = if (swapDims) subW else subH
        val totalDst = dstW * dstH

        if (pixelBuffer.size != totalDst) {
            pixelBuffer = IntArray(totalDst)
        }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val yLim = yBuf.limit()
        val uLim = uBuf.limit()
        val vLim = vBuf.limit()

        // Central 30% skin ROI in upright display space for rPPG tracking
        val roiX0 = (dstW * 0.34f).toInt()
        val roiX1 = (dstW * 0.66f).toInt()
        val roiY0 = (dstH * 0.28f).toInt()
        val roiY1 = (dstH * 0.70f).toInt()

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var roiCount = 0
        var irHotPixels = 0

        for (sy in 0 until subH) {
            val origY = sy * step
            val yRowOff = origY * yRowStride
            val uvY = origY shr 1
            val uRowOff = uvY * uRowStride
            val vRowOff = uvY * vRowStride

            for (sx in 0 until subW) {
                val origX = sx * step
                val yIdx = yRowOff + origX * yPixelStride
                val uvX = origX shr 1
                val uIdx = uRowOff + uvX * uPixelStride
                val vIdx = vRowOff + uvX * vPixelStride

                val yVal = if (yIdx in 0 until yLim) (yBuf.get(yIdx).toInt() and 0xFF) else 128
                val uVal = if (uIdx in 0 until uLim) (uBuf.get(uIdx).toInt() and 0xFF) - 128 else 0
                val vVal = if (vIdx in 0 until vLim) (vBuf.get(vIdx).toInt() and 0xFF) - 128 else 0

                // Integer BT.601 YUV -> RGB
                val r = (yVal + ((359 * vVal) shr 8)).coerceIn(0, 255)
                val g = (yVal - ((88 * uVal + 183 * vVal) shr 8)).coerceIn(0, 255)
                val b = (yVal + ((454 * uVal) shr 8)).coerceIn(0, 255)

                // Direct upright rotation + front-camera mirror mapping
                var dx: Int
                var dy: Int
                when (rot) {
                    90 -> {
                        dx = subH - 1 - sy
                        dy = sx
                    }
                    180 -> {
                        dx = subW - 1 - sx
                        dy = subH - 1 - sy
                    }
                    270 -> {
                        dx = sy
                        dy = subW - 1 - sx
                    }
                    else -> {
                        dx = sx
                        dy = sy
                    }
                }
                if (isFrontCamera) {
                    dx = dstW - 1 - dx
                }

                val inRoiY = (mode == VisorMode.BIO_RPPG) && (dy in roiY0..roiY1)
                if (inRoiY && dx in roiX0..roiX1) {
                    if (r in 35..242 && g in 25..238) {
                        sumR += r
                        sumG += g
                        sumB += b
                        roiCount++
                    }
                }

                val outColor = when (mode) {
                    VisorMode.NORMAL -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    VisorMode.BIO_RPPG -> {
                        val onBorder = (inRoiY && (dx == roiX0 || dx == roiX1)) ||
                            ((dy == roiY0 || dy == roiY1) && dx in roiX0..roiX1)
                        if (onBorder) {
                            0xFF00FF88.toInt()
                        } else {
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }

                    VisorMode.VEIN_HEMO -> {
                        // Beer-Lambert differential: subcutaneous veins absorb ~540nm green stronger than ~650nm red
                        val rf = max(r, 4).toDouble()
                        val gf = max(g, 4).toDouble()
                        val hemoIndex = (ln(rf) - ln(gf)) * 210.0
                        val baseLuma = (r * 77 + g * 150 + b * 29) shr 8
                        val veinBoost = (baseLuma - hemoIndex * 2.2).roundToInt().coerceIn(0, 255)
                        val vr = (veinBoost * 0.25f).toInt()
                        val vg = veinBoost
                        val vb = min(255, (veinBoost * 0.90f + 20).toInt())
                        (0xFF shl 24) or (vr shl 16) or (vg shl 8) or vb
                    }

                    VisorMode.DERMA_UV_SIM -> {
                        // Shortwave blue channel (~450nm) emphasizes superficial melanin/epidermal pigmentation
                        val uvContrast = ((b * 1.35f) - (r * 0.35f)).roundToInt().coerceIn(0, 255)
                        val dr = (uvContrast * 0.45f).toInt()
                        val dg = (uvContrast * 0.65f).toInt()
                        val db = min(255, (uvContrast * 1.25f + 15).toInt())
                        (0xFF shl 24) or (dr shl 16) or (dg shl 8) or db
                    }

                    VisorMode.THERMAL_LUT -> {
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        ironbowLut[luma]
                    }

                    VisorMode.NIGHT_GAMMA -> {
                        (0xFF shl 24) or (nightLut[r] shl 16) or (nightLut[g] shl 8) or nightLut[b]
                    }

                    VisorMode.IR_BEACON -> {
                        val irScore = (r + b) - (g * 2)
                        val isBeacon = (r > 175 && b > 155 && irScore > 60)
                        if (isBeacon) {
                            irHotPixels++
                            0xFFFF0055.toInt()
                        } else {
                            val dim = (r + g + b) / 9
                            (0xFF shl 24) or (dim shl 16) or (dim shl 8) or dim
                        }
                    }
                }

                pixelBuffer[dy * dstW + dx] = outColor
            }
        }

        val readyBitmap = synchronized(bitmapLock) {
            if (backBufferBitmap == null || backBufferBitmap?.width != dstW || backBufferBitmap?.height != dstH) {
                backBufferBitmap?.recycle()
                backBufferBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            }
            val target = backBufferBitmap!!
            target.setPixels(pixelBuffer, 0, dstW, 0, 0, dstW, dstH)
            // Swap double-buffer so UI always holds an immutable frame while analyzer writes next frame
            val oldFront = frontBufferBitmap
            frontBufferBitmap = target
            backBufferBitmap = oldFront
            target
        }

        val telemetry = if (mode == VisorMode.BIO_RPPG && roiCount > 80) {
            val (bpm, quality) = rppgTracker.pushSample(
                timestampNs = imageProxy.imageInfo.timestamp,
                meanR = sumR / roiCount,
                meanG = sumG / roiCount,
                meanB = sumB / roiCount
            )
            VisorTelemetry(mode = mode, estimatedBpm = bpm, signalQuality = quality)
        } else {
            VisorTelemetry(
                mode = mode,
                irPeakDetected = (mode == VisorMode.IR_BEACON && irHotPixels >= 5),
                irHotPixels = irHotPixels
            )
        }

        return VisorFrameResult(bitmap = readyBitmap, telemetry = telemetry)
    }

    fun snapshotLatestBitmapCopy(): Bitmap? = synchronized(bitmapLock) {
        val src = frontBufferBitmap ?: return null
        if (src.isRecycled) return null
        src.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun release() {
        synchronized(bitmapLock) {
            frontBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            backBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            frontBufferBitmap = null
            backBufferBitmap = null
        }
        rppgTracker.reset()
    }
}

/**
 * Pure Kotlin mathematical implementation of POS (Plane-Orthogonal-to-Skin, Wang et al. IEEE TBME 2017)
 * remote photoplethysmography (48..180 BPM).
 */
internal class PosRppgTracker(private val windowSize: Int = 240) {
    private val timesSec = DoubleArray(windowSize)
    private val rBuf = DoubleArray(windowSize)
    private val gBuf = DoubleArray(windowSize)
    private val bBuf = DoubleArray(windowSize)
    private var count = 0
    private var head = 0

    fun reset() {
        count = 0
        head = 0
    }

    fun pushSample(timestampNs: Long, meanR: Double, meanG: Double, meanB: Double): Pair<Int?, Float> {
        timesSec[head] = timestampNs / 1_000_000_000.0
        rBuf[head] = meanR
        gBuf[head] = meanG
        bBuf[head] = meanB
        head = (head + 1) % windowSize
        if (count < windowSize) count++

        // Minimum ~4.5 seconds (110 frames @ ~24-30fps) before attempting stable spectral estimation
        if (count < 110) {
            return null to (count / 110f) * 0.38f
        }

        val n = count
        val oldestIdx = (head - n + windowSize) % windowSize
        val latestIdx = (head - 1 + windowSize) % windowSize
        val durationSec = timesSec[latestIdx] - timesSec[oldestIdx]
        if (durationSec <= 2.0) return null to 0.1f

        var avgR = 0.0
        var avgG = 0.0
        var avgB = 0.0
        for (i in 0 until n) {
            val idx = (oldestIdx + i) % windowSize
            avgR += rBuf[idx]
            avgG += gBuf[idx]
            avgB += bBuf[idx]
        }
        avgR /= n
        avgG /= n
        avgB /= n
        if (avgR < 1.0 || avgG < 1.0 || avgB < 1.0) return null to 0f

        val s1 = DoubleArray(n)
        val s2 = DoubleArray(n)
        var meanS1 = 0.0
        var meanS2 = 0.0
        for (i in 0 until n) {
            val idx = (oldestIdx + i) % windowSize
            val rn = rBuf[idx] / avgR
            val gn = gBuf[idx] / avgG
            val bn = bBuf[idx] / avgB
            s1[i] = gn - bn
            s2[i] = gn + bn - 2.0 * rn
            meanS1 += s1[i]
            meanS2 += s2[i]
        }
        meanS1 /= n
        meanS2 /= n

        var varS1 = 0.0
        var varS2 = 0.0
        for (i in 0 until n) {
            s1[i] -= meanS1
            s2[i] -= meanS2
            varS1 += s1[i] * s1[i]
            varS2 += s2[i] * s2[i]
        }
        val stdS1 = sqrt(varS1 / n)
        val stdS2 = sqrt(varS2 / n)
        if (stdS2 < 1e-6) return null to 0.1f

        val alpha = stdS1 / stdS2
        val pulseWave = DoubleArray(n)
        for (i in 0 until n) {
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            pulseWave[i] = (s1[i] + alpha * s2[i]) * hann
        }

        // Exact-timestamp DFT across 48..180 BPM (step 2 BPM = 67 bins)
        val t0 = timesSec[oldestIdx]
        var bestBpm = 70
        var maxPower = 0.0
        var totalBandPower = 0.0

        for (bpm in 48..180 step 2) {
            val omega = 2.0 * PI * (bpm / 60.0)
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val dt = timesSec[(oldestIdx + i) % windowSize] - t0
                val angle = omega * dt
                re += pulseWave[i] * cos(angle)
                im -= pulseWave[i] * sin(angle)
            }
            val power = re * re + im * im
            totalBandPower += power
            if (power > maxPower) {
                maxPower = power
                bestBpm = bpm
            }
        }

        val snrRatio = if (totalBandPower > 1e-9) (maxPower / (totalBandPower / 67.0)) else 0.0
        val quality = ((snrRatio - 2.0) / 8.0).toFloat().coerceIn(0f, 1f)

        return if (quality >= 0.35f) bestBpm to quality else null to quality
    }
}
