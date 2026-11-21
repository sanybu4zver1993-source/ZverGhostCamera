package com.example.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FocusStackEngine (Part B):
 * Focus Bracketing & All-in-Focus Laplacian Tile Fusion engine.
 * - Aligns bracketed Bitmaps onto the center anchor using [MultiFrameStacker.estimateTranslationShift].
 * - Evaluates 16x16 Laplacian sharpness energy across each frame and smoothly blends the sharpest tiles.
 * - Safe on 4 GB Helio G85 devices (bounded resolution + prompt recycling).
 */
object FocusStackEngine {

    data class FocusStackOutput(
        val bitmap: Bitmap,
        val frameCount: Int,
        val alignedOffsets: List<Pair<Int, Int>>,
        val sharpnessGainPct: Int,
        val usedManualFocusSweep: Boolean
    )

    /**
     * Generates normalized [0f..1f] focus distance ratios for [steps] bracketed shots.
     */
    fun buildDiopterSteps(steps: Int = 3): List<Float> = when {
        steps <= 1 -> listOf(0.5f)
        steps == 2 -> listOf(0.25f, 0.78f)
        steps == 3 -> listOf(0.18f, 0.50f, 0.85f)
        steps == 4 -> listOf(0.15f, 0.40f, 0.65f, 0.90f)
        else -> listOf(0.12f, 0.32f, 0.52f, 0.72f, 0.92f)
    }

