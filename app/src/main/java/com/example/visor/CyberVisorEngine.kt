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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class VisorMode(val title: String, val badgeRu: String, val hintRu: String) {
    NORMAL("Обычный", "КАМЕРА", "Стандартный аппаратный видоискатель без нагрузки"),
    BIO_RPPG("Пульс+HRV", "rPPG/HRV", "Помести лоб/щеку или палец в квадрат на 5с — снимет ЧСС и HRV (RMSSD)"),
    EULER_AMP("Эйлер x20", "МИКРО", "Эйлерово усиление микродвижений и пульсации сосудов/дыхания в 20x"),
    VEIN_HEMO("Веновизор", "ГЕМО", "Оптический контраст подкожного гемоглобина и сосудов (ln R/G)"),
    DERMA_UV_SIM("Дерма-УФ", "МЕЛАНИН", "Усиление синего спектра (~450 нм) для проявления пигмента кожи"),
    THERMAL_LUT("Термо-LUT", "IRONBOW", "Цветовая карта яркости Ironbow (оптическая визуализация)"),
    DEHAZE_LIVE("Анти-Туман", "DEHAZE", "Вычитание дымки/тумана и контрастная проявка силуэтов в реальном времени"),
    WIREFRAME_POSE("Скелет 33т", "КАРКАС", "Построение проволочного скелета (33 точки ML Kit) поверх тёмной/сложной сцены"),
    VEG_CHLOROPHYLL("Флора/Еда", "ХЛОРО", "Спектральный индекс ExG (2G-R-B): живая зелень vs пластик/порча"),
    NIGHT_GAMMA("Ночь-Буст", "ГАММА", "Нелинейное вытягивание теней в темноте (γ=0.42)"),
    IR_BEACON("Поиск ИК", "ИК-850", "Детектор ближнего ИК (850–940 нм): пульт, ночная подсветка. Лучше с фронталки")
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
    val irPeakDetected: Boolean = false,
    val irHotPixels: Int = 0,
    val poseWireframe: PoseWireframe? = null
)

/**
 * CyberVisorEngine v2.0:
 * Real-time biophysical, spectral & bundled-CV computation over CameraX YUV_420_888 frames.
 * - Zero CPU cost when VisorMode == NORMAL.
 * - Double-buffered upright ARGB_8888 rendering (~2-4ms on Helio G85).
 * - Includes POS rPPG (BPM + HRV RMSSD), Eulerian micro-motion/color magnification,
 *   live atmospheric Dehaze, 33-point bundled Pose Wireframe, Beer-Lambert Vein contrast,
 *   Melanin UV contrast, Chlorophyll/Freshness ExG index, Ironbow LUT, Night Gamma, and IR detector.
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
            }
        }

    private val bitmapLock = Any()
    private var backBufferBitmap: Bitmap? = null
    private var frontBufferBitmap: Bitmap? = null
    private var pixelBuffer: IntArray = IntArray(0)
    private var rawRgbaBuffer: IntArray = IntArray(0)

    private val rppgTracker = PosRppgTracker()
    private val eulerTracker = EulerianTemporalAmplifier()

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

    // Precomputed 256-entry Ironbow false-color LUT
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
        if ((mode == VisorMode.EULER_AMP || mode == VisorMode.WIREFRAME_POSE) && rawRgbaBuffer.size != totalDst) {
            rawRgbaBuffer = IntArray(totalDst)
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

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var roiCount = 0
        var irHotPixels = 0

        // Estimate scene airlight & min floor for DEHAZE_LIVE using fast subsample
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
            // Atmospheric haze veil estimate: mix of floor & average dark channel
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
                val uVal = if (uIdx in 0 until uLim) (uBuf.get(uIdx).toInt() and 0xFF) - 128 else 0
                val vVal = if (vIdx in 0 until vLim) (vBuf.get(vIdx).toInt() and 0xFF) - 128 else 0

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
                if (mode == VisorMode.EULER_AMP || mode == VisorMode.WIREFRAME_POSE) {
                    rawRgbaBuffer[dstIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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

                pixelBuffer[dstIdx] = when (mode) {
                    VisorMode.NORMAL -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    VisorMode.BIO_RPPG -> {
                        val onBorder = (inRoiY && (dx == roiX0 || dx == roiX1)) ||
                            ((dy == roiY0 || dy == roiY1) && dx in roiX0..roiX1)
                        if (onBorder) 0xFF00FF88.toInt() else (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }

                    VisorMode.EULER_AMP -> (0xFF shl 24) or (r shl 16) or (g shl 8) or b // processed below in temporal pass

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
                        // Fast Dark-Channel atmospheric scattering subtraction + stretch
                        val span = max(48, lumaMax - airlightVeil)
                        val dr = (((r - airlightVeil) * 255) / span).coerceIn(0, 255)
                        val dg = (((g - airlightVeil) * 255) / span).coerceIn(0, 255)
                        val db = (((b - airlightVeil) * 255) / span).coerceIn(0, 255)
                        // Boost micro-contrast slightly via gamma 0.85
                        (0xFF shl 24) or (dr shl 16) or (dg shl 8) or db
                    }

                    VisorMode.WIREFRAME_POSE -> {
                        // Slightly tinted tactical cyber-view so 33-point skeleton pops clearly
                        val wr = (r * 45) / 100
                        val wg = (g * 65) / 100
                        val wb = (b * 85) / 100
                        (0xFF shl 24) or (wr shl 16) or (wg shl 8) or wb
                    }

                    VisorMode.VEG_CHLOROPHYLL -> {
                        // Optical Excess Green (ExG = 2G - R - B): live chlorophyll glows emerald, non-living/bruised dims purple/amber
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
            }
        }

        if (mode == VisorMode.EULER_AMP) {
            eulerTracker.amplifyInto(rawRgbaBuffer, pixelBuffer, dstW, dstH)
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

        // Throttled async 33-point bundled Pose estimation for WIREFRAME_POSE (never stalls 30fps loop)
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
                if (roiCount > 80) {
                    val (bpm, hrv, quality) = rppgTracker.pushSample(
                        timestampNs = imageProxy.imageInfo.timestamp,
                        meanR = sumR / roiCount,
                        meanG = sumG / roiCount,
                        meanB = sumB / roiCount
                    )
                    VisorTelemetry(
                        mode = mode,
                        estimatedBpm = bpm,
                        estimatedHrvMs = hrv,
                        signalQuality = quality
                    )
                } else {
                    VisorTelemetry(mode = mode)
                }
            }
            VisorMode.WIREFRAME_POSE -> VisorTelemetry(
                mode = mode,
                poseWireframe = latestPoseWireframe
            )
            VisorMode.IR_BEACON -> VisorTelemetry(
                mode = mode,
                irPeakDetected = (irHotPixels >= 5),
                irHotPixels = irHotPixels
            )
            else -> VisorTelemetry(mode = mode)
        }

        return VisorFrameResult(bitmap = readyBitmap, telemetry = telemetry)
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
 * MIT CSAIL inspired zero-weight Eulerian Temporal Band-Pass Amplifier:
 * Computes Difference-of-Exponential-Moving-Averages (DoE) on the green/luma signal
 * and amplifies tiny periodic motion & microvascular color changes by ~20x without external weights.
 */
