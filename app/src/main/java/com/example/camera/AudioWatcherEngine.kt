package com.example.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AudioWatcherEngine (P3):
 * Real offline PCM-16 acoustic watcher using Android AudioRecord (16 kHz mono):
 * - Measures real-time RMS dBFS, Peak dBFS, Crest Factor, and Zero-Crossing Rate.
 * - Tracks adaptive ambient noise floor via EMA and fires [onAcousticTrigger] with cooldown
 *   when sound crosses [thresholdDbfs] or shows a sharp transient spike.
 * - Immediately releases AudioRecord when stopped so Android 15 mic privacy dot clears.
 */
class AudioWatcherEngine(
    private val context: Context,
    private val onTelemetry: (AcousticTelemetry) -> Unit,
    private val onAcousticTrigger: (AcousticTelemetry, String) -> Unit,
    private val onStatusError: (String) -> Unit = {}
) {
    data class AcousticTelemetry(
        val rmsDbfs: Double = -96.0,
        val peakDbfs: Double = -96.0,
        val noiseFloorDbfs: Double = -60.0,
        val crestFactorDb: Double = 0.0,
        val zeroCrossingRate: Float = 0f,
        val isTransientSpike: Boolean = false,
        val isAboveThreshold: Boolean = false,
        val waveformBars: List<Float> = List(16) { 0f }
    )

    @Volatile
    var thresholdDbfs: Double = -26.0
        set(value) {
            field = value.coerceIn(-55.0, -6.0)
        }

    @Volatile
    var isRunning: Boolean = false
        private set

    private var watcherJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var lastTriggerMs: Long = 0L

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            onStatusError("Нет разрешения RECORD_AUDIO для Акустик-сторожа")
            return
        }

        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onStatusError("Аудио-буфер HAL недоступен")
            return
        }

        val frameSamples = 1600 // 100 ms window at 16 kHz
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, frameSamples * 4)
            )
        } catch (e: SecurityException) {
            onStatusError("Отказ микрофона: ${e.message}")
            return
        } catch (e: Exception) {
            onStatusError("Сбой AudioRecord: ${e.message}")
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onStatusError("Не удалось инициализировать микрофон")
            return
        }

        audioRecord = rec
        isRunning = true
        var ambientFloor = -58.0

        watcherJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(frameSamples)
            try {
                rec.startRecording()
                while (isActive && isRunning) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val telemetry = analyzePcmFrame(
                            samples = buffer,
                            count = read,
                            noiseFloorDbfs = ambientFloor,
                            thresholdDbfs = thresholdDbfs
                        )
                        // Slow EMA update of background floor when not spiking
                        if (!telemetry.isAboveThreshold) {
                            ambientFloor = ambientFloor * 0.92 + telemetry.rmsDbfs * 0.08
                        }
                        onTelemetry(telemetry)

                        val now = System.currentTimeMillis()
                        if ((telemetry.isAboveThreshold || (telemetry.isTransientSpike && telemetry.rmsDbfs > thresholdDbfs - 4.0)) &&
                            (now - lastTriggerMs >= 3500L)
                        ) {
                            lastTriggerMs = now
                            val reason = if (telemetry.isTransientSpike) {
                                "Импульс звука ${String.format("%.1f", telemetry.peakDbfs)} dBFS"
                            } else {
                                "Шум ${String.format("%.1f", telemetry.rmsDbfs)} dBFS > ${thresholdDbfs.toInt()}"
                            }
                            onAcousticTrigger(telemetry, reason)
                        }
                    }
                }
            } catch (e: Exception) {
                onStatusError("Остановка аудио-сторожа: ${e.message}")
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() {
        isRunning = false
        watcherJob?.cancel()
        watcherJob = null
        stopInternal()
    }

    @Synchronized
    private fun stopInternal() {
        isRunning = false
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: Exception) {}
            try {
                rec.release()
            } catch (_: Exception) {}
        }
    }

    companion object {
        /**
         * Pure DSP analyzer over 16-bit signed PCM samples (safe for unit testing without mic hardware).
         */
        fun analyzePcmFrame(
            samples: ShortArray,
            count: Int = samples.size,
            noiseFloorDbfs: Double = -58.0,
            thresholdDbfs: Double = -26.0
        ): AcousticTelemetry {
            val n = min(count, samples.size)
            if (n <= 0) return AcousticTelemetry()

            var sumSq = 0.0
            var maxAbs = 0
            var zcrHits = 0
            var prevSign = samples[0] >= 0

            val barsCount = 16
            val barBins = FloatArray(barsCount)
            val binSize = max(1, n / barsCount)

            for (i in 0 until n) {
                val s = samples[i].toInt()
                val absS = abs(s)
                sumSq += s.toDouble() * s.toDouble()
                if (absS > maxAbs) maxAbs = absS
                val curSign = s >= 0
                if (i > 0 && curSign != prevSign) zcrHits++
                prevSign = curSign

                val binIdx = min(barsCount - 1, i / binSize)
                val norm = absS / 32768f
                if (norm > barBins[binIdx]) barBins[binIdx] = norm
            }

            val rms = sqrt(sumSq / n.toDouble())
            val rmsDbfs = if (rms > 0.5) (20.0 * log10(rms / 32768.0)).coerceIn(-96.0, 0.0) else -96.0
            val peakDbfs = if (maxAbs > 0) (20.0 * log10(maxAbs.toDouble() / 32768.0)).coerceIn(-96.0, 0.0) else -96.0
            val crestDb = (peakDbfs - rmsDbfs).coerceAtLeast(0.0)
            val zcr = (zcrHits.toFloat() / max(1, n - 1).toFloat()).coerceIn(0f, 1f)

            val isTransient = crestDb >= 10.5 && peakDbfs > (noiseFloorDbfs + 14.0)
            val above = rmsDbfs >= thresholdDbfs

            // Visually boost low voice/room levels slightly via sqrt mapping for smooth meter bars
            val barsList = barBins.map { v -> sqrt(v.coerceIn(0f, 1f)) }

            return AcousticTelemetry(
                rmsDbfs = rmsDbfs,
                peakDbfs = peakDbfs,
                noiseFloorDbfs = noiseFloorDbfs,
                crestFactorDb = crestDb,
                zeroCrossingRate = zcr,
                isTransientSpike = isTransient,
                isAboveThreshold = above,
                waveformBars = barsList
            )
        }
    }
}
