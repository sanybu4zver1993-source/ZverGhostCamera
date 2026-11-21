package com.example.visor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FftProcessor:
 * Zero-dependency pure-Kotlin Radix-2 Cooley-Tukey FFT + Hann window + uniform linear resampler +
 * parabolic spectral peak estimator. Reusable across Bio-Visor rPPG and Scientific Acoustic FFT.
 */
object FftProcessor {

    data class SpectralPeak(
        val peakFreqHz: Double,
        val peakPower: Double,
        val meanBandPower: Double,
        val snrRatio: Double,
        val spectrumPower: DoubleArray
    )

    fun nextPowerOfTwo(n: Int): Int {
        if (n <= 1) return 1
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * Resamples irregularly timestamped signal [(timesSec[i], values[i])] onto a uniform
     * grid at [targetSampleRateHz] (filling up to [outBuf.size] samples anchored to the latest window).
     * Returns number of valid samples populated in [outBuf].
     */
    fun resampleLinear(
        timesSec: DoubleArray,
        values: DoubleArray,
        count: Int,
        targetSampleRateHz: Double,
        outBuf: DoubleArray
    ): Int {
        if (count < 2 || outBuf.isEmpty() || targetSampleRateHz <= 0.0) return 0
        val tStart = timesSec[0]
        val tEnd = timesSec[count - 1]
        val duration = tEnd - tStart
        if (duration <= 0.05) return 0

        val dt = 1.0 / targetSampleRateHz
        val availableSamples = (duration * targetSampleRateHz).toInt() + 1
        val outCount = min(outBuf.size, availableSamples)
        if (outCount < 2) return 0

        // Align output end cleanly with tEnd
        val gridStart = tEnd - (outCount - 1) * dt
        var srcIdx = 0
        for (k in 0 until outCount) {
            val t = gridStart + k * dt
            if (t <= tStart) {
                outBuf[k] = values[0]
                continue
            }
            if (t >= tEnd) {
                outBuf[k] = values[count - 1]
                continue
            }
            while (srcIdx + 1 < count && timesSec[srcIdx + 1] < t) {
                srcIdx++
            }
            val t0 = timesSec[srcIdx]
            val t1 = timesSec[min(count - 1, srcIdx + 1)]
            val span = t1 - t0
            val frac = if (span > 1e-9) ((t - t0) / span).coerceIn(0.0, 1.0) else 0.0
            val v0 = values[srcIdx]
            val v1 = values[min(count - 1, srcIdx + 1)]
            outBuf[k] = v0 + (v1 - v0) * frac
        }
        return outCount
    }

    /**
     * Subtracts DC mean and multiplies active prefix [0 until length] by a Hann window.
     */
    fun applyHannWindow(buf: DoubleArray, length: Int = buf.size) {
        if (length <= 1) return
        var mean = 0.0
        for (i in 0 until length) mean += buf[i]
        mean /= length.toDouble()
        val denom = (length - 1).toDouble()
        for (i in 0 until length) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / denom))
            buf[i] = (buf[i] - mean) * w
        }
        if (length < buf.size) {
            for (i in length until buf.size) buf[i] = 0.0
        }
    }

    /**
     * In-place Radix-2 Cooley-Tukey FFT.
     * [re] and [im] must have equal power-of-two length.
     */
    fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "Real and imaginary arrays must match size" }
        if (n <= 1) return
        require((n and (n - 1)) == 0) { "FFT size must be a power of 2 (was $n)" }

        // Bit-reversal permutation
        val bits = log2(n.toDouble()).toInt()
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                val tr = re[i]
                val ti = im[i]
                re[i] = re[j]
                im[i] = im[j]
                re[j] = tr
                im[j] = ti
            }
        }

        // Iterative butterfly stages
        var halfSize = 1
        while (halfSize < n) {
            val step = halfSize shl 1
            val theta = -PI / halfSize.toDouble()
            val wStepRe = cos(theta)
            val wStepIm = sin(theta)

            var k = 0
            while (k < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (j in 0 until halfSize) {
                    val evenIdx = k + j
                    val oddIdx = evenIdx + halfSize
                    val oddRe = re[oddIdx]
                    val oddIm = im[oddIdx]
                    val tRe = wRe * oddRe - wIm * oddIm
                    val tIm = wRe * oddIm + wIm * oddRe

                    val eRe = re[evenIdx]
                    val eIm = im[evenIdx]
                    re[evenIdx] = eRe + tRe
                    im[evenIdx] = eIm + tIm
                    re[oddIdx] = eRe - tRe
                    im[oddIdx] = eIm - tIm

                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                k += step
            }
            halfSize = step
        }
    }

    /**
     * Computes single-sided power spectrum and finds dominant frequency within [minFreqHz..maxFreqHz]
     * using 3-point parabolic interpolation around the peak bin for sub-bin accuracy.
     */
    fun findPeakFrequencyWithSnr(
        signal: DoubleArray,
        validLength: Int,
        sampleRateHz: Double,
        minFreqHz: Double,
        maxFreqHz: Double,
        fftSize: Int = max(64, nextPowerOfTwo(validLength))
    ): SpectralPeak {
        val n = max(16, nextPowerOfTwo(fftSize))
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val copyLen = min(validLength, min(signal.size, n))
        if (copyLen <= 2 || sampleRateHz <= 0.0) {
            return SpectralPeak(0.0, 0.0, 0.0, 0.0, DoubleArray(n / 2))
        }
        System.arraycopy(signal, 0, re, 0, copyLen)
        applyHannWindow(re, copyLen)
        fftInPlace(re, im)

        val half = n / 2
        val power = DoubleArray(half)
        for (i in 0 until half) {
            val r = re[i]
            val m = im[i]
            power[i] = r * r + m * m
        }

        val binHz = sampleRateHz / n.toDouble()
        val kMin = max(1, (minFreqHz / binHz).toInt())
        val kMax = min(half - 2, kotlin.math.ceil(maxFreqHz / binHz).toInt())
        if (kMax <= kMin) {
            return SpectralPeak(0.0, 0.0, 0.0, 0.0, power)
        }

        var bestBin = kMin
        var bestPow = -1.0
        var bandSum = 0.0
        var bandBins = 0
        for (k in kMin..kMax) {
            val p = power[k]
            bandSum += p
            bandBins++
            if (p > bestPow) {
                bestPow = p
                bestBin = k
            }
        }

        // 3-point parabolic interpolation on sqrt(power) magnitude for sub-bin peak estimate
        var deltaBin = 0.0
        if (bestBin in (kMin + 1) until kMax) {
            val mL = sqrt(power[bestBin - 1])
            val mC = sqrt(power[bestBin])
            val mR = sqrt(power[bestBin + 1])
            val denom = mL - 2.0 * mC + mR
            if (kotlin.math.abs(denom) > 1e-12) {
                deltaBin = (0.5 * (mL - mR) / denom).coerceIn(-0.5, 0.5)
            }
        }

        val peakFreqHz = ((bestBin + deltaBin) * binHz).coerceIn(minFreqHz, maxFreqHz)
        // Compute local harmonic/band SNR excluding +/-2 bins right around the peak
        var noiseSum = 0.0
        var noiseBins = 0
        for (k in kMin..kMax) {
            if (kotlin.math.abs(k - bestBin) > 2) {
                noiseSum += power[k]
                noiseBins++
            }
        }
        val meanNoise = if (noiseBins > 0) noiseSum / noiseBins else (bandSum / max(1, bandBins))
        val meanBand = bandSum / max(1, bandBins)
        val snr = if (meanNoise > 1e-12) bestPow / meanNoise else 0.0

        return SpectralPeak(
            peakFreqHz = peakFreqHz,
            peakPower = bestPow.coerceAtLeast(0.0),
            meanBandPower = meanBand,
            snrRatio = snr,
            spectrumPower = power
        )
    }
}