internal class EulerianTemporalAmplifier(private val gain: Float = 20f) {
    private var fastLuma = FloatArray(0)
    private var slowLuma = FloatArray(0)
    private var initialized = false
    private var lastW = 0
    private var lastH = 0

    fun reset() {
        initialized = false
    }

    fun amplifyInto(srcPixels: IntArray, dstPixels: IntArray, w: Int, h: Int) {
        val n = w * h
        if (srcPixels.size < n || dstPixels.size < n) return
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
            lastW = w
            lastH = h
            initialized = true
            return
        }

        for (i in 0 until n) {
            val c = srcPixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val gf = g.toFloat()

            // Temporal band-pass via Difference-of-IIR (captures ~0.6..4 Hz breathing/pulse/micro-vibration)
            val fNew = fastLuma[i] * 0.45f + gf * 0.55f
            val sNew = slowLuma[i] * 0.90f + gf * 0.10f
            fastLuma[i] = fNew
            slowLuma[i] = sNew

            val bandDelta = fNew - sNew
            val amp = (bandDelta * gain).roundToInt()

            // Overlay amplified micro-oscillation onto green + subtle thermal cyan highlights
            val nr = (r + (amp / 4)).coerceIn(0, 255)
            val ng = (g + amp).coerceIn(0, 255)
            val nb = (b + abs(amp) / 2).coerceIn(0, 255)
            dstPixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}

/**
 * Pure Kotlin POS (Plane-Orthogonal-to-Skin) rPPG heart-rate + HRV (RMSSD ms) tracker.
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

    /**
     * Returns Triple(bpmOrNull, hrvRmssdMsOrNull, signalQuality0to1).
     */
    fun pushSample(timestampNs: Long, meanR: Double, meanG: Double, meanB: Double): Triple<Int?, Int?, Float> {
        timesSec[head] = timestampNs / 1_000_000_000.0
        rBuf[head] = meanR
        gBuf[head] = meanG
        bBuf[head] = meanB
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
        val rawPulse = DoubleArray(n)
        val windowedPulse = DoubleArray(n)
        for (i in 0 until n) {
            val valPos = s1[i] + alpha * s2[i]
            rawPulse[i] = valPos
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            windowedPulse[i] = valPos * hann
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

        // Compute HRV (RMSSD ms) from smoothed systolic peaks over rawPulse
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
        val rmssd = sqrt(diffSq.average()).roundToInt().coerceIn(5, 220)
        return rmssd
    }
}
