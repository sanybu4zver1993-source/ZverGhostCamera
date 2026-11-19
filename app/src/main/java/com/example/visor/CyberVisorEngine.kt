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
    NORMAL("Обычный", "КАМЕРА", "Обычный режим камеры без наворотов"),
    BIO_RPPG("Пульс (Лицо/Палец)", "ПУЛЬС", "Приложи палец к камере или держи лицо (6–8с): меряет удар в минуту и стресс без сети"),
    SKIN_CHROMA("Тепло-Кожа", "КОЖА", "Выделяет живого человека по цвету кожи и подсвечивает тех, кто шевелится"),
    EULER_AMP("Дрожь / Пульс", "ТРЯСКА", "Усиливает в 20 раз малейшие движения кожи, дыхание и пульс"),
    VEIN_HEMO("Веновизор", "ВЕНЫ", "Вытягивает контраст вен и кровеносных сосудов под кожей"),
    DERMA_UV_SIM("Рентген кожи", "РЕНТГЕН", "Проявляет пигмент, синяки и скрытые пятна"),
    THERMAL_LUT("Тепловизор", "ТЕПЛО", "Цветная карта тепла от синего к красному"),
    DEHAZE_LIVE("Анти-Туман", "АНТИ-ТУМАН", "Вырезает мрак, дымку и вытягивает силуэты в темноте"),
    WIREFRAME_POSE("Скелет", "СКЕЛЕТ", "Рисует палочный каркас человека в темноте или со спины"),
    VEG_CHLOROPHYLL("Детектор зелени", "ХЛОРОФИЛЛ", "Отличает живые растения и траву от зелёного пластика"),
    NIGHT_GAMMA("Ночной буст", "НОЧЬ", "Вытягивает картинку из кромешной тьмы"),
    IR_BEACON("Поиск ИК-камер", "ПОИСК ИК", "Ищет скрытые камеры и ночную подсветку по ярким точкам"),
    EDGE_TRACE("Дорожки ПП (CV)", "ТРАССЫ", "Выделяет контуры SMD-компонентов и медных дорожек (Sobel)"),
    OCR_CONTRAST("Маркировка OCR", "МАРКИРОВКА", "Усиливает контраст гравировки на чипах и мелкого текста")
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
    val poseWireframe: PoseWireframe? = null,
    val vegLivePercent: Int = 0,
    val vegSyntheticPercent: Int = 0,
    val edgeTraceDensityPercent: Int = 0
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
                if (value != VisorMode.WIREFRAME_POSE) {
                    latestPoseWireframe = null
                    poseSmoother.reset()
                }
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
    private var skinMotionRawBuffer: BooleanArray = BooleanArray(0)
    private var prevSkinLumaBuffer: ByteArray = ByteArray(0)
    private var curSkinLumaBuffer: ByteArray = ByteArray(0)
    private var vegClassBuffer: ByteArray = ByteArray(0)
    private var hasPrevSkinLuma = false
    private var skinSceneCbShift = 0
    private var skinSceneCrShift = 0

    private val rppgTracker = PosRppgTracker()
    private val eulerTracker = EulerianTemporalAmplifier()
    private val poseSmoother = PoseTemporalSmoother()
    private var irPersistFrames = 0
    private var lastPoseIsFront = false

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
        if ((mode == VisorMode.EULER_AMP || mode == VisorMode.WIREFRAME_POSE || mode == VisorMode.IR_BEACON || mode == VisorMode.EDGE_TRACE || mode == VisorMode.VEG_CHLOROPHYLL) && rawRgbaBuffer.size != totalDst) {
            rawRgbaBuffer = IntArray(totalDst)
        }
        if (mode == VisorMode.IR_BEACON && irMaskBuffer.size != totalDst) {
            irMaskBuffer = BooleanArray(totalDst)
        }
        if (mode == VisorMode.VEG_CHLOROPHYLL && vegClassBuffer.size != totalDst) {
            vegClassBuffer = ByteArray(totalDst)
        }
        if (mode == VisorMode.SKIN_CHROMA) {
            if (skinMaskBuffer.size != totalDst) {
                skinMaskBuffer = BooleanArray(totalDst)
                skinDenoisedBuffer = BooleanArray(totalDst)
                skinMotionRawBuffer = BooleanArray(totalDst)
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
                if (mode == VisorMode.EULER_AMP || mode == VisorMode.WIREFRAME_POSE || mode == VisorMode.IR_BEACON || mode == VisorMode.SKIN_CHROMA || mode == VisorMode.EDGE_TRACE || mode == VisorMode.VEG_CHLOROPHYLL) {
                    rawRgbaBuffer[dstIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                val isSkin = when (mode) {
                    VisorMode.BIO_RPPG -> YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(yVal, uRaw, vRaw)
                    VisorMode.SKIN_CHROMA -> {
                        sceneCbSum += uRaw
                        sceneCrSum += vRaw
                        curSkinLumaBuffer[dstIdx] = yVal.toByte()
                        val ycbcrMatch = YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(
                            yVal,
                            uRaw,
                            vRaw,
                            skinSceneCbShift,
                            skinSceneCrShift
                        )
                        // Combined YCbCr + RGB warmth guard prevents beige/cool walls & green-tinted surfaces
                        val matched = ycbcrMatch && isWarmHumanSkinRgbGuard(r, g, b)
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
                        vegClassBuffer[dstIdx] = classifyChlorophyllPixel(r, g, b)
                        0 // Finalized after 3x3 organic-texture & anti-plastic coherence pass
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

                    VisorMode.EDGE_TRACE -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    VisorMode.OCR_CONTRAST -> {
                        val lum = (r * 77 + g * 150 + b * 29) shr 8
                        val stretched = (((lum - 128) * 24) / 10 + 128).coerceIn(0, 255)
                        val boostR = min(255, (stretched * 95) / 100)
                        val boostG = stretched
                        val boostB = min(255, stretched + 12)
                        (0xFF shl 24) or (boostR shl 16) or (boostG shl 8) or boostB
                    }
                }
            }
        }

        var edgeTracePixels = 0
        if (mode == VisorMode.EDGE_TRACE && dstW >= 4 && dstH >= 4) {
            edgeTracePixels = renderSobelPcbTracePass(rawRgbaBuffer, pixelBuffer, dstW, dstH)
        }

        var vegLivePixels = 0
        var vegSyntheticPixels = 0
        if (mode == VisorMode.VEG_CHLOROPHYLL && dstW >= 4 && dstH >= 4) {
            val counts = renderChlorophyllCoherencePass(rawRgbaBuffer, vegClassBuffer, pixelBuffer, dstW, dstH)
            vegLivePixels = counts.first
            vegSyntheticPixels = counts.second
        }

        if (mode == VisorMode.EULER_AMP) {
            eulerTracker.amplifyInto(rawRgbaBuffer, pixelBuffer, dstW, dstH)
        }

        // 3x3 Morphological despeckle + global-shift compensated moving-skin intersection for SKIN_CHROMA
        var skinTotalCount = 0
        var movingSkinCount = 0
        var skinMinX = dstW
        var skinMaxX = -1
        var skinMinY = dstH
        var skinMaxY = -1
        if (mode == VisorMode.SKIN_CHROMA && dstW >= 4 && dstH >= 4) {
            // Estimate median-like global luma drift so handheld shake doesn't trigger whole-face false motion
            var globalShiftSum = 0L
            var globalShiftSamples = 0
            if (hasPrevSkinLuma) {
                for (sy in 0 until dstH step 6) {
                    val off = sy * dstW
                    for (sx in 0 until dstW step 6) {
                        val idx = off + sx
                        val cY = curSkinLumaBuffer[idx].toInt() and 0xFF
                        val pY = prevSkinLumaBuffer[idx].toInt() and 0xFF
                        val d = cY - pY
                        if (abs(d) <= 20) {
                            globalShiftSum += d
                            globalShiftSamples++
                        }
                    }
                }
            }
            val globalLumaShift = if (globalShiftSamples > 12) (globalShiftSum / globalShiftSamples).toInt() else 0

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

                    val curY = curSkinLumaBuffer[idx].toInt() and 0xFF
                    val prevY = prevSkinLumaBuffer[idx].toInt() and 0xFF
                    val compensatedDelta = abs((curY - prevY) - globalLumaShift)
                    skinMotionRawBuffer[idx] = clean && hasPrevSkinLuma && compensatedDelta >= 13
                }
            }

            for (y in 0 until dstH) {
                val rowOff = y * dstW
                val upOff = max(0, y - 1) * dstW
                val dnOff = min(dstH - 1, y + 1) * dstW
                for (x in 0 until dstW) {
                    val idx = rowOff + x
                    val clean = skinDenoisedBuffer[idx]
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

                        val xm = max(0, x - 1)
                        val xp = min(dstW - 1, x + 1)
                        var motionNeighbors = 0
                        if (skinMotionRawBuffer[rowOff + xm]) motionNeighbors++
                        if (skinMotionRawBuffer[rowOff + xp]) motionNeighbors++
                        if (skinMotionRawBuffer[upOff + x]) motionNeighbors++
                        if (skinMotionRawBuffer[dnOff + x]) motionNeighbors++

                        val curY = curSkinLumaBuffer[idx].toInt() and 0xFF
                        val prevY = prevSkinLumaBuffer[idx].toInt() and 0xFF
                        val strongDelta = abs((curY - prevY) - globalLumaShift) >= 22
                        val isMoving = skinMotionRawBuffer[idx] && (motionNeighbors >= 1 || strongDelta)

                        if (isMoving) {
                            movingSkinCount++
                            // Moving skin highlight: vivid electric cyan-mint outline glow
                            val sr = min(255, r / 4 + 36)
                            val sg = 255
                            val sb = min(255, b / 3 + 175)
                            pixelBuffer[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                        } else {
                            // Static coherent skin: warm bio-thermal amber/orange tone mapped by luma
                            val luma = (r * 77 + g * 150 + b * 29) shr 8
                            val sr = min(255, 130 + (luma * 125) / 255)
                            val sg = min(255, 65 + (luma * 115) / 255)
                            val sb = min(100, 18 + (b / 4))
                            pixelBuffer[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                        }
                    } else {
                        // Cool deep navy background so thermal skin silhouettes pop clearly
                        val dim = ((r * 77 + g * 150 + b * 29) shr 8) / 3
                        pixelBuffer[idx] = (0xFF shl 24) or ((dim * 0.50f).toInt() shl 16) or
                            ((dim * 0.72f).toInt() shl 8) or min(255, dim + 18)
                    }
                }
            }
            System.arraycopy(curSkinLumaBuffer, 0, prevSkinLumaBuffer, 0, totalDst)
            hasPrevSkinLuma = true

            // Update gentle adaptive scene white-balance offset only when skin isn't dominating frame
            val skinFrac = skinTotalCount.toFloat() / max(1, totalDst).toFloat()
            if (skinFrac < 0.25f && totalDst > 0) {
                val meanCb = (sceneCbSum / totalDst).toInt()
                val meanCr = (sceneCrSum / totalDst).toInt()
                skinSceneCbShift = ((meanCb - 128) / 7).coerceIn(-3, 3)
                skinSceneCrShift = ((meanCr - 128) / 7).coerceIn(-3, 3)
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
            if (lastPoseIsFront != isFrontCamera) {
                lastPoseIsFront = isFrontCamera
                poseSmoother.reset()
                latestPoseWireframe = null
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPoseInferMs >= 110L && poseBusy.compareAndSet(false, true)) {
                lastPoseInferMs = nowMs
                val poseBmp = Bitmap.createBitmap(rawRgbaBuffer, dstW, dstH, Bitmap.Config.ARGB_8888)
                val input = InputImage.fromBitmap(poseBmp, 0)
                poseDetector.process(input)
                    .addOnSuccessListener { pose ->
                        latestPoseWireframe = buildSmoothedWireframe(pose, dstW.toFloat(), dstH.toFloat())
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
            VisorMode.VEG_CHLOROPHYLL -> VisorTelemetry(
                mode = mode,
                vegLivePercent = ((vegLivePixels * 100f) / max(1, totalDst)).roundToInt().coerceIn(0, 100),
                vegSyntheticPercent = ((vegSyntheticPixels * 100f) / max(1, totalDst)).roundToInt().coerceIn(0, 100)
            )
            VisorMode.EDGE_TRACE -> VisorTelemetry(
                mode = mode,
                edgeTraceDensityPercent = ((edgeTracePixels * 100f) / max(1, totalDst)).roundToInt().coerceIn(0, 100)
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

    /**
     * Lightweight telemetry-only pass when [GlVisorDualRenderer] handles screen & video rendering
     * on the GPU. Produces zero display Bitmaps while keeping live badges, IR halo validation,
     * and ML Kit Pose landmarks updated.
     */
    fun analyzeTelemetryOnly(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ): VisorTelemetry? {
        val mode = currentMode
        if (mode == VisorMode.NORMAL) return null

        when (mode) {
            VisorMode.THERMAL_LUT,
            VisorMode.NIGHT_GAMMA,
            VisorMode.VEIN_HEMO,
            VisorMode.DERMA_UV_SIM,
            VisorMode.OCR_CONTRAST,
            VisorMode.DEHAZE_LIVE -> return VisorTelemetry(mode = mode)
            else -> {}
        }

        val planes = imageProxy.planes
        if (planes.size < 3) return VisorTelemetry(mode = mode)
        val srcW = imageProxy.width
        val srcH = imageProxy.height
        if (srcW < 16 || srcH < 16) return VisorTelemetry(mode = mode)

        if (mode == VisorMode.WIREFRAME_POSE) {
            if (lastPoseIsFront != isFrontCamera) {
                lastPoseIsFront = isFrontCamera
                poseSmoother.reset()
                latestPoseWireframe = null
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPoseInferMs >= 110L && poseBusy.compareAndSet(false, true)) {
                lastPoseInferMs = nowMs
                val step = max(2, max(srcW, srcH) / 240)
                val subW = (srcW / step).coerceAtLeast(16)
                val subH = (srcH / step).coerceAtLeast(16)
                val rot = ((rotationDegrees % 360) + 360) % 360
                val swapDims = (rot == 90 || rot == 270)
                val dstW = if (swapDims) subH else subW
                val dstH = if (swapDims) subW else subH
                val totalDst = dstW * dstH
                if (rawRgbaBuffer.size != totalDst) rawRgbaBuffer = IntArray(totalDst)

                val yBuf = planes[0].buffer
                val uBuf = planes[1].buffer
                val vBuf = planes[2].buffer
                val yRS = planes[0].rowStride
                val yPS = planes[0].pixelStride
                val uRS = planes[1].rowStride
                val uPS = planes[1].pixelStride
                val vRS = planes[2].rowStride
                val vPS = planes[2].pixelStride
                val yLim = yBuf.limit()
                val uLim = uBuf.limit()
                val vLim = vBuf.limit()

                for (sy in 0 until subH) {
                    val oy = sy * step
                    val yRO = oy * yRS
                    val uvY = oy shr 1
                    val uRO = uvY * uRS
                    val vRO = uvY * vRS
                    for (sx in 0 until subW) {
                        val ox = sx * step
                        val yI = yRO + ox * yPS
                        val uvX = ox shr 1
                        val uI = uRO + uvX * uPS
                        val vI = vRO + uvX * vPS
                        val yVal = if (yI in 0 until yLim) (yBuf.get(yI).toInt() and 0xFF) else 128
                        val uVal = (if (uI in 0 until uLim) (uBuf.get(uI).toInt() and 0xFF) else 128) - 128
                        val vVal = (if (vI in 0 until vLim) (vBuf.get(vI).toInt() and 0xFF) else 128) - 128
                        val r = (yVal + ((359 * vVal) shr 8)).coerceIn(0, 255)
                        val g = (yVal - ((88 * uVal + 183 * vVal) shr 8)).coerceIn(0, 255)
                        val b = (yVal + ((454 * uVal) shr 8)).coerceIn(0, 255)
                        var dx: Int
                        var dy: Int
                        when (rot) {
                            90 -> { dx = subH - 1 - sy; dy = sx }
                            180 -> { dx = subW - 1 - sx; dy = subH - 1 - sy }
                            270 -> { dx = sy; dy = subW - 1 - sx }
                            else -> { dx = sx; dy = sy }
                        }
                        if (isFrontCamera) dx = dstW - 1 - dx
                        rawRgbaBuffer[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                val poseBmp = Bitmap.createBitmap(rawRgbaBuffer, dstW, dstH, Bitmap.Config.ARGB_8888)
                val input = InputImage.fromBitmap(poseBmp, 0)
                poseDetector.process(input)
                    .addOnSuccessListener { pose ->
                        latestPoseWireframe = buildSmoothedWireframe(pose, dstW.toFloat(), dstH.toFloat())
                    }
                    .addOnCompleteListener {
                        if (!poseBmp.isRecycled) poseBmp.recycle()
                        poseBusy.set(false)
                    }
            }
            return VisorTelemetry(mode = mode, poseWireframe = latestPoseWireframe)
        }

        // Coarse (~160x120) fast metrics pass for IR_BEACON / VEG_CHLOROPHYLL / SKIN_CHROMA / EDGE_TRACE
        val step = max(4, max(srcW, srcH) / 160)
        val subW = (srcW / step).coerceAtLeast(8)
        val subH = (srcH / step).coerceAtLeast(8)
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapDims = (rot == 90 || rot == 270)
        val dstW = if (swapDims) subH else subW
        val dstH = if (swapDims) subW else subH
        val total = dstW * dstH

        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRS = planes[0].rowStride
        val yPS = planes[0].pixelStride
        val uRS = planes[1].rowStride
        val uPS = planes[1].pixelStride
        val vRS = planes[2].rowStride
        val vPS = planes[2].pixelStride
        val yLim = yBuf.limit()
        val uLim = uBuf.limit()
        val vLim = vBuf.limit()

        if (mode == VisorMode.IR_BEACON && rawRgbaBuffer.size != total) {
            rawRgbaBuffer = IntArray(total)
        }

        var skinCnt = 0
        var vegLive = 0
        var vegSynth = 0
        var irCands = 0
        var irMinX = dstW
        var irMaxX = -1
        var irMinY = dstH
        var irMaxY = -1
        var edgeHits = 0
        var prevRowLuma = 0

        for (sy in 0 until subH) {
            val oy = sy * step
            val yRO = oy * yRS
            val uvY = oy shr 1
            val uRO = uvY * uRS
            val vRO = uvY * vRS
            for (sx in 0 until subW) {
                val ox = sx * step
                val yI = yRO + ox * yPS
                val uvX = ox shr 1
                val uI = uRO + uvX * uPS
                val vI = vRO + uvX * vPS
                val yVal = if (yI in 0 until yLim) (yBuf.get(yI).toInt() and 0xFF) else 128
                val uRaw = if (uI in 0 until uLim) (uBuf.get(uI).toInt() and 0xFF) else 128
                val vRaw = if (vI in 0 until vLim) (vBuf.get(vI).toInt() and 0xFF) else 128
                val uVal = uRaw - 128
                val vVal = vRaw - 128
                val r = (yVal + ((359 * vVal) shr 8)).coerceIn(0, 255)
                val g = (yVal - ((88 * uVal + 183 * vVal) shr 8)).coerceIn(0, 255)
                val b = (yVal + ((454 * uVal) shr 8)).coerceIn(0, 255)

                when (mode) {
                    VisorMode.SKIN_CHROMA -> {
                        if (YcbcrSkinAnalyzer.isHumanSkinYCbCrFast(yVal, uRaw, vRaw) && isWarmHumanSkinRgbGuard(r, g, b)) {
                            skinCnt++
                        }
                    }
                    VisorMode.VEG_CHLOROPHYLL -> {
                        when (classifyChlorophyllPixel(r, g, b).toInt()) {
                            1 -> vegLive++
                            2 -> vegSynth++
                        }
                    }
                    VisorMode.IR_BEACON -> {
                        var dx: Int
                        var dy: Int
                        when (rot) {
                            90 -> { dx = subH - 1 - sy; dy = sx }
                            180 -> { dx = subW - 1 - sx; dy = subH - 1 - sy }
                            270 -> { dx = sy; dy = subW - 1 - sx }
                            else -> { dx = sx; dy = sy }
                        }
                        if (isFrontCamera) dx = dstW - 1 - dx
                        rawRgbaBuffer[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        val irScore = (r + b) - (g * 2)
                        if (irScore > 55 && ((r > 170 && b > 145) || (r > 246 && g > 246 && b > 246))) {
                            irCands++
                            if (dx < irMinX) irMinX = dx
                            if (dx > irMaxX) irMaxX = dx
                            if (dy < irMinY) irMinY = dy
                            if (dy > irMaxY) irMaxY = dy
                        }
                    }
                    VisorMode.EDGE_TRACE -> {
                        if (sx > 0 && abs(yVal - prevRowLuma) > 22) edgeHits++
                        prevRowLuma = yVal
                    }
                    else -> {}
                }
            }
        }

        return when (mode) {
            VisorMode.SKIN_CHROMA -> VisorTelemetry(
                mode = mode,
                skinPercent = ((skinCnt * 100f) / max(1, total)).roundToInt().coerceIn(0, 100),
                skinCompactness = if (skinCnt > 8) 0.5f else 0f
            )
            VisorMode.VEG_CHLOROPHYLL -> VisorTelemetry(
                mode = mode,
                vegLivePercent = ((vegLive * 100f) / max(1, total)).roundToInt().coerceIn(0, 100),
                vegSyntheticPercent = ((vegSynth * 100f) / max(1, total)).roundToInt().coerceIn(0, 100)
            )
            VisorMode.EDGE_TRACE -> VisorTelemetry(
                mode = mode,
                edgeTraceDensityPercent = ((edgeHits * 100f) / max(1, total)).roundToInt().coerceIn(0, 100)
            )
            VisorMode.IR_BEACON -> {
                val irEval = evaluateIrHaloCandidate(rawRgbaBuffer, dstW, dstH, irCands, irMinX, irMaxX, irMinY, irMaxY)
                val confirmed = if (irEval.isCompactCandidate) {
                    irPersistFrames = min(60, irPersistFrames + 1)
                    irPersistFrames >= 6
                } else {
                    irPersistFrames = max(0, irPersistFrames - 2)
                    false
                }
                val label = when {
                    confirmed -> "УСТОЙЧИВЫЙ ТОЧЕЧНЫЙ ОРЕОЛ ($irCands пикс)"
                    irEval.isCompactCandidate -> "КАНДИДАТ ИК ($irPersistFrames/6 кадров)"
                    else -> irEval.rejectionLabel
                }
                VisorTelemetry(mode = mode, irPeakDetected = confirmed, irHotPixels = irCands, irStatusLabel = label)
            }
            else -> VisorTelemetry(mode = mode)
        }
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

    /**
     * Renders [mode] deterministically onto a full/high-res upright ARGB_8888 [src] Bitmap
     * so saved `.gcf` photos preserve the active Bio-Visor optical/CV filter.
     */
    fun applyVisorEffectToBitmap(
        src: Bitmap,
        mode: VisorMode,
        poseOverlay: PoseWireframe? = latestPoseWireframe
    ): Bitmap {
        if (mode == VisorMode.NORMAL || src.isRecycled) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        val w = src.width
        val h = src.height
        val total = w * h
        val pixels = IntArray(total)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(total)

        var airlight = 0
        var lumaMax = 255
        if (mode == VisorMode.DEHAZE_LIVE) {
            var minSum = 0L
            var minDark = 255
            var maxL = 1
            var samples = 0
            for (i in 0 until total step 16) {
                val c = pixels[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val yVal = (r * 77 + g * 150 + b * 29) shr 8
                val dark = min(r, min(g, b))
                minSum += dark
                if (dark < minDark) minDark = dark
                if (yVal > maxL) maxL = yVal
                samples++
            }
            val avgDark = if (samples > 0) (minSum / samples).toInt() else 0
            airlight = ((minDark * 6 + avgDark * 4) / 10).coerceIn(0, 140)
            lumaMax = max(64, maxL)
        }

        when (mode) {
            VisorMode.EDGE_TRACE -> {
                if (w >= 4 && h >= 4) {
                    renderSobelPcbTracePass(pixels, out, w, h)
                } else {
                    System.arraycopy(pixels, 0, out, 0, total)
                }
            }
            VisorMode.VEG_CHLOROPHYLL -> {
                val classes = ByteArray(total)
                for (i in 0 until total) {
                    val c = pixels[i]
                    classes[i] = classifyChlorophyllPixel((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF)
                }
                if (w >= 4 && h >= 4) {
                    renderChlorophyllCoherencePass(pixels, classes, out, w, h)
                } else {
                    System.arraycopy(pixels, 0, out, 0, total)
                }
            }
            VisorMode.SKIN_CHROMA -> {
                val rawMask = BooleanArray(total)
                for (i in 0 until total) {
                    val c = pixels[i]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    rawMask[i] = YcbcrSkinAnalyzer.isHumanSkinRgb(r, g, b) && isWarmHumanSkinRgbGuard(r, g, b)
                }
                for (y in 0 until h) {
                    val rowOff = y * w
                    val upOff = max(0, y - 1) * w
                    val dnOff = min(h - 1, y + 1) * w
                    for (x in 0 until w) {
                        val idx = rowOff + x
                        val cur = rawMask[idx]
                        var nCount = 0
                        if (rawMask[rowOff + max(0, x - 1)]) nCount++
                        if (rawMask[rowOff + min(w - 1, x + 1)]) nCount++
                        if (rawMask[upOff + x]) nCount++
                        if (rawMask[dnOff + x]) nCount++
                        val clean = if (cur) nCount >= 2 else nCount >= 3
                        val c = pixels[idx]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val luma = (r * 77 + g * 150 + b * 29) shr 8
                        out[idx] = if (clean) {
                            val sr = min(255, 130 + (luma * 125) / 255)
                            val sg = min(255, 65 + (luma * 115) / 255)
                            val sb = min(100, 18 + (b / 4))
                            (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                        } else {
                            val dim = luma / 3
                            (0xFF shl 24) or ((dim * 0.50f).toInt() shl 16) or
                                ((dim * 0.72f).toInt() shl 8) or min(255, dim + 18)
                        }
                    }
                }
            }
            else -> {
                val roiX0 = (w * 0.34f).toInt()
                val roiX1 = (w * 0.66f).toInt()
                val roiY0 = (h * 0.28f).toInt()
                val roiY1 = (h * 0.70f).toInt()
                for (y in 0 until h) {
                    val rowOff = y * w
                    val inRoiY = (y in roiY0..roiY1)
                    for (x in 0 until w) {
                        val idx = rowOff + x
                        val c = pixels[idx]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        out[idx] = when (mode) {
                            VisorMode.VEIN_HEMO -> {
                                val rf = max(r, 4).toDouble()
                                val gf = max(g, 4).toDouble()
                                val hemoIndex = (ln(rf) - ln(gf)) * 210.0
                                val baseLuma = (r * 77 + g * 150 + b * 29) shr 8
                                val veinBoost = (baseLuma - hemoIndex * 2.2).roundToInt().coerceIn(0, 255)
                                (0xFF shl 24) or ((veinBoost * 0.25f).toInt() shl 16) or
                                    (veinBoost shl 8) or min(255, (veinBoost * 0.90f + 20).toInt())
                            }
                            VisorMode.DERMA_UV_SIM -> {
                                val uvContrast = ((b * 1.35f) - (r * 0.35f)).roundToInt().coerceIn(0, 255)
                                (0xFF shl 24) or ((uvContrast * 0.45f).toInt() shl 16) or
                                    ((uvContrast * 0.65f).toInt() shl 8) or min(255, (uvContrast * 1.25f + 15).toInt())
                            }
                            VisorMode.THERMAL_LUT -> {
                                val luma = (r * 77 + g * 150 + b * 29) shr 8
                                ironbowLut[luma]
                            }
                            VisorMode.DEHAZE_LIVE -> {
                                val span = max(48, lumaMax - airlight)
                                val dr = (((r - airlight) * 255) / span).coerceIn(0, 255)
                                val dg = (((g - airlight) * 255) / span).coerceIn(0, 255)
                                val db = (((b - airlight) * 255) / span).coerceIn(0, 255)
                                (0xFF shl 24) or (dr shl 16) or (dg shl 8) or db
                            }
                            VisorMode.WIREFRAME_POSE -> {
                                (0xFF shl 24) or (((r * 45) / 100) shl 16) or (((g * 65) / 100) shl 8) or ((b * 85) / 100)
                            }
                            VisorMode.NIGHT_GAMMA -> {
                                (0xFF shl 24) or (nightLut[r] shl 16) or (nightLut[g] shl 8) or nightLut[b]
                            }
                            VisorMode.IR_BEACON -> {
                                val irScore = (r + b) - (g * 2)
                                if (irScore > 55 && ((r > 170 && b > 145) || (r > 246 && g > 246 && b > 246))) {
                                    0xFFFF0055.toInt()
                                } else {
                                    val dim = (r + g + b) / 9
                                    (0xFF shl 24) or (dim shl 16) or (dim shl 8) or dim
                                }
                            }
                            VisorMode.OCR_CONTRAST -> {
                                val lum = (r * 77 + g * 150 + b * 29) shr 8
                                val stretched = (((lum - 128) * 24) / 10 + 128).coerceIn(0, 255)
                                (0xFF shl 24) or (min(255, (stretched * 95) / 100) shl 16) or
                                    (stretched shl 8) or min(255, stretched + 12)
                            }
                            VisorMode.BIO_RPPG -> {
                                val onBorder = (inRoiY && (x == roiX0 || x == roiX1)) ||
                                    ((y == roiY0 || y == roiY1) && x in roiX0..roiX1)
                                if (onBorder) 0xFF00FF88.toInt() else c
                            }
                            else -> c
                        }
                    }
                }
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (mode == VisorMode.WIREFRAME_POSE && poseOverlay != null) {
            drawWireframeOntoBitmap(result, poseOverlay)
        }
        return result
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
        poseSmoother.reset()
        try { poseDetector.close() } catch (_: Exception) {}
    }

    private fun buildSmoothedWireframe(
        pose: com.google.mlkit.vision.pose.Pose,
        w: Float,
        h: Float
    ): PoseWireframe {
        val rawMap = HashMap<Int, Pair<PosePointNorm, Float>>(33)
        for (lm in pose.allPoseLandmarks) {
            rawMap[lm.landmarkType] = PosePointNorm(
                (lm.position.x / w).coerceIn(0f, 1f),
                (lm.position.y / h).coerceIn(0f, 1f)
            ) to lm.inFrameLikelihood
        }
        return poseSmoother.update(rawMap)
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

    companion object {
        internal val POSE_BONE_PAIRS = listOf(
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

        /**
         * Extra RGB warmth & neutral-wall rejection guard for SKIN_CHROMA.
         */
        internal fun isWarmHumanSkinRgbGuard(r: Int, g: Int, b: Int): Boolean {
            return (r - b) >= 8 && r >= (g - 4) && (r > 32 || g > 20)
        }

        /**
         * Spectral Chlorophyll vs Synthetic Green Classifier:
         * Returns:
         * - 1: Natural chlorophyll candidate (normalized ExG + ExGR + blue-absorption check, non-neon)
         * - 2: Artificial/plastic/teal/neon green (high green hue failing biological chlorophyll ratios)
         * - 0: Non-green background
         */
        internal fun classifyChlorophyllPixel(r: Int, g: Int, b: Int): Byte {
            val luma = (r * 77 + g * 150 + b * 29) shr 8
            val sum = r + g + b
            if (luma < 18 || min(r, min(g, b)) > 205) return 0
            if (g <= r + 4 || g <= b + 6) return 0

            val nExG = ((2 * g - r - b) * 100) / max(48, sum)
            val blueDropPct = ((g - b) * 100) / max(24, g)
            val exGr = ((3 * g - (24 * r) / 10 - b) * 100) / max(48, sum)
            val isNeonDye = (g > 135 && r * 100 < g * 22 && b * 100 < g * 18)

            return if (!isNeonDye && nExG >= 16 && blueDropPct >= 18 && exGr >= 6) {
                1
            } else if (nExG >= 8 || (g - r >= 6 && g - b >= 6)) {
                2
            } else {
                0
            }
        }

        internal fun renderChlorophyllCoherencePass(
            srcRgba: IntArray,
            classBuf: ByteArray,
            dstRgba: IntArray,
            w: Int,
            h: Int
        ): Pair<Int, Int> {
            var liveCount = 0
            var synthCount = 0
            for (y in 0 until h) {
                val rowOff = y * w
                val upOff = max(0, y - 1) * w
                val dnOff = min(h - 1, y + 1) * w
                val up2Off = max(0, y - 2) * w
                val dn2Off = min(h - 1, y + 2) * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    val c = srcRgba[idx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    val luma = (r * 77 + g * 150 + b * 29) shr 8
                    val cls = classBuf[idx].toInt()

                    if (cls == 1) {
                        val xm = max(0, x - 1)
                        val xp = min(w - 1, x + 1)
                        var nLive = 0
                        if (classBuf[rowOff + xm].toInt() == 1) nLive++
                        if (classBuf[rowOff + xp].toInt() == 1) nLive++
                        if (classBuf[upOff + x].toInt() == 1) nLive++
                        if (classBuf[dnOff + x].toInt() == 1) nLive++

                        // Organic texture check across 5x5 step-2 neighborhood (flat glossy molded plastic stays smooth)
                        val gL = (srcRgba[rowOff + max(0, x - 2)] ushr 8) and 0xFF
                        val gR = (srcRgba[rowOff + min(w - 1, x + 2)] ushr 8) and 0xFF
                        val gU = (srcRgba[up2Off + x] ushr 8) and 0xFF
                        val gD = (srcRgba[dn2Off + x] ushr 8) and 0xFF
                        val localOrganicVar = abs(gL - g) + abs(gR - g) + abs(gU - g) + abs(gD - g)

                        val confirmedLive = (nLive >= 2 && localOrganicVar >= 6) || (nLive >= 3 && (g - b) >= 24 && localOrganicVar >= 3)
                        if (confirmedLive) {
                            liveCount++
                            val exg = (2 * g) - r - b
                            val boost = min(255, luma / 3 + exg * 2 + 24)
                            dstRgba[idx] = (0xFF shl 24) or (22 shl 16) or (boost shl 8) or 70
                        } else {
                            // Downgrade ultra-smooth uniform green plastic/fabric to synthetic warning tint
                            synthCount++
                            val dim = luma / 2
                            dstRgba[idx] = (0xFF shl 24) or (min(255, dim + 65) shl 16) or
                                (min(255, dim / 2 + 20) shl 8) or min(255, dim + 75)
                        }
                    } else if (cls == 2) {
                        synthCount++
                        val dim = luma / 2
                        dstRgba[idx] = (0xFF shl 24) or (min(255, dim + 72) shl 16) or
                            (min(255, dim / 2 + 18) shl 8) or min(255, dim + 82)
                    } else {
                        val dim = (luma * 42) / 100
                        dstRgba[idx] = (0xFF shl 24) or (dim shl 16) or (dim shl 8) or min(255, dim + 18)
                    }
                }
            }
            return liveCount to synthCount
        }

        /**
         * True 3x3 Sobel edge extractor over green-weighted luminance for PCB copper traces & solder pads.
         */
        internal fun renderSobelPcbTracePass(
            srcRgba: IntArray,
            dstRgba: IntArray,
            w: Int,
            h: Int
        ): Int {
            fun gLum(c: Int): Int =
                (((c ushr 16) and 0xFF) * 3 + ((c ushr 8) and 0xFF) * 10 + (c and 0xFF) * 3) shr 4

            var traceCount = 0
            for (y in 0 until h) {
                val ym = max(0, y - 1)
                val yp = min(h - 1, y + 1)
                val upRow = ym * w
                val midRow = y * w
                val dnRow = yp * w
                for (x in 0 until w) {
                    val idx = midRow + x
                    if (x == 0 || x == w - 1 || y == 0 || y == h - 1) {
                        val base = gLum(srcRgba[idx]) / 4
                        dstRgba[idx] = (0xFF shl 24) or (base shl 16) or (base shl 8) or min(255, base + 10)
                        continue
                    }
                    val xm = x - 1
                    val xp = x + 1

                    val tl = gLum(srcRgba[upRow + xm])
                    val tc = gLum(srcRgba[upRow + x])
                    val tr = gLum(srcRgba[upRow + xp])
                    val ml = gLum(srcRgba[midRow + xm])
                    val mc = gLum(srcRgba[idx])
                    val mr = gLum(srcRgba[midRow + xp])
                    val bl = gLum(srcRgba[dnRow + xm])
                    val bc = gLum(srcRgba[dnRow + x])
                    val br = gLum(srcRgba[dnRow + xp])

                    val gx = (tr + (mr shl 1) + br) - (tl + (ml shl 1) + bl)
                    val gy = (bl + (bc shl 1) + br) - (tl + (tc shl 1) + tr)
                    val grad = min(255, (abs(gx) + abs(gy)) / 2)

                    val c = srcRgba[idx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    val chromaSpread = max(r, max(g, b)) - min(r, min(g, b))

                    if (grad > 40) {
                        traceCount++
                        val isSolderPadOrPin = grad > 76 && mc > 125 && chromaSpread < 52
                        dstRgba[idx] = if (isSolderPadOrPin) {
                            // Metallic solder pads / IC leads: warm gold-amber
                            val pr = min(255, 195 + grad / 4)
                            val pg = min(255, 155 + grad / 3)
                            (0xFF shl 24) or (pr shl 16) or (pg shl 8) or 52
                        } else {
                            // Fine copper traces / SMD boundaries: crisp neon cyan-emerald
                            val tg = min(255, grad + 55)
                            val tb = min(255, grad + 85)
                            (0xFF shl 24) or (0 shl 16) or (tg shl 8) or tb
                        }
                    } else {
                        val base = mc / 4
                        dstRgba[idx] = (0xFF shl 24) or (base shl 16) or (min(255, base + 4) shl 8) or min(255, base + 12)
                    }
                }
            }
            return traceCount
        }
    }
}

/**
 * Hysteresis + Velocity-Adaptive One-Euro-style EMA smoother for ML Kit Pose landmarks.
 * Prevents close-hand jitter, rapid limb-swap spikes, and flickering bone dropout.
 */
internal class PoseTemporalSmoother {
    private data class TrackedJoint(
        var x: Float,
        var y: Float,
        var likelihood: Float,
        var missedFrames: Int = 0
    )

    private val joints = HashMap<Int, TrackedJoint>(33)

    fun reset() {
        joints.clear()
    }

    fun update(rawLandmarks: Map<Int, Pair<PosePointNorm, Float>>): PoseWireframe {
        val nextIds = HashSet<Int>(33)
        for ((type, pair) in rawLandmarks) {
            val (pt, likelihood) = pair
            val existing = joints[type]
            if (existing == null) {
                if (likelihood >= 0.50f) {
                    joints[type] = TrackedJoint(pt.x, pt.y, likelihood, 0)
                    nextIds.add(type)
                }
            } else {
                if (likelihood >= 0.32f) {
                    val dx = pt.x - existing.x
                    val dy = pt.y - existing.y
                    val dist = sqrt(dx * dx + dy * dy)
                    // Outlier spike / close-hand swap clamp + adaptive deadband EMA
                    val alpha = when {
                        dist > 0.20f && likelihood < 0.78f -> 0.14f
                        dist < 0.008f -> 0.10f
                        dist < 0.05f -> 0.24f + ((dist - 0.008f) / 0.042f) * 0.26f
                        else -> 0.56f
                    }
                    existing.x = (existing.x + dx * alpha).coerceIn(0f, 1f)
                    existing.y = (existing.y + dy * alpha).coerceIn(0f, 1f)
                    existing.likelihood = likelihood
                    existing.missedFrames = 0
                    nextIds.add(type)
                }
            }
        }

        // Coast briefly lost joints (up to 3 frames) so close-hand occlusions don't cause flickering bones
        val iter = joints.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!nextIds.contains(entry.key)) {
                entry.value.missedFrames++
                entry.value.likelihood *= 0.85f
                if (entry.value.missedFrames > 3) {
                    iter.remove()
                }
            }
        }

        if (joints.size < 3) {
            return PoseWireframe(visibleCount = joints.size)
        }

        val bones = mutableListOf<Pair<PosePointNorm, PosePointNorm>>()
        for ((a, b) in CyberVisorEngine.POSE_BONE_PAIRS) {
            val pa = joints[a]
            val pb = joints[b]
            if (pa != null && pb != null) {
                bones.add(PosePointNorm(pa.x, pa.y) to PosePointNorm(pb.x, pb.y))
            }
        }
        val pts = joints.values.map { PosePointNorm(it.x, it.y) }
        return PoseWireframe(joints = pts, bones = bones, visibleCount = joints.size)
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
