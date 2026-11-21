package com.example.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * RetroReflectionDetector (Part B — Anti-Optics Lens Glint Detector):
 * Compares paired Torch-ON and Torch-OFF Y-plane frames with micro-shift translation compensation:
 * - Hidden optical lenses exhibit strong coaxial retro-reflection only when illuminated by the phone's LED,
 *   forming compact, circular, isolated differential hotspots (`Y_on - Y_off >= deltaThreshold`).
 * - Rejects broad bright walls, daylight windows, or large mirror reflections by cluster area & compactness.
 */
object RetroReflectionDetector {

    data class RetroHotspot(
        val normX: Float,       // 0..1 rotated to match portrait preview
        val normY: Float,       // 0..1 rotated to match portrait preview
        val radiusPx: Float,    // approximate cluster radius in source sample space
        val deltaLuma: Int,     // peak Y_on - Y_off intensity
        val circularity: Float  // 0..1 compactness vs bounding box
    )

    data class RetroScanResult(
        val hotspots: List<RetroHotspot>,
        val confidence: Float,
        val alignedShift: Pair<Int, Int>,
        val summaryRu: String
    )

    fun analyzePairedYuv(
        onYPlane: ByteArray,
        offYPlane: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int = 90,
        isFrontCamera: Boolean = false,
        deltaThreshold: Int = 52
    ): RetroScanResult {
        val n = width * height
        if (width < 24 || height < 24 || onYPlane.size < n || offYPlane.size < n) {
            return RetroScanResult(emptyList(), 0f, 0 to 0, "Недостаточно данных кадра")
        }

        // Micro-align ON frame onto OFF frame in +/-6 px window so hand tremor doesn't create false edges
        val (shiftX, shiftY) = estimateLumaShift(offYPlane, onYPlane, width, height, searchRadius = 6)

        val deltaMask = BooleanArray(n)
        val deltaValues = IntArray(n)
        for (y in 2 until height - 2) {
            val onY = (y + shiftY).coerceIn(0, height - 1)
            val offRow = y * width
            val onRow = onY * width
            for (x in 2 until width - 2) {
                val onX = (x + shiftX).coerceIn(0, width - 1)
                val yOff = offYPlane[offRow + x].toInt() and 0xFF
                val yOn = onYPlane[onRow + onX].toInt() and 0xFF
                val diff = yOn - yOff
                // Reject static bright bulbs/windows already saturated in OFF frame
                if (diff >= deltaThreshold && yOff < 215 && yOn >= 135) {
                    val idx = offRow + x
                    deltaMask[idx] = true
                    deltaValues[idx] = diff
                }
            }
        }

        // Connected-components BFS to cluster positive retro-reflection sparks & filter out broad walls
        val visited = BooleanArray(n)
        val queue = IntArray(min(n, 4096))
        val candidates = mutableListOf<RetroHotspot>()
        val rot = ((rotationDegrees % 360) + 360) % 360
        val maxClusterArea = max(48, n / 28) // broad lit walls/doors exceed ~3.5% of frame

        for (y in 2 until height - 2) {
            val rowOff = y * width
            for (x in 2 until width - 2) {
                val startIdx = rowOff + x
                if (!deltaMask[startIdx] || visited[startIdx]) continue

                var qHead = 0
                var qTail = 0
                queue[qTail++] = startIdx
                visited[startIdx] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var sumX = 0L
                var sumY = 0L
                var area = 0
                var peakDelta = 0

                while (qHead < qTail) {
                    val cur = queue[qHead++]
                    val cy = cur / width
                    val cx = cur - cy * width
                    area++
                    sumX += cx
                    sumY += cy
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    val dVal = deltaValues[cur]
                    if (dVal > peakDelta) peakDelta = dVal

                    for (ny in max(1, cy - 1)..min(height - 2, cy + 1)) {
                        val nRow = ny * width
                        for (nx in max(1, cx - 1)..min(width - 2, cx + 1)) {
                            val nIdx = nRow + nx
                            if (deltaMask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (qTail < queue.size) {
                                    queue[qTail++] = nIdx
                                }
                            }
                        }
                    }
                }

                // Optical lens criteria: compact spark (4..maxClusterArea pixels), roughly circular/oval
                val bw = (maxX - minX + 1).coerceAtLeast(1)
                val bh = (maxY - minY + 1).coerceAtLeast(1)
                val aspect = min(bw, bh).toFloat() / max(bw, bh).toFloat()
                val fillRatio = area.toFloat() / (bw * bh).toFloat()
                val radiusEst = kotlin.math.sqrt(area / PI).toFloat()

                if (area in 4..maxClusterArea &&
                    aspect >= 0.42f &&
                    fillRatio >= 0.34f &&
                    radiusEst in 1.1f..22f
                ) {
                    val cxNorm = (sumX.toFloat() / area) / width.toFloat()
                    val cyNorm = (sumY.toFloat() / area) / height.toFloat()
                    var rx: Float
                    var ry: Float
                    when (rot) {
                        90 -> { rx = 1f - cyNorm; ry = cxNorm }
                        180 -> { rx = 1f - cxNorm; ry = 1f - cyNorm }
                        270 -> { rx = cyNorm; ry = 1f - cxNorm }
                        else -> { rx = cxNorm; ry = cyNorm }
                    }
                    if (isFrontCamera) rx = 1f - rx
                    candidates.add(
                        RetroHotspot(
                            normX = rx.coerceIn(0f, 1f),
                            normY = ry.coerceIn(0f, 1f),
                            radiusPx = radiusEst,
                            deltaLuma = peakDelta,
                            circularity = ((aspect + fillRatio) * 0.5f).coerceIn(0f, 1f)
                        )
                    )
                }
            }
        }

        val sorted = candidates.sortedByDescending { it.deltaLuma * it.circularity }.take(6)
        val conf = if (sorted.isEmpty()) 0f else {
            val top = sorted.first()
            ((top.deltaLuma / 140f) * 0.6f + top.circularity * 0.4f).coerceIn(0.25f, 0.99f)
        }
        val summary = if (sorted.isEmpty()) {
            "Анти-Оптика: чистые блики линз не найдены"
        } else {
            "🚨 Обнаружен блик оптики: ${sorted.size} точ. (Δ+${sorted.first().deltaLuma} Y)"
        }
        return RetroScanResult(
            hotspots = sorted,
            confidence = conf,
            alignedShift = shiftX to shiftY,
            summaryRu = summary
        )
    }