    /**
     * Aligns [frames] and fuses the sharpest 16x16 tiles from each plane into a single crisp Bitmap.
     */
    fun fuseFocusStack(
        frames: List<Bitmap>,
        usedManualFocusSweep: Boolean = false,
        recycleInputs: Boolean = false
    ): FocusStackOutput {
        require(frames.isNotEmpty()) { "Focus stack requires at least 1 frame" }
        if (frames.size == 1) {
            val copy = frames[0].copy(Bitmap.Config.ARGB_8888, false)
            if (recycleInputs && !frames[0].isRecycled) frames[0].recycle()
            return FocusStackOutput(
                bitmap = copy,
                frameCount = 1,
                alignedOffsets = listOf(0 to 0),
                sharpnessGainPct = 0,
                usedManualFocusSweep = usedManualFocusSweep
            )
        }

        // Use middle frame as geometric anchor so near/far magnification variance averages gracefully
        val anchorIdx = frames.size / 2
        val anchor = frames[anchorIdx]
        val w = anchor.width
        val h = anchor.height
        val totalPixels = w * h

        val alignedFrames = ArrayList<IntArray>(frames.size)
        val anchorPixels = IntArray(totalPixels)
        anchor.getPixels(anchorPixels, 0, w, 0, 0, w, h)
        alignedFrames.add(anchorPixels)

        val offsets = mutableListOf(0 to 0)
        for (i in frames.indices) {
            if (i == anchorIdx) continue
            val src = frames[i]
            val sized = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
            val pix = IntArray(totalPixels)
            sized.getPixels(pix, 0, w, 0, 0, w, h)
            if (sized !== src && !sized.isRecycled) sized.recycle()
            val (dx, dy) = MultiFrameStacker.estimateTranslationShift(anchorPixels, pix, w, h, searchRadius = 8)
            offsets.add(dx to dy)
            alignedFrames.add(if (dx == 0 && dy == 0) pix else shiftPixels(pix, w, h, dx, dy))
        }

        if (recycleInputs) {
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }

        val tileSize = 16
        val tilesX = max(1, (w + tileSize - 1) / tileSize)
        val tilesY = max(1, (h + tileSize - 1) / tileSize)
        val numFrames = alignedFrames.size

        // Compute Laplacian energy per 16x16 tile for every aligned frame
        val tileEnergies = Array(numFrames) { DoubleArray(tilesX * tilesY) }
        for (f in 0 until numFrames) {
            val pix = alignedFrames[f]
            val energies = tileEnergies[f]
            for (ty in 0 until tilesY) {
                val y0 = max(1, ty * tileSize)
                val y1 = min(h - 1, (ty + 1) * tileSize)
                for (tx in 0 until tilesX) {
                    val x0 = max(1, tx * tileSize)
                    val x1 = min(w - 1, (tx + 1) * tileSize)
                    var energy = 0.0
                    var cnt = 0
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in x0 until x1) {
                            val c = fastLum(pix[row + x])
                            val l = fastLum(pix[row + x - 1])
                            val r = fastLum(pix[row + x + 1])
                            val u = fastLum(pix[row - w + x])
                            val d = fastLum(pix[row + w + x])
                            val lap = (l + r + u + d - (c shl 2)).toDouble()
                            energy += lap * lap
                            cnt++
                        }
                    }
                    energies[ty * tilesX + tx] = if (cnt > 0) energy / cnt else 0.0
                }
            }
        }

        // Smooth winner tile selection using 3x3 weighted sharpness so transitions avoid harsh seams
        val winnerFrame = IntArray(tilesX * tilesY)
        var anchorEnergySum = 0.0
        var fusedEnergySum = 0.0
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val tIdx = ty * tilesX + tx
                anchorEnergySum += tileEnergies[0][tIdx]
                var bestF = 0
                var bestScore = -1.0
                for (f in 0 until numFrames) {
                    var smoothed = tileEnergies[f][tIdx] * 2.0
                    var wSum = 2.0
                    for (ny in max(0, ty - 1)..min(tilesY - 1, ty + 1)) {
                        for (nx in max(0, tx - 1)..min(tilesX - 1, tx + 1)) {
                            if (nx == tx && ny == ty) continue
                            smoothed += tileEnergies[f][ny * tilesX + nx]
                            wSum += 1.0
                        }
                    }
                    val score = smoothed / wSum
                    if (score > bestScore) {
                        bestScore = score
                        bestF = f
                    }
                }
                winnerFrame[tIdx] = bestF
                fusedEnergySum += tileEnergies[bestF][tIdx]
            }
        }

        // Blend winner tiles with bilinear center feathering near tile boundaries
        val outPixels = IntArray(totalPixels)
        for (y in 0 until h) {
            val ty = min(tilesY - 1, y / tileSize)
            val rowOff = y * w
            for (x in 0 until w) {
                val tx = min(tilesX - 1, x / tileSize)
                val primaryF = winnerFrame[ty * tilesX + tx]
                val p0 = alignedFrames[primaryF][rowOff + x]

                // Feather with neighbor tile winner along 2px borders to suppress seams
                val inBorderX = (x % tileSize) < 2 && tx > 0
                val inBorderY = (y % tileSize) < 2 && ty > 0
                if (inBorderX || inBorderY) {
                    val nx = if (inBorderX) tx - 1 else tx
                    val ny = if (inBorderY) ty - 1 else ty
                    val altF = winnerFrame[ny * tilesX + nx]
                    if (altF != primaryF) {
                        val p1 = alignedFrames[altF][rowOff + x]
                        val r = (((p0 ushr 16) and 0xFF) * 2 + ((p1 ushr 16) and 0xFF)) / 3
                        val g = (((p0 ushr 8) and 0xFF) * 2 + ((p1 ushr 8) and 0xFF)) / 3
                        val b = ((p0 and 0xFF) * 2 + (p1 and 0xFF)) / 3
                        outPixels[rowOff + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        continue
                    }
                }
                outPixels[rowOff + x] = p0
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)

        val gainPct = if (anchorEnergySum > 1e-6) {
            (((fusedEnergySum - anchorEnergySum) / anchorEnergySum) * 100.0).toInt().coerceIn(0, 400)
        } else 0

        return FocusStackOutput(
            bitmap = outBmp,
            frameCount = frames.size,
            alignedOffsets = offsets,
            sharpnessGainPct = gainPct,
            usedManualFocusSweep = usedManualFocusSweep
        )
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

    private fun fastLum(c: Int): Int =
        (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
}
