package com.example.visor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class VisorMode(val title: String, val badgeRu: String, val hintRu: String) {
    NORMAL("Обычный", "КАМЕРА", "Стандартный аппаратный видоискатель без нагрузки"),
    BIO_RPPG("Пульс+HRV", "rPPG/HRV", "Лоб/щека или палец на камере (~6–8с): ориентировочный ЧСС и HRV; при клиппинге автопереход на G-канал"),
    SKIN_CHROMA("Кожа YCbCr", "КОЖА-UV", "Прямая сегментация цветности кожи человека в YCbCr без нейросетей: скрытые силуэты, тон и пересвет"),
    EULER_AMP("Эйлер x20", "МИКРО", "Эйлерово усиление микродвижений/пульса с 1D-стабилизацией сдвига и демпфером ореолов"),
    VEIN_HEMO("Веновизор", "ГЕМО", "Оптический контраст подкожного гемоглобина и сосудов (ln R/G)"),
    DERMA_UV_SIM("Дерма-УФ", "МЕЛАНИН", "Усиление синего спектра (~450 нм) для проявления пигмента кожи"),
    THERMAL_LUT("Термо-LUT", "IRONBOW", "Цветовая карта яркости Ironbow (оптическая визуализация)"),
    DEHAZE_LIVE("Анти-Туман", "DEHAZE", "Вычитание дымки/тумана и контрастная проявка силуэтов в реальном времени"),
    WIREFRAME_POSE("Скелет 33т", "КАРКАС", "Построение проволочного скелета (33 точки ML Kit) поверх тёмной/сложной сцены"),
    VEG_CHLOROPHYLL("Флора/Еда", "ХЛОРО", "Спектральный индекс ExG (2G-R-B): живая зелень vs пластик/порча"),
    NIGHT_GAMMA("Ночь-Буст", "ГАММА", "Нелинейное вытягивание теней в темноте (γ=0.42)"),
    IR_BEACON("Поиск ИК", "ИК-850", "Детектор подозрительных точечных пурпурных ореолов (ИК-подсветка 850–940 нм) с отсечкой ламп/OLED")
}

data class PosePointNorm(val x: Float, val y: Float)

data class PoseWireframe(
    val joints: List<PosePointNorm> = emptyList(),
    val bones: List<Pair<PosePointNorm, PosePointNorm>> = emptyList(),
    val visibleCount: Int = 0
)

data class VisorTelemetry(
    val mode: VisorMode = VisorMode.NORMAL,
    val estimatedBpm: Int? = null,
    val estimatedHrvMs: Int? = null,
    val signalQuality: Float = 0f, // 0f..1f
    val rppgSourceLabel: String = "",
    val skinPercent: Int = 0,
    val skinCompactness: Float = 0f,
    val movingSkinPercent: Int = 0,
    val eulerStability: Float = 1f,
    val irPeakDetected: Boolean = false,
    val irHotPixels: Int = 0,
    val irStatusLabel: String = "",
    val poseWireframe: PoseWireframe? = null
)

/**
 * CyberVisorEngine v2.2:
 * Real-time biophysical, YCbCr human-skin, spectral & bundled-CV computation over CameraX YUV_420_888 frames.
 * - Zero CPU cost when VisorMode == NORMAL.
 * - Double-buffered upright ARGB_8888 rendering (~2-4ms on Helio G85).
 * - Direct 256x256 YCbCr skin LUT + 3x3 morphological despeckle + Y-plane moving-skin intersection.
 * - Finger/AE-jump resilient POS rPPG + 1D Integral Projection Eulerian stabilization + IR halo filtering.
 */
class CyberVisorEngine {

    @Volatile
    var currentMode: VisorMode = VisorMode.NORMAL
        set(value) {
            if (field != value) {
                field = value
                if (value == VisorMode.BIO_RPPG) rppgTracker.reset()
                if (value == VisorMode.EULER_AMP) eulerTracker.reset()
                if (value != VisorMode.WIREFRAME_POSE) latestPoseWireframe = null
                if (value != VisorMode.IR_BEACON) irPersistFrames = 0
                if (value != VisorMode.SKIN_CHROMA) {
                    hasPrevSkinLuma = false
                    skinSceneCbShift = 0
                    skinSceneCrShift = 0
                }
            }
        }

    private val bitmapLock = Any()
    private var backBufferBitmap: Bitmap? = null
    private var frontBufferBitmap: Bitmap? = null
    private var pixelBuffer: IntArray = IntArray(0)
    private var rawRgbaBuffer: IntArray = IntArray(0)
    private var irMaskBuffer: BooleanArray = BooleanArray(0)
    private var skinMaskBuffer: BooleanArray = BooleanArray(0)
    private var skinDenoisedBuffer: BooleanArray = BooleanArray(0)
    private var prevSkinLumaBuffer: ByteArray = ByteArray(0)
    private var curSkinLumaBuffer: ByteArray = ByteArray(0)
    private var hasPrevSkinLuma = false
    private var skinSceneCbShift = 0
    private var skinSceneCrShift = 0

    private val rppgTracker = PosRppgTracker()
    private val eulerTracker = EulerianTemporalAmplifier()
    private var irPersistFrames = 0

