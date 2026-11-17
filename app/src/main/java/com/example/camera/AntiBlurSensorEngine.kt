package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * AntiBlurSensorEngine v5.0 (Redmi 13C MT6769Z / Helio G85 Hardened):
 * - High-rate Gyroscope sampling (anti-blur / Jello mitigation).
 * - Accelerometer Document Level & Parallelism Indicator.
 * - Proximity sensor pocket-lock detection (stealth blackout touch guard).
 * - Snatch Protection (sudden high-G jerk + rapid rotation) & optional Flip-to-Disguise.
 * - Adaptive Noise-Suppressed Laplacian Variance over 1:1 Center ROI (400x400).
 * - Real-time Y-plane Focus Peaking contour sampling & Exposure Clipping (highlights + shadows).
 */
class AntiBlurSensorEngine(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    @Volatile
    var currentAngularSpeed: Float = 0f
        private set

    @Volatile
    var isStable: Boolean = true
        private set

    @Volatile
    var isTripodMode: Boolean = false
        private set

    @Volatile
    var currentTiltDeg: Float = 0f
        private set

    @Volatile
    var isDocumentParallel: Boolean = false
        private set

    @Volatile
    var isProximityNear: Boolean = false
        private set

    var onSnatchDetected: (() -> Unit)? = null
    var onFaceDownDetected: (() -> Unit)? = null
    var onKnockDetected: ((Float) -> Unit)? = null

    private var lastLinearAccelSpikeMs = 0L
    private var lastSnatchTriggerMs = 0L
    private var lastFaceDownTriggerMs = 0L
    private var lastKnockTriggerMs = 0L
    private var lastAccelMag = 9.81f

    data class MotionTelemetry(
        val motionScore: Int,       // 0..100 (% of blocks shifted above noise floor)
        val avgLuma: Int,           // 0..255 ambient scene brightness
        val isLowLight: Boolean,    // avgLuma < 60
        val grid: IntArray
    )

    companion object {
        const val MICRO_BLUR_THRESHOLD = 0.075f // rad/s
        const val TRIPOD_THRESHOLD = 0.018f     // rad/s
        const val SHARPNESS_PASS_THRESHOLD = 110.0
        const val DOCUMENT_PARALLEL_THRESHOLD_DEG = 3.0f
        const val SNATCH_ACCEL_THRESHOLD = 28.0f // m/s^2 sudden jerk
        const val KNOCK_ACCEL_MIN = 4.2f         // m/s^2 door/desk knock tap
        const val KNOCK_ACCEL_MAX = 24.0f        // below snatch threshold
        const val SNATCH_GYRO_THRESHOLD = 5.0f   // rad/s rapid twist
        const val MOTION_GRID_SIZE = 12
    }

    fun start() {
        gyroscope?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        linearAccel?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        proximity?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val nowMs = System.currentTimeMillis()
        when (event?.sensor?.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val omega = sqrt(wx * wx + wy * wy + wz * wz)
                currentAngularSpeed = omega
                isStable = omega < MICRO_BLUR_THRESHOLD
                isTripodMode = omega < TRIPOD_THRESHOLD

                if (omega >= SNATCH_GYRO_THRESHOLD && (nowMs - lastLinearAccelSpikeMs) <= 300L) {
                    if (nowMs - lastSnatchTriggerMs > 2500L) {
                        lastSnatchTriggerMs = nowMs
                        onSnatchDetected?.invoke()
                    }
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val linMag = sqrt(ax * ax + ay * ay + az * az)
                if (linMag >= SNATCH_ACCEL_THRESHOLD) {
                    lastLinearAccelSpikeMs = nowMs
                } else if (linMag in KNOCK_ACCEL_MIN..KNOCK_ACCEL_MAX) {
                    if (nowMs - lastKnockTriggerMs > 1200L) {
                        lastKnockTriggerMs = nowMs
                        onKnockDetected?.invoke(linMag)
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val horizontalNorm = sqrt(ax * ax + ay * ay)
                val tilt = Math.toDegrees(atan2(horizontalNorm.toDouble(), abs(az).toDouble())).toFloat()
                currentTiltDeg = tilt
                isDocumentParallel = tilt <= DOCUMENT_PARALLEL_THRESHOLD_DEG

                // Fallback snatch jerk detection if TYPE_LINEAR_ACCELERATION is absent
                val mag = sqrt(ax * ax + ay * ay + az * az)
                if (linearAccel == null && abs(mag - lastAccelMag) >= SNATCH_ACCEL_THRESHOLD) {
                    lastLinearAccelSpikeMs = nowMs
                }
                lastAccelMag = mag

                // Face-down flip detection (az <= -8.8 m/s^2 and nearly flat)
                if (az <= -8.8f && horizontalNorm < 3.2f) {
                    if (nowMs - lastFaceDownTriggerMs > 2500L) {
                        lastFaceDownTriggerMs = nowMs
                        onFaceDownDetected?.invoke()
                    }
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val dist = event.values[0]
                val maxRange = proximity?.maximumRange ?: 5f
                isProximityNear = dist < minOf(maxRange, 3.0f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun calculateCenterRoiLaplacianVariance(
        yBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        targetRoiSize: Int = 400
    ): Double {
        if (width <= 10 || height <= 10) return 0.0

        val actualRoi = minOf(targetRoiSize, minOf(width, height) - 6)
        val startX = (width - actualRoi) / 2
        val startY = (height - actualRoi) / 2
        val limit = yBuffer.limit()

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        val step = 2
        for (y in startY + 2 until startY + actualRoi - 2 step step) {
            val rowOffset = y * rowStride
            for (x in startX + 2 until startX + actualRoi - 2 step step) {
                val cIdx = rowOffset + x
                val tIdx = rowOffset - (rowStride * 2) + x
                val bIdx = rowOffset + (rowStride * 2) + x
                val lIdx = rowOffset + x - 2
                val rIdx = rowOffset + x + 2

                if (bIdx >= limit || rIdx >= limit || tIdx < 0 || lIdx < 0) continue

                fun getSmoothed(offset: Int): Int {
                    if (offset - rowStride - 1 < 0 || offset + rowStride + 1 >= limit) {
                        return yBuffer.get(offset).toInt() and 0xFF
                    }
                    val s00 = yBuffer.get(offset - rowStride - 1).toInt() and 0xFF
                    val s01 = yBuffer.get(offset - rowStride).toInt() and 0xFF
                    val s02 = yBuffer.get(offset - rowStride + 1).toInt() and 0xFF
                    val s10 = yBuffer.get(offset - 1).toInt() and 0xFF
                    val s11 = yBuffer.get(offset).toInt() and 0xFF
                    val s12 = yBuffer.get(offset + 1).toInt() and 0xFF
                    val s20 = yBuffer.get(offset + rowStride - 1).toInt() and 0xFF
                    val s21 = yBuffer.get(offset + rowStride).toInt() and 0xFF
                    val s22 = yBuffer.get(offset + rowStride + 1).toInt() and 0xFF
                    return (s00 + s01 + s02 + s10 + s11 + s12 + s20 + s21 + s22) / 9
                }

                val center = getSmoothed(cIdx)
                val top = getSmoothed(rowOffset - rowStride + x)
                val bottom = getSmoothed(rowOffset + rowStride + x)
                val left = getSmoothed(rowOffset + x - 1)
                val right = getSmoothed(rowOffset + x + 1)

                val lap = (top + bottom + left + right) - (4 * center)
                sum += lap
                sumSq += (lap * lap)
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /**
     * Real-time Y-plane gradient Focus Peaking contour sampler.
     * Returns normalized [0f..1f] coordinates rotated to match portrait preview orientation.
     */
    fun computeFocusPeakingPoints(
        yBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int = 90,
        edgeThreshold: Int = 46,
        maxPoints: Int = 380
    ): List<Offset> {
        if (width <= 16 || height <= 16) return emptyList()
        val limit = yBuffer.limit()
        val points = ArrayList<Offset>(minOf(maxPoints, 256))
        val step = 10

        for (y in 4 until height - 4 step step) {
            val rowOff = y * rowStride
            for (x in 4 until width - 4 step step) {
                val c = rowOff + x
                val r = c + 2
                val b = c + rowStride * 2
                if (b >= limit || r >= limit) continue

                val yc = yBuffer.get(c).toInt() and 0xFF
                val yr = yBuffer.get(r).toInt() and 0xFF
                val yb = yBuffer.get(b).toInt() and 0xFF
                val grad = abs(yc - yr) + abs(yc - yb)
                if (grad >= edgeThreshold) {
                    val nx = x.toFloat() / width.toFloat()
                    val ny = y.toFloat() / height.toFloat()
                    val rotated = when (((rotationDegrees % 360) + 360) % 360) {
                        90 -> Offset(1f - ny, nx)
                        180 -> Offset(1f - nx, 1f - ny)
                        270 -> Offset(ny, 1f - nx)
                        else -> Offset(nx, ny)
                    }
                    points.add(rotated)
                    if (points.size >= maxPoints) return points
                }
            }
        }
        return points
    }

    fun calculateExposureMetrics(
        yBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): Pair<Int, Int> {
        if (width <= 8 || height <= 8) return 0 to 0
        val limit = yBuffer.limit()
        var over = 0
        var under = 0
        var total = 0
        val step = 8
        for (y in 0 until height step step) {
            val rowOffset = y * rowStride
            for (x in 0 until width step step) {
                val idx = rowOffset + x
                if (idx >= limit) continue
                val lum = yBuffer.get(idx).toInt() and 0xFF
                if (lum >= 245) over++
                if (lum <= 14) under++
                total++
            }
        }
        if (total == 0) return 0 to 0
        return ((over * 100) / total) to ((under * 100) / total)
    }

    fun calculateOverexposurePercent(
        yBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): Int = calculateExposureMetrics(yBuffer, width, height, rowStride).first

    /**
     * Sentinel v5.0: Lightweight 12x12 Y-plane block motion detector + ambient Day/Night luma sampler.
     * Runs in <1 ms with zero allocations beyond the tiny 144-int grid.
     */
    fun computeMotionAndLuma(
        yBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        previousGrid: IntArray?,
        lumaDeltaThreshold: Int = 14
    ): MotionTelemetry {
        val gridN = MOTION_GRID_SIZE
        val totalCells = gridN * gridN
        if (width < gridN || height < gridN) {
            return MotionTelemetry(0, 128, false, IntArray(totalCells) { 128 })
        }

        val limit = yBuffer.limit()
        val cellW = width / gridN
        val cellH = height / gridN
        val grid = IntArray(totalCells)
        var globalLumaSum = 0L
        var activeBlocks = 0

        for (gy in 0 until gridN) {
            val yStart = gy * cellH
            val yEnd = minOf(height, yStart + cellH)
            for (gx in 0 until gridN) {
                val xStart = gx * cellW
                val xEnd = minOf(width, xStart + cellW)
                var sum = 0
                var samples = 0
                val stepY = maxOf(2, cellH / 4)
                val stepX = maxOf(2, cellW / 4)

                var y = yStart + stepY / 2
                while (y < yEnd) {
                    val rowOff = y * rowStride
                    var x = xStart + stepX / 2
                    while (x < xEnd) {
                        val idx = rowOff + x
                        if (idx in 0 until limit) {
                            sum += (yBuffer.get(idx).toInt() and 0xFF)
                            samples++
                        }
                        x += stepX
                    }
                    y += stepY
                }
                val mean = if (samples > 0) sum / samples else 0
                val cellIdx = gy * gridN + gx
                grid[cellIdx] = mean
                globalLumaSum += mean
                if (previousGrid != null && previousGrid.size == totalCells) {
                    if (abs(mean - previousGrid[cellIdx]) >= lumaDeltaThreshold) {
                        activeBlocks++
                    }
                }
            }
        }

        val avgLuma = (globalLumaSum / totalCells).toInt().coerceIn(0, 255)
        val motionScore = if (previousGrid != null && previousGrid.size == totalCells) {
            ((activeBlocks * 100) / totalCells).coerceIn(0, 100)
        } else {
            0
        }
        return MotionTelemetry(
            motionScore = motionScore,
            avgLuma = avgLuma,
            isLowLight = avgLuma < 60,
            grid = grid
        )
    }

    fun processHandheldMfsr(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list empty")
        if (frames.size == 1) return frames[0]

        val base = frames[0]
        val w = base.width
        val h = base.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val frameCount = frames.size
        val accR = IntArray(w * h)
        val accG = IntArray(w * h)
        val accB = IntArray(w * h)
        val pixels = IntArray(w * h)

        for (frame in frames) {
            frame.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in 0 until w * h) {
                val c = pixels[i]
                accR[i] += Color.red(c)
                accG[i] += Color.green(c)
                accB[i] += Color.blue(c)
            }
        }

        val outPixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = (accR[i] / frameCount).coerceIn(0, 255)
            val g = (accG[i] / frameCount).coerceIn(0, 255)
            val b = (accB[i] / frameCount).coerceIn(0, 255)
            outPixels[i] = Color.argb(255, r, g, b)
        }

        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    fun applyPolyblur(bitmap: Bitmap, iterations: Int = 1): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)

        val alpha = 0.35f
        for (iter in 0 until iterations) {
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val c = src[row + x]
                    val t = src[row - w + x]
                    val b = src[row + w + x]
                    val l = src[row + x - 1]
                    val r = src[row + x + 1]

                    val cr = Color.red(c)
                    val cg = Color.green(c)
                    val cb = Color.blue(c)

                    val lapR = (Color.red(t) + Color.red(b) + Color.red(l) + Color.red(r)) - (4 * cr)
                    val lapG = (Color.green(t) + Color.green(b) + Color.green(l) + Color.green(r)) - (4 * cg)
                    val lapB = (Color.blue(t) + Color.blue(b) + Color.blue(l) + Color.blue(r)) - (4 * cb)

                    val newR = (cr - alpha * lapR).toInt().coerceIn(0, 255)
                    val newG = (cg - alpha * lapG).toInt().coerceIn(0, 255)
                    val newB = (cb - alpha * lapB).toInt().coerceIn(0, 255)

                    dst[row + x] = Color.argb(255, newR, newG, newB)
                }
            }
            System.arraycopy(dst, 0, src, 0, w * h)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(dst, 0, w, 0, 0, w, h)
        return result
    }
}
