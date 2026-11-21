package com.example.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.example.visor.MuxerLifecycleGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * TimeLapseRecorder (Part B):
 * Hardware-accelerated H.264 Time-Lapse intervalometer recorder for Redmi 13C (MT6769Z):
 * - Encodes periodic frames onto a `MediaCodec` Surface (`COLOR_FormatSurface`) at 30 fps playback.
 * - Uses monotonic synthetic presentation timestamps (`frameIndex * 33_333L us`) for smooth playback.
 * - Thread-safe `MuxerLifecycleGuard` prevents MediaMuxer premature-stop IllegalStateException.
 */
class TimeLapseRecorder {

    companion object {
        val SUPPORTED_INTERVALS_MS = listOf(500L, 1000L, 2000L, 5000L, 10000L)
        private const val OUTPUT_FPS = 30
        private const val FRAME_STEP_US = 33_333L
    }

    private val running = AtomicBoolean(false)
    private val capturedCount = AtomicInteger(0)
    private val startedAtMs = AtomicLong(0L)

    @Volatile
    var isRecording: Boolean = false
        private set

    val framesCaptured: Int
        get() = capturedCount.get()

    val elapsedSeconds: Int
        get() {
            val s = startedAtMs.get()
            return if (s > 0L && isRecording) ((System.currentTimeMillis() - s) / 1000L).toInt().coerceAtLeast(0) else 0
        }

    private val encodeLock = Any()
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private val muxerGuard = MuxerLifecycleGuard()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var outputFileRef: File? = null
    private var encWidth = 720
    private var encHeight = 960

    fun start(
        outputFile: File,
        sampleWidth: Int = 720,
        sampleHeight: Int = 960
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            outputFile.parentFile?.mkdirs()
            val scale = min(1f, 960f / max(1, max(sampleWidth, sampleHeight)).toFloat())
            encWidth = max(320, ((sampleWidth * scale).toInt() and 0x7FFFFFF0))
            encHeight = max(240, ((sampleHeight * scale).toInt() and 0x7FFFFFF0))

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mc.createInputSurface()
            val mx = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            synchronized(encodeLock) {
                codec = mc
                inputSurface = surface
                muxer = mx
                videoTrackIndex = -1
                muxerGuard.reset()
                outputFileRef = outputFile
                capturedCount.set(0)
                startedAtMs.set(System.currentTimeMillis())
                mc.start()
            }
            isRecording = true
            true
        } catch (_: Throwable) {
            cleanupResources()
            running.set(false)
            isRecording = false
            false
        }
    }

    fun appendBitmapFrame(bitmap: Bitmap): Boolean {
        if (!running.get() || bitmap.isRecycled) return false
        synchronized(encodeLock) {
            val surface = inputSurface ?: return false
            val mc = codec ?: return false
            return try {
                val canvas: Canvas = surface.lockHardwareCanvas()
                try {
                    val dstRect = Rect(0, 0, encWidth, encHeight)
                    canvas.drawBitmap(bitmap, null, dstRect, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                capturedCount.incrementAndGet()
                drainEncoder(mc, endOfStream = false)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    suspend fun stopAndFinalize(): File? = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(true, false)) return@withContext null
        isRecording = false
        synchronized(encodeLock) {
            val mc = codec
            val outFile = outputFileRef
            try {
                if (mc != null) {
                    try { mc.signalEndOfInputStream() } catch (_: Throwable) {}
                    drainEncoder(mc, endOfStream = true)
                }
            } catch (_: Throwable) {
            } finally {
                cleanupResources()
            }
            val writtenFrames = capturedCount.get()
            if (writtenFrames > 0 && outFile != null && outFile.exists() && outFile.length() > 0L) {
                outFile
            } else {
                outFile?.let { try { it.delete() } catch (_: Exception) {} }
                null
            }
        }
    }

    private fun drainEncoder(mc: MediaCodec, endOfStream: Boolean) {
        val mx = muxer ?: return
        var retries = if (endOfStream) 32 else 6
        while (retries-- > 0) {
            val status = mc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 15_000L else 0L)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerGuard.isStarted) {
                        videoTrackIndex = mx.addTrack(mc.outputFormat)
                        mx.start()
                        muxerGuard.markStarted()
                    }
                }
                status >= 0 -> {
                    val outBuf = mc.getOutputBuffer(status)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (outBuf != null && bufferInfo.size > 0 && !isConfig && muxerGuard.isStarted && videoTrackIndex >= 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val frameIdx = max(0, capturedCount.get() - 1)
                        bufferInfo.presentationTimeUs = frameIdx.toLong() * FRAME_STEP_US
                        mx.writeSampleData(videoTrackIndex, outBuf, bufferInfo)
                        muxerGuard.markSampleWritten()
                    }
                    mc.releaseOutputBuffer(status, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun cleanupResources() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        if (muxerGuard.consumeStopIfNeeded()) {
            try { muxer?.stop() } catch (_: Throwable) {}
        }
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        videoTrackIndex = -1
        startedAtMs.set(0L)
    }

    fun formatIntervalLabel(intervalMs: Long): String = when {
        intervalMs < 1000L -> "${intervalMs}мс"
        else -> "${intervalMs / 1000L}с"
    }
}