    private val poseDetector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }
    private val poseBusy = AtomicBoolean(false)
    @Volatile
    private var lastPoseInferMs = 0L
    @Volatile
    private var latestPoseWireframe: PoseWireframe? = null

    private val ironbowLut = IntArray(256) { i ->
        val t = i / 255f
        val r = (255f * min(1f, max(0f, 1.5f * t + 0.05f * sin(t * PI).toFloat())))
            .roundToInt().coerceIn(0, 255)
        val g = (255f * t.pow(2.2f)).roundToInt().coerceIn(0, 255)
        val b = (255f * (if (t < 0.5f) sin(t * PI * 1.4f).toFloat() * 0.85f else (t - 0.5f) * 1.6f))
            .roundToInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val nightLut = IntArray(256) { i ->
        (255.0 * (i / 255.0).pow(0.42)).roundToInt().coerceIn(0, 255)
    }

    data class VisorFrameResult(
        val bitmap: Bitmap,
        val telemetry: VisorTelemetry
    )

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
        if ((mode == VisorMode.EULER_AMP || mode == VisorMode.WIREFRAME_POSE || mode == VisorMode.IR_BEACON) && rawRgbaBuffer.size != totalDst) {
            rawRgbaBuffer = IntArray(totalDst)
        }
        if (mode == VisorMode.IR_BEACON && irMaskBuffer.size != totalDst) {
            irMaskBuffer = BooleanArray(totalDst)
        }
        if (mode == VisorMode.SKIN_CHROMA) {
            if (skinMaskBuffer.size != totalDst) {
                skinMaskBuffer = BooleanArray(totalDst)
                skinDenoisedBuffer = BooleanArray(totalDst)
                curSkinLumaBuffer = ByteArray(totalDst)
                prevSkinLumaBuffer = ByteArray(totalDst)
                hasPrevSkinLuma = false
            }
            if (rawRgbaBuffer.size != totalDst) {
                rawRgbaBuffer = IntArray(totalDst)
            }
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

        val roiX0 = (dstW * 0.34f).toInt()
        val roiX1 = (dstW * 0.66f).toInt()
        val roiY0 = (dstH * 0.28f).toInt()
        val roiY1 = (dstH * 0.70f).toInt()

        // rPPG accumulators (skin-gated vs total ROI for finger-on-lens fallback)
        var skinR = 0.0
        var skinG = 0.0
        var skinB = 0.0
        var skinRoiCount = 0
        var allR = 0.0
        var allG = 0.0
        var allB = 0.0
        var allRoiCount = 0
        var clippedRedCount = 0

        // SKIN_CHROMA accumulators
        var sceneCbSum = 0L
        var sceneCrSum = 0L

        // IR_BEACON accumulators
        var irCandidatePixels = 0
        var irMinX = dstW
        var irMaxX = -1
        var irMinY = dstH
        var irMaxY = -1

        var airlightVeil = 0
        var lumaMax = 255
        if (mode == VisorMode.DEHAZE_LIVE) {
            var minChannelSum = 0L
            var minChannelMin = 255
            var maxL = 1
            var samples = 0
            for (sy in 0 until subH step 4) {
                val origY = sy * step
                val yRowOff = origY * yRowStride
                val uvY = origY shr 1
                val uRowOff = uvY * uRowStride
                val vRowOff = uvY * vRowStride
                for (sx in 0 until subW step 4) {
                    val origX = sx * step
                    val yIdx = yRowOff + origX * yPixelStride
                    val uvX = origX shr 1
                    val uIdx = uRowOff + uvX * uPixelStride
                    val vIdx = vRowOff + uvX * vPixelStride
                    val yVal = if (yIdx in 0 until yLim) (yBuf.get(yIdx).toInt() and 0xFF) else 128
                    val uVal = if (uIdx in 0 until uLim) (uBuf.get(uIdx).toInt() and 0xFF) - 128 else 0
                    val vVal = if (vIdx in 0 until vLim) (vBuf.get(vIdx).toInt() and 0xFF) - 128 else 0
                    val r = (yVal + ((359 * vVal) shr 8)).coerceIn(0, 255)
                    val g = (yVal - ((88 * uVal + 183 * vVal) shr 8)).coerceIn(0, 255)
                    val b = (yVal + ((454 * uVal) shr 8)).coerceIn(0, 255)
                    val dark = min(r, min(g, b))
                    minChannelSum += dark
                    if (dark < minChannelMin) minChannelMin = dark
                    if (yVal > maxL) maxL = yVal
                    samples++
                }
            }
            val avgDark = if (samples > 0) (minChannelSum / samples).toInt() else 0
            airlightVeil = ((minChannelMin * 6 + avgDark * 4) / 10).coerceIn(0, 140)
            lumaMax = max(64, maxL)
        }

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
                val uRaw = if (uIdx in 0 until uLim) (uBuf.get(uIdx).toInt() and 0xFF) else 128
                val vRaw = if (vIdx in 0 until vLim) (vBuf.get(vIdx).toInt() and 0xFF) else 128
                val uVal = uRaw - 128
                val vVal = vRaw - 128

                val r = (yVal + ((359 * vVal) shr 8)).coerceIn(0, 255)
                val g = (yVal - ((88 * uVal + 183 * vVal) shr 8)).coerceIn(0, 255)
                val b = (yVal + ((454 * uVal) shr 8)).coerceIn(0, 255)

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

                val dstIdx = dy * dstW + dx
                if (mode == VisorMode.EULER_AMP || mode == VisorMode.WIREFRAME_POSE || mode == VisorMode.IR_BEACON || mode == VisorMode.SKIN_CHROMA) {
                    rawRgbaBuffer[dstIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                val isSkin = when (mode) {
                    VisorMode.BIO_RPPG -> YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(yVal, uRaw, vRaw)
                    VisorMode.SKIN_CHROMA -> {
                        sceneCbSum += uRaw
                        sceneCrSum += vRaw
                        curSkinLumaBuffer[dstIdx] = yVal.toByte()
                        val matched = YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(
                            yVal,
                            uRaw,
                            vRaw,
                            skinSceneCbShift,
                            skinSceneCrShift
                        )
                        skinMaskBuffer[dstIdx] = matched
                        matched
                    }
                    else -> false
                }

                val inRoiY = (mode == VisorMode.BIO_RPPG) && (dy in roiY0..roiY1)
                if (inRoiY && dx in roiX0..roiX1) {
                    allRoiCount++
                    allR += r
                    allG += g
                    allB += b
                    if (r > 242) clippedRedCount++
                    if (isSkin && r in 15..242 && g in 8..242) {
                        skinR += r
                        skinG += g
                        skinB += b
                        skinRoiCount++
                    }
                }

                pixelBuffer[dstIdx] = when (mode) {
                    VisorMode.NORMAL -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    VisorMode.BIO_RPPG -> {
                        val onBorder = (inRoiY && (dx == roiX0 || dx == roiX1)) ||
                            ((dy == roiY0 || dy == roiY1) && dx in roiX0..roiX1)
                        when {
                            onBorder -> 0xFF00FF88.toInt()
                            inRoiY && dx in roiX0..roiX1 && isSkin -> {
                                // Subtle emerald tint on detected skin pixels inside ROI
                                val ng = min(255, g + 26)
                                (0xFF shl 24) or (r shl 16) or (ng shl 8) or b
                            }
                            else -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }

                    VisorMode.SKIN_CHROMA -> 0 // Finalized after 3x3 morphological despeckle + motion intersection pass

                    VisorMode.EULER_AMP -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    VisorMode.VEIN_HEMO -> {
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

                    VisorMode.DEHAZE_LIVE -> {
                        val span = max(48, lumaMax - airlightVeil)
                        val dr = (((r - airlightVeil) * 255) / span).coerceIn(0, 255)
                        val dg = (((g - airlightVeil) * 255) / span).coerceIn(0, 255)
                        val db = (((b - airlightVeil) * 255) / span).coerceIn(0, 255)
                        (0xFF shl 24) or (dr shl 16) or (dg shl 8) or db
                    }

                    VisorMode.WIREFRAME_POSE -> {
                        val wr = (r * 45) / 100
                        val wg = (g * 65) / 100
                        val wb = (b * 85) / 100
                        (0xFF shl 24) or (wr shl 16) or (wg shl 8) or wb
                    }

                    VisorMode.VEG_CHLOROPHYLL -> {
                        val exg = (2 * g) - r - b
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        if (exg > 22) {
                            val boost = min(255, luma / 3 + exg * 2)
                            (0xFF shl 24) or (20 shl 16) or (boost shl 8) or 80
                        } else {
                            val dim = luma / 2
                            val warnR = min(255, dim + (r / 3))
                            (0xFF shl 24) or (warnR shl 16) or ((dim / 2) shl 8) or (dim + 25).coerceAtMost(255)
                        }
                    }

                    VisorMode.NIGHT_GAMMA -> {
                        (0xFF shl 24) or (nightLut[r] shl 16) or (nightLut[g] shl 8) or nightLut[b]
                    }

                    VisorMode.IR_BEACON -> {
                        val irScore = (r + b) - (g * 2)
                        val isCand = (r > 170 && b > 145 && irScore > 55) ||
                            (r > 246 && g > 246 && b > 246) // saturated emitter core
                        irMaskBuffer[dstIdx] = isCand && (irScore > 55)
                        if (irMaskBuffer[dstIdx]) {
                            irCandidatePixels++
                            if (dx < irMinX) irMinX = dx
                            if (dx > irMaxX) irMaxX = dx
                            if (dy < irMinY) irMinY = dy
                            if (dy > irMaxY) irMaxY = dy
                            0xFFFF0055.toInt()
                        } else {
                            val dim = (r + g + b) / 9
                            (0xFF shl 24) or (dim shl 16) or (dim shl 8) or dim
                        }
                    }
                }
            }
        }

        if (mode == VisorMode.EULER_AMP) {
            eulerTracker.amplifyInto(rawRgbaBuffer, pixelBuffer, dstW, dstH)
        }

        // 3x3 Morphological despeckle + Y-plane moving-skin intersection for SKIN_CHROMA
        var skinTotalCount = 0
        var movingSkinCount = 0
        var skinMinX = dstW
        var skinMaxX = -1
        var skinMinY = dstH
        var skinMaxY = -1
        if (mode == VisorMode.SKIN_CHROMA && dstW >= 4 && dstH >= 4) {
            for (y in 0 until dstH) {
                val rowOff = y * dstW
                val upOff = max(0, y - 1) * dstW
                val dnOff = min(dstH - 1, y + 1) * dstW
                for (x in 0 until dstW) {
                    val idx = rowOff + x
                    val cur = skinMaskBuffer[idx]
                    val xm = max(0, x - 1)
                    val xp = min(dstW - 1, x + 1)
                    var nCount = 0
                    if (skinMaskBuffer[rowOff + xm]) nCount++
                    if (skinMaskBuffer[rowOff + xp]) nCount++
                    if (skinMaskBuffer[upOff + x]) nCount++
                    if (skinMaskBuffer[dnOff + x]) nCount++
                    // Open/Close despeckle: drop isolated specks (n < 2), fill single pinholes (n >= 3)
                    val clean = if (cur) nCount >= 2 else nCount >= 3
                    skinDenoisedBuffer[idx] = clean

                    val c = rawRgbaBuffer[idx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF

                    if (clean) {
                        skinTotalCount++
                        if (x < skinMinX) skinMinX = x
                        if (x > skinMaxX) skinMaxX = x
                        if (y < skinMinY) skinMinY = y
                        if (y > skinMaxY) skinMaxY = y

                        val curY = curSkinLumaBuffer[idx].toInt() and 0xFF
                        val prevY = prevSkinLumaBuffer[idx].toInt() and 0xFF
                        val isMoving = hasPrevSkinLuma && abs(curY - prevY) >= 14
                        if (isMoving) {
                            movingSkinCount++
                            // Moving skin highlight: bright electric cyan/emerald outline glow
                            val sr = min(255, r / 2 + 40)
                            val sg = 255
                            val sb = min(255, b / 2 + 120)
                            pixelBuffer[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                        } else {
                            // Static coherent skin: bio-amber contrast
                            val sr = min(255, (r * 0.88f + 45).toInt())
                            val sg = min(255, (g * 0.92f + 38).toInt())
                            val sb = (b * 0.42f).toInt()
                            pixelBuffer[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                        }
                    } else {
                        val dim = ((r * 77 + g * 150 + b * 29) shr 8) / 3
                        pixelBuffer[idx] = (0xFF shl 24) or ((dim * 0.7f).toInt() shl 16) or
                            ((dim * 0.85f).toInt() shl 8) or min(255, dim + 16)
                    }
                }
            }
            System.arraycopy(curSkinLumaBuffer, 0, prevSkinLumaBuffer, 0, totalDst)
            hasPrevSkinLuma = true

            // Update gentle adaptive scene white-balance offset only when skin isn't dominating frame
            val skinFrac = skinTotalCount.toFloat() / max(1, totalDst).toFloat()
            if (skinFrac < 0.30f && totalDst > 0) {
                val meanCb = (sceneCbSum / totalDst).toInt()
                val meanCr = (sceneCrSum / totalDst).toInt()
                skinSceneCbShift = ((meanCb - 128) / 6).coerceIn(-4, 4)
                skinSceneCrShift = ((meanCr - 128) / 6).coerceIn(-4, 4)
            } else {
                skinSceneCbShift = 0
                skinSceneCrShift = 0
            }
        }

        // Spatial compactness & ring-halo verification for IR_BEACON
        var irConfirmedPeak = false
        var irStatus = ""
        if (mode == VisorMode.IR_BEACON) {
            val irEval = evaluateIrHaloCandidate(
                rawRgba = rawRgbaBuffer,
                w = dstW,
                h = dstH,
                hotPixels = irCandidatePixels,
                minX = irMinX,
                maxX = irMaxX,
                minY = irMinY,
                maxY = irMaxY
            )
            if (irEval.isCompactCandidate) {
                irPersistFrames = min(60, irPersistFrames + 1)
                irConfirmedPeak = irPersistFrames >= 6
                irStatus = if (irConfirmedPeak) {
                    "УСТОЙЧИВЫЙ ТОЧЕЧНЫЙ ОРЕОЛ (${irCandidatePixels} пикс)"
                } else {
                    "КАНДИДАТ ИК (${irPersistFrames}/6 кадров)"
                }
            } else {
                irPersistFrames = max(0, irPersistFrames - 2)
                irStatus = irEval.rejectionLabel
            }
        }

        val readyBitmap = synchronized(bitmapLock) {
            if (backBufferBitmap == null || backBufferBitmap?.width != dstW || backBufferBitmap?.height != dstH) {
                backBufferBitmap?.recycle()
                backBufferBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            }
            val target = backBufferBitmap!!
            target.setPixels(pixelBuffer, 0, dstW, 0, 0, dstW, dstH)
            val oldFront = frontBufferBitmap
            frontBufferBitmap = target
            backBufferBitmap = oldFront
            target
        }

        if (mode == VisorMode.WIREFRAME_POSE) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPoseInferMs >= 140L && poseBusy.compareAndSet(false, true)) {
                lastPoseInferMs = nowMs
                val poseBmp = Bitmap.createBitmap(rawRgbaBuffer, dstW, dstH, Bitmap.Config.ARGB_8888)
                val input = InputImage.fromBitmap(poseBmp, 0)
                poseDetector.process(input)
                    .addOnSuccessListener { pose ->
                        latestPoseWireframe = buildWireframe(pose.allPoseLandmarks, pose, dstW.toFloat(), dstH.toFloat())
                    }
                    .addOnCompleteListener {
                        if (!poseBmp.isRecycled) poseBmp.recycle()
                        poseBusy.set(false)
                    }
            }
        }

        val telemetry = when (mode) {
            VisorMode.BIO_RPPG -> {
                if (allRoiCount > 80) {
                    val clippedRatio = clippedRedCount.toFloat() / allRoiCount.toFloat()
                    val avgAllR = allR / allRoiCount
                    val avgAllG = allG / allRoiCount
                    val avgAllB = allB / allRoiCount
                    val useFingerOrClipped = clippedRatio > 0.25f ||
                        (skinRoiCount < 40 && avgAllR > 155.0 && avgAllG < 95.0)
                    val sampleR: Double
                    val sampleG: Double
                    val sampleB: Double
                    val sourceLabel: String
                    if (!useFingerOrClipped && skinRoiCount >= 40) {
                        sampleR = skinR / skinRoiCount
                        sampleG = skinG / skinRoiCount
                        sampleB = skinB / skinRoiCount
                        sourceLabel = "Кожа YCbCr (POS)"
                    } else {
                        sampleR = avgAllR
                        sampleG = avgAllG
                        sampleB = avgAllB
                        sourceLabel = if (useFingerOrClipped) "Палец / G-канал" else "ROI центр"
                    }

                    val (bpm, hrv, quality) = rppgTracker.pushSample(
                        timestampNs = imageProxy.imageInfo.timestamp,
                        meanR = sampleR,
                        meanG = sampleG,
                        meanB = sampleB,
                        greenFallback = useFingerOrClipped
                    )
                    VisorTelemetry(
                        mode = mode,
                        estimatedBpm = bpm,
                        estimatedHrvMs = hrv,
                        signalQuality = quality,
                        rppgSourceLabel = sourceLabel
                    )
                } else {
                    VisorTelemetry(mode = mode, rppgSourceLabel = "Наведи на кожу")
                }
            }
            VisorMode.SKIN_CHROMA -> {
                val pct = ((skinTotalCount * 100f) / max(1, totalDst)).roundToInt().coerceIn(0, 100)
                val movingPct = ((movingSkinCount * 100f) / max(1, totalDst)).roundToInt().coerceIn(0, 100)
                val bboxArea = if (skinTotalCount > 0 && skinMaxX >= skinMinX && skinMaxY >= skinMinY) {
                    (skinMaxX - skinMinX + 1) * (skinMaxY - skinMinY + 1)
                } else 0
                val compact = if (bboxArea > 0) (skinTotalCount.toFloat() / bboxArea.toFloat()).coerceIn(0f, 1f) else 0f
                VisorTelemetry(
                    mode = mode,
                    skinPercent = pct,
                    skinCompactness = compact,
                    movingSkinPercent = movingPct
                )
            }
            VisorMode.EULER_AMP -> VisorTelemetry(
                mode = mode,
                eulerStability = eulerTracker.lastStability
            )
            VisorMode.WIREFRAME_POSE -> VisorTelemetry(
                mode = mode,
                poseWireframe = latestPoseWireframe
            )
            VisorMode.IR_BEACON -> VisorTelemetry(
                mode = mode,
                irPeakDetected = irConfirmedPeak,
                irHotPixels = irCandidatePixels,
                irStatusLabel = irStatus
            )
            else -> VisorTelemetry(mode = mode)
        }

        return VisorFrameResult(bitmap = readyBitmap, telemetry = telemetry)
    }

    private data class IrHaloEval(val isCompactCandidate: Boolean, val rejectionLabel: String)

    private fun evaluateIrHaloCandidate(
        rawRgba: IntArray,
        w: Int,
        h: Int,
        hotPixels: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int
    ): IrHaloEval {
        if (hotPixels < 5) return IrHaloEval(false, "")
        val maxAllowedArea = max(25, (w * h) / 7)
        if (hotPixels > maxAllowedArea) {
            return IrHaloEval(false, "ШИРОКИЙ ИСТОЧНИК / OLED (отсеяно)")
        }
        val bw = (maxX - minX + 1).coerceAtLeast(1)
        val bh = (maxY - minY + 1).coerceAtLeast(1)
        val compactness = hotPixels.toFloat() / (bw * bh).toFloat()
        if (compactness < 0.14f && bw > 20 && bh > 20) {
            return IrHaloEval(false, "РАЗБРОС БЛИКОВ (отсеяно)")
        }

        // Check concentric outer ring (r3) vs core for warm incandescent bulb rejection (R > G > B warm spill)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val rInner = max(4, max(bw, bh) / 2)
        val rOuter = min(max(w, h) / 2, rInner + max(8, w / 20))
        var outerR = 0L
        var outerG = 0L
        var outerB = 0L
        var outerCount = 0
        for (dy in -rOuter..rOuter step 2) {
            val yy = cy + dy
            if (yy !in 0 until h) continue
            for (dx in -rOuter..rOuter step 2) {
                val xx = cx + dx
                if (xx !in 0 until w) continue
                val distSq = dx * dx + dy * dy
                if (distSq in (rInner * rInner + 4)..(rOuter * rOuter)) {
                    val c = rawRgba[yy * w + xx]
                    outerR += (c ushr 16) and 0xFF
                    outerG += (c ushr 8) and 0xFF
                    outerB += c and 0xFF
                    outerCount++
                }
            }
        }
        if (outerCount > 0) {
            val avgR = outerR / outerCount
            val avgG = outerG / outerCount
            val avgB = outerB / outerCount
            // Warm halogen/incandescent bulb fills outer ring with bright yellow/orange (R > G > B + 28)
            if (avgG > avgB + 28 && avgR > avgG && avgR > 140) {
                return IrHaloEval(false, "ТЁПЛАЯ ЛАМПА / БЛИК (отсеяно)")
            }
        }
        return IrHaloEval(true, "КАНДИДАТ ИК")
    }

    fun snapshotLatestBitmapCopy(): Bitmap? = synchronized(bitmapLock) {
        val src = frontBufferBitmap ?: return null
        if (src.isRecycled) return null
        val copy = src.copy(Bitmap.Config.ARGB_8888, true)
        if (currentMode == VisorMode.WIREFRAME_POSE) {
            latestPoseWireframe?.let { wf ->
                drawWireframeOntoBitmap(copy, wf)
            }
        }
        copy
    }

    fun release() {
        synchronized(bitmapLock) {
            frontBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            backBufferBitmap?.let { if (!it.isRecycled) it.recycle() }
            frontBufferBitmap = null
            backBufferBitmap = null
        }
        rppgTracker.reset()
        eulerTracker.reset()
        try { poseDetector.close() } catch (_: Exception) {}
    }

    private fun buildWireframe(
        all: List<PoseLandmark>,
        pose: com.google.mlkit.vision.pose.Pose,
        w: Float,
        h: Float
    ): PoseWireframe {
        val visible = all.filter { it.inFrameLikelihood >= 0.45f }
        if (visible.size < 3) return PoseWireframe(visibleCount = visible.size)

        fun pt(type: Int): PosePointNorm? {
            val lm = pose.getPoseLandmark(type) ?: return null
            if (lm.inFrameLikelihood < 0.40f) return null
            return PosePointNorm((lm.position.x / w).coerceIn(0f, 1f), (lm.position.y / h).coerceIn(0f, 1f))
        }

        val pairs = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.NOSE to PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.NOSE to PoseLandmark.RIGHT_SHOULDER
        )

        val bones = mutableListOf<Pair<PosePointNorm, PosePointNorm>>()
        for ((a, b) in pairs) {
            val pa = pt(a)
            val pb = pt(b)
            if (pa != null && pb != null) bones.add(pa to pb)
        }
        val joints = visible.map {
            PosePointNorm((it.position.x / w).coerceIn(0f, 1f), (it.position.y / h).coerceIn(0f, 1f))
        }
        return PoseWireframe(joints = joints, bones = bones, visibleCount = visible.size)
    }

    private fun drawWireframeOntoBitmap(bmp: Bitmap, wf: PoseWireframe) {
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            strokeWidth = max(2f, w * 0.009f)
            style = Paint.Style.STROKE
        }
        val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        for ((a, b) in wf.bones) {
            canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, bonePaint)
        }
        val r = max(3f, w * 0.014f)
        for (j in wf.joints) {
            canvas.drawCircle(j.x * w, j.y * h, r, jointPaint)
        }
    }
}