    private fun estimateLumaShift(
        ref: ByteArray,
        cand: ByteArray,
        w: Int,
        h: Int,
        searchRadius: Int
    ): Pair<Int, Int> {
        val x0 = w / 5
        val x1 = (w * 4) / 5
        val y0 = h / 5
        val y1 = (h * 4) / 5
        val step = max(3, min(w, h) / 40)
        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE

        for (dy in -searchRadius..searchRadius step 2) {
            for (dx in -searchRadius..searchRadius step 2) {
                val sad = roiSad(ref, cand, w, h, x0, x1, y0, y1, step, dx, dy)
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        val cx = bestDx
        val cy = bestDy
        for (dy in (cy - 1)..(cy + 1)) {
            for (dx in (cx - 1)..(cx + 1)) {
                if (dx < -searchRadius || dx > searchRadius || dy < -searchRadius || dy > searchRadius) continue
                val sad = roiSad(ref, cand, w, h, x0, x1, y0, y1, step, dx, dy)
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private fun roiSad(
        ref: ByteArray,
        cand: ByteArray,
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
            val rRow = y * w
            val cRow = cy * w
            var x = x0
            while (x < x1) {
                val cx = (x + dx).coerceIn(0, w - 1)
                sad += abs((ref[rRow + x].toInt() and 0xFF) - (cand[cRow + cx].toInt() and 0xFF))
                x += step
            }
            y += step
        }
        return sad
    }

    fun downsampleYPlane(
        yBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        targetLongSide: Int = 240
    ): Triple<ByteArray, Int, Int> {
        val step = max(1, max(width, height) / targetLongSide)
        val outW = max(16, width / step)
        val outH = max(16, height / step)
        val out = ByteArray(outW * outH)
        val limit = yBuffer.limit()
        for (oy in 0 until outH) {
            val sy = min(height - 1, oy * step)
            val rowOff = sy * rowStride
            val dstOff = oy * outW
            for (ox in 0 until outW) {
                val sx = min(width - 1, ox * step)
                val idx = rowOff + sx
                out[dstOff + ox] = if (idx in 0 until limit) yBuffer.get(idx) else 0
            }
        }
        return Triple(out, outW, outH)
    }
}