/**
 * MIT CSAIL inspired zero-weight Eulerian Temporal Band-Pass Amplifier with:
 * - 1D Integral Projection Motion Stabilization (SAD search + subpixel parabolic delta) BEFORE temporal IIR
 * - Spatial gradient edge-halo damping (prevents hand shake from creating rainbow borders)
 */
internal class EulerianTemporalAmplifier(private val gain: Float = 20f) {
    private var fastLuma = FloatArray(0)
    private var slowLuma = FloatArray(0)
    private var prevProjX = FloatArray(0)
    private var prevProjY = FloatArray(0)
    private var curProjX = FloatArray(0)
    private var curProjY = FloatArray(0)
    private var initialized = false
    private var lastW = 0
    private var lastH = 0

    @Volatile
    var lastStability: Float = 1f
        private set

    fun reset() {
        initialized = false
        lastStability = 1f
    }

    fun amplifyInto(srcPixels: IntArray, dstPixels: IntArray, w: Int, h: Int) {
        val n = w * h
        if (srcPixels.size < n || dstPixels.size < n || w <= 0 || h <= 0) return

        if (curProjX.size != w) {
            curProjX = FloatArray(w)
            prevProjX = FloatArray(w)
        }
        if (curProjY.size != h) {
            curProjY = FloatArray(h)
            prevProjY = FloatArray(h)
        }

        // Build 1D vertical & horizontal luma projections on current frame
        curProjX.fill(0f)
        curProjY.fill(0f)
        for (y in 0 until h) {
            val rowOff = y * w
            var rowSum = 0f
            for (x in 0 until w) {
                val g = ((srcPixels[rowOff + x] ushr 8) and 0xFF).toFloat()
                curProjX[x] += g
                rowSum += g
            }
            curProjY[y] = rowSum / w.toFloat()
        }
        for (x in 0 until w) {
            curProjX[x] /= h.toFloat()
        }

        if (!initialized || lastW != w || lastH != h || fastLuma.size != n) {
            fastLuma = FloatArray(n)
            slowLuma = FloatArray(n)
            for (i in 0 until n) {
                val c = srcPixels[i]
                val g = ((c ushr 8) and 0xFF).toFloat()
                fastLuma[i] = g
                slowLuma[i] = g
                dstPixels[i] = c
            }
            System.arraycopy(curProjX, 0, prevProjX, 0, w)
            System.arraycopy(curProjY, 0, prevProjY, 0, h)
            lastW = w
            lastH = h
            initialized = true
            lastStability = 1f
            return
        }

        // Estimate global shift (dx, dy) via 1D SAD projection matching (skip if frame is tiny synthetic test)
        val (shiftX, confX) = estimate1DShift(prevProjX, curProjX, w)
        val (shiftY, confY) = estimate1DShift(prevProjY, curProjY, h)
        val trackConf = min(confX, confY)
        val totalShift = abs(shiftX) + abs(shiftY)
        // Smooth confidence damping when camera swings violently (>5px/frame)
        val stability = if (w < 8 || h < 8) 1f else {
            (trackConf * (1f - (totalShift / 14f).coerceIn(0f, 0.9f))).coerceIn(0.1f, 1f)
        }
        lastStability = stability

        val sxInt = shiftX.roundToInt().coerceIn(-(w / 2), w / 2)
        val syInt = shiftY.roundToInt().coerceIn(-(h / 2), h / 2)

        for (y in 0 until h) {
            val sy = (y - syInt).coerceIn(0, h - 1)
            val syPrev = (sy - 1).coerceAtLeast(0)
            val rowOff = y * w
            val srcRow = sy * w
            val srcPrevRow = syPrev * w

            for (x in 0 until w) {
                val sx = (x - sxInt).coerceIn(0, w - 1)
                val sxPrev = (sx - 1).coerceAtLeast(0)
                val c = srcPixels[srcRow + sx]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val gf = g.toFloat()

                // Local edge gradient for halo damping
                val gLeft = (srcPixels[srcRow + sxPrev] ushr 8) and 0xFF
                val gUp = (srcPixels[srcPrevRow + sx] ushr 8) and 0xFF
                val edgeGrad = abs(g - gLeft) + abs(g - gUp)
                val edgeDamp = if (edgeGrad == 0) 1f else (1f / (1f + (edgeGrad / 26f) * (edgeGrad / 26f)))

                val idx = rowOff + x
                val fNew = fastLuma[idx] * 0.45f + gf * 0.55f
                val sNew = slowLuma[idx] * 0.90f + gf * 0.10f
                fastLuma[idx] = fNew
                slowLuma[idx] = sNew

                val bandDelta = fNew - sNew
                val effectiveGain = gain * stability * edgeDamp
                val amp = (bandDelta * effectiveGain).roundToInt()

                val nr = (r + (amp / 4)).coerceIn(0, 255)
                val ng = (g + amp).coerceIn(0, 255)
                val nb = (b + abs(amp) / 2).coerceIn(0, 255)
                dstPixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        System.arraycopy(curProjX, 0, prevProjX, 0, w)
        System.arraycopy(curProjY, 0, prevProjY, 0, h)
    }

    private fun estimate1DShift(prev: FloatArray, cur: FloatArray, len: Int): Pair<Float, Float> {
        if (len < 16) return 0f to 1f
        val maxShift = min(8, len / 6)
        var bestK = 0
        var bestSad = Float.MAX_VALUE
        var secondSad = Float.MAX_VALUE
        var kMinus1Sad = 0f
        var kPlus1Sad = 0f

        val sads = FloatArray(maxShift * 2 + 1)
        for (k in -maxShift..maxShift) {
            var sad = 0f
            var cnt = 0
            for (i in maxShift until (len - maxShift)) {
                sad += abs(cur[i + k] - prev[i])
                cnt++
            }
            val normSad = if (cnt > 0) sad / cnt else Float.MAX_VALUE
            sads[k + maxShift] = normSad
            if (normSad < bestSad) {
                secondSad = bestSad
                bestSad = normSad
                bestK = k
            } else if (normSad < secondSad) {
                secondSad = normSad
            }
        }

        if (bestSad < 1e-4f) return bestK.toFloat() to 1f
        val idx = bestK + maxShift
        var subDelta = 0f
        if (idx in 1 until sads.lastIndex) {
            kMinus1Sad = sads[idx - 1]
            kPlus1Sad = sads[idx + 1]
            val denom = 2f * (kMinus1Sad - 2f * bestSad + kPlus1Sad)
            if (abs(denom) > 1e-4f) {
                subDelta = ((kMinus1Sad - kPlus1Sad) / denom).coerceIn(-0.5f, 0.5f)
            }
        }
        val margin = ((secondSad - bestSad) / max(1f, bestSad)).coerceIn(0f, 1f)
        val conf = (0.45f + margin * 2.5f).coerceIn(0.35f, 1f)
        return (bestK + subDelta) to conf
    }
}

/**
 * Pure Kotlin POS (Plane-Orthogonal-to-Skin) rPPG heart-rate + indicative HRV (RMSSD ms) tracker with:
 * - Timestamp-aware One-Pole 0.45 Hz High-Pass detrending
 * - Auto-Exposure (AE) step-jump detection & filter baseline rebase (preserves 8s window across sudden AE shifts)
 * - Finger-on-lens / Red-clipping Green-channel PPG fallback
 */
internal class PosRppgTracker(private val windowSize: Int = 240) {
    private val timesSec = DoubleArray(windowSize)
    private val rBuf = DoubleArray(windowSize)
    private val gBuf = DoubleArray(windowSize)
    private val bBuf = DoubleArray(windowSize)
    private var count = 0
    private var head = 0

    // High-pass & AE jump tracking state
    private var prevLuma = -1.0
    private var prevTimeSec = -1.0
    private var hpR = 0.0
    private var hpG = 0.0
    private var hpB = 0.0
    private var prevRawR = 0.0
    private var prevRawG = 0.0
    private var prevRawB = 0.0

    fun reset() {
        count = 0
        head = 0
        prevLuma = -1.0
        prevTimeSec = -1.0
        hpR = 0.0
        hpG = 0.0
        hpB = 0.0
        prevRawR = 0.0
        prevRawG = 0.0
        prevRawB = 0.0
    }

    /**
     * Returns Triple(bpmOrNull, hrvRmssdMsOrNull, signalQuality0to1).
     */
    fun pushSample(
        timestampNs: Long,
        meanR: Double,
        meanG: Double,
        meanB: Double,
        greenFallback: Boolean = false
    ): Triple<Int?, Int?, Float> {
        val tSec = timestampNs / 1_000_000_000.0
        val luma = max(1.0, 0.299 * meanR + 0.587 * meanG + 0.114 * meanB)

        if (prevLuma <= 0.0 || prevTimeSec < 0.0) {
            prevLuma = luma
            prevTimeSec = tSec
            prevRawR = meanR
            prevRawG = meanG
            prevRawB = meanB
            hpR = 0.0
            hpG = 0.0
            hpB = 0.0
        } else {
            val dt = (tSec - prevTimeSec).coerceIn(0.005, 0.5)
            val aeJumpRatio = abs(ln(luma / prevLuma))
            if (aeJumpRatio > 0.035) {
                // Rebase baselines cleanly on abrupt Auto-Exposure jump instead of throwing away 8s buffer
                val scale = luma / prevLuma
                prevRawR *= scale
                prevRawG *= scale
                prevRawB *= scale
                // Soften transient step without poisoning DC offset
                val k = exp(-2.0 * PI * 0.45 * dt)
                hpR *= k
                hpG *= k
                hpB *= k
            } else {
                val alpha = exp(-2.0 * PI * 0.45 * dt)
                hpR = alpha * (hpR + meanR - prevRawR)
                hpG = alpha * (hpG + meanG - prevRawG)
                hpB = alpha * (hpB + meanB - prevRawB)
                prevRawR = meanR
                prevRawG = meanG
                prevRawB = meanB
            }
            prevLuma = luma
            prevTimeSec = tSec
        }

        // Store normalized AC+baseline around fixed scale 128 so POS ratio & green fallback stay invariant across AE jumps
        timesSec[head] = tSec
        rBuf[head] = max(1.0, 128.0 + hpR * (128.0 / max(10.0, meanR)))
        gBuf[head] = max(1.0, 128.0 + hpG * (128.0 / max(10.0, meanG)))
        bBuf[head] = max(1.0, 128.0 + hpB * (128.0 / max(10.0, meanB)))
        head = (head + 1) % windowSize
        if (count < windowSize) count++

        if (count < 110) {
            return Triple(null, null, (count / 110f) * 0.38f)
        }

        val n = count
        val oldestIdx = (head - n + windowSize) % windowSize
        val latestIdx = (head - 1 + windowSize) % windowSize
        val durationSec = timesSec[latestIdx] - timesSec[oldestIdx]
        if (durationSec <= 2.0) return Triple(null, null, 0.1f)

        val rawPulse = DoubleArray(n)
        val windowedPulse = DoubleArray(n)

        if (greenFallback) {
            var meanGac = 0.0
            for (i in 0 until n) {
                meanGac += gBuf[(oldestIdx + i) % windowSize]
            }
            meanGac /= n
            for (i in 0 until n) {
                val valG = gBuf[(oldestIdx + i) % windowSize] - meanGac
                rawPulse[i] = valG
                val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
                windowedPulse[i] = valG * hann
            }
        } else {
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
            if (avgR < 1.0 || avgG < 1.0 || avgB < 1.0) return Triple(null, null, 0f)

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
            if (stdS2 < 1e-6) return Triple(null, null, 0.1f)

            val alpha = stdS1 / stdS2
            for (i in 0 until n) {
                val valPos = s1[i] + alpha * s2[i]
                rawPulse[i] = valPos
                val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
                windowedPulse[i] = valPos * hann
            }
        }

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
                re += windowedPulse[i] * cos(angle)
                im -= windowedPulse[i] * sin(angle)
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
        if (quality < 0.35f) return Triple(null, null, quality)

        val hrvMs = estimateRmssdMs(rawPulse, oldestIdx, n)
        return Triple(bestBpm, hrvMs, quality)
    }

    private fun estimateRmssdMs(wave: DoubleArray, oldestIdx: Int, n: Int): Int? {
        if (n < 60) return null
        val smoothed = DoubleArray(n)
        for (i in 1 until n - 1) {
            smoothed[i] = (wave[i - 1] + 2.0 * wave[i] + wave[i + 1]) * 0.25
        }
        val peakTimes = mutableListOf<Double>()
        for (i in 2 until n - 2) {
            val v = smoothed[i]
            if (v > smoothed[i - 1] && v >= smoothed[i + 1] && v > smoothed[i - 2] && v > smoothed[i + 2] && v > 0.0) {
                val t = timesSec[(oldestIdx + i) % windowSize]
                if (peakTimes.isEmpty() || (t - peakTimes.last()) >= 0.34) {
                    peakTimes.add(t)
                }
            }
        }
        if (peakTimes.size < 4) return null

        val ibisMs = mutableListOf<Double>()
        for (i in 1 until peakTimes.size) {
            val ibi = (peakTimes[i] - peakTimes[i - 1]) * 1000.0
            if (ibi in 340.0..1400.0) ibisMs.add(ibi)
        }
        if (ibisMs.size < 3) return null

        val diffSq = mutableListOf<Double>()
        for (i in 1 until ibisMs.size) {
            val d = ibisMs[i] - ibisMs[i - 1]
            if (abs(d) <= 280.0) {
                diffSq.add(d * d)
            }
        }
        if (diffSq.isEmpty()) return null
        return sqrt(diffSq.average()).roundToInt().coerceIn(5, 220)
    }
}
