package com.example.visor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin YUV420 frame packer & dimension helpers (kept tested/reusable for compatibility).
 */
object VisorYuvPacker {

    fun alignTo16(value: Int, minVal: Int = 128, maxVal: Int = 720): Int {
        val clamped = value.coerceIn(minVal, maxVal)
        val aligned = ((clamped + 8) / 16) * 16
        return aligned.coerceIn(minVal, maxVal)
    }

    fun computeTargetDimensions(srcWidth: Int, srcHeight: Int, maxLongSide: Int = 640): Pair<Int, Int> {
        val w = srcWidth.coerceAtLeast(16)
        val h = srcHeight.coerceAtLeast(16)
        val scale = if (max(w, h) > maxLongSide) maxLongSide.toFloat() / max(w, h).toFloat() else 1f
        val tw = alignTo16((w * scale).roundToInt(), minVal = 128, maxVal = maxLongSide)
        val th = alignTo16((h * scale).roundToInt(), minVal = 128, maxVal = maxLongSide)
        return tw to th
    }

    /**
     * Converts upright ARGB_8888 [argb] into tightly-packed NV12 (YUV420SemiPlanar: Y, then interleaved UV).
     */
    fun argbToYuv420SemiPlanar(argb: IntArray, width: Int, height: Int, outYuv: ByteArray) {
        val frameSize = width * height
        val expected = frameSize + (frameSize / 2)
        if (argb.size < frameSize || outYuv.size < expected) return

        var yIdx = 0
        var uvIdx = frameSize
        for (j in 0 until height) {
            val rowOff = j * width
            val isEvenRow = (j and 1) == 0
            for (i in 0 until width) {
                val c = argb[rowOff + i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                outYuv[yIdx++] = yVal.toByte()

                if (isEvenRow && (i and 1) == 0 && uvIdx + 1 < outYuv.size) {
                    val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    outYuv[uvIdx++] = uVal.toByte()
                    outYuv[uvIdx++] = vVal.toByte()
                }
            }
        }
    }

    /**
     * Converts upright ARGB_8888 [argb] into tightly-packed I420 (YUV420Planar: Y, then U, then V).
     */
    fun argbToYuv420Planar(argb: IntArray, width: Int, height: Int, outYuv: ByteArray) {
        val frameSize = width * height
        val quarter = frameSize / 4
        val expected = frameSize + quarter * 2
        if (argb.size < frameSize || outYuv.size < expected) return

        var yIdx = 0
        var uIdx = frameSize
        var vIdx = frameSize + quarter
        for (j in 0 until height) {
            val rowOff = j * width
            val isEvenRow = (j and 1) == 0
            for (i in 0 until width) {
                val c = argb[rowOff + i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                outYuv[yIdx++] = yVal.toByte()

                if (isEvenRow && (i and 1) == 0 && vIdx < outYuv.size) {
                    val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    outYuv[uIdx++] = uVal.toByte()
                    outYuv[vIdx++] = vVal.toByte()
                }
            }
        }
    }

    fun writeArgbToCodecImage(argb: IntArray, width: Int, height: Int, image: android.media.Image) {
        val planes = image.planes
        if (planes.size < 3) return
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        for (j in 0 until height) {
            val rowOff = j * width
            val isEvenRow = (j and 1) == 0
            val yBase = j * yRowStride
            val uvRow = (j ushr 1)
            val uBase = uvRow * uRowStride
            val vBase = uvRow * vRowStride

            for (i in 0 until width) {
                val c = argb[rowOff + i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val yPos = yBase + i * yPixStride
                if (yPos < yBuf.limit()) yBuf.put(yPos, yVal.toByte())

                if (isEvenRow && (i and 1) == 0) {
                    val uvCol = i ushr 1
                    val uPos = uBase + uvCol * uPixStride
                    val vPos = vBase + uvCol * vPixStride
                    val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    if (uPos < uBuf.limit()) uBuf.put(uPos, uVal.toByte())
                    if (vPos < vBuf.limit()) vBuf.put(vPos, vVal.toByte())
                }
            }
        }
    }
}

/**
 * Pure state-transition guard for MediaMuxer lifecycle so stop() is never invoked twice
 * or without written samples (prevents `MPEG4Writer: Stop() called but track is not started or stopped`).
 */
internal class MuxerLifecycleGuard {
    @Volatile var started: Boolean = false
        private set
    @Volatile var stopped: Boolean = false
        private set
    @Volatile var wroteSample: Boolean = false
        private set

    fun markStarted() {
        started = true
        stopped = false
    }

    fun markSampleWritten() {
        if (started && !stopped) {
            wroteSample = true
        }
    }

    /**
     * Returns true strictly once if muxer.stop() should be called cleanly.
     */
    fun consumeStopIfNeeded(): Boolean {
        if (started && !stopped && wroteSample) {
            stopped = true
            return true
        }
        stopped = true
        return false
    }

    fun reset() {
        started = false
        stopped = false
        wroteSample = false
    }
}

/**
 * Hardware-accelerated H.264/AAC MP4 recorder for live CyberVisorEngine frames.
 * - Uses `COLOR_FormatSurface` + `MediaCodec.createInputSurface()` (`lockHardwareCanvas` with
 *   `lockCanvas` fallback) instead of slow CPU byte-by-byte YUV conversion -> 24-30 FPS target.
 * - Uses a pre-allocated two-buffer staging pool (zero per-frame LOS Bitmap allocations / GC pauses).
 * - Renders optional [PoseWireframe] skeleton directly onto the encoder Surface Canvas.
 * - Guards MediaMuxer start/stop strictly via [MuxerLifecycleGuard] to eliminate MPEG4Writer errors.
 */
class VisorVideoRecorder {

    private val isRecordingFlag = AtomicBoolean(false)
    private val isStoppingFlag = AtomicBoolean(false)

    private var encoderScope: CoroutineScope? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null

    private val frameLock = Object()
    private var stagingWriteBmp: Bitmap? = null
    private var stagingReadBmp: Bitmap? = null
    private var hasFreshFrame = false
    private var pendingPose: PoseWireframe? = null
    private val stagingPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val muxerLock = Any()
    private var muxer: MediaMuxer? = null
    private val muxerGuard = MuxerLifecycleGuard()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var audioExpected = false

    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var encWidth = 480
    private var encHeight = 640
    private var startTimeNs = 0L
    private var currentOutputFile: File? = null
    private var bytesCallback: ((Long) -> Unit)? = null

    data class ExternalSurfaceSession(
        val inputSurface: Surface,
        val width: Int,
        val height: Int
    )

    val isRecording: Boolean
        get() = isRecordingFlag.get()

    private var externalSurfaceMode = false

    @SuppressLint("MissingPermission")
    fun start(
        outputFile: File,
        sampleWidth: Int,
        sampleHeight: Int,
        enableAudio: Boolean,
        hasAudioPermission: Boolean,
        onBytesWritten: (Long) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (isRecordingFlag.get()) return false

        return try {
            val (tw, th) = VisorYuvPacker.computeTargetDimensions(sampleWidth, sampleHeight, maxLongSide = 640)
            encWidth = tw
            encHeight = th

            // Pre-allocate reusable 2-slot ARGB_8888 staging pool once (eliminates LOS GC pauses)
            synchronized(frameLock) {
                stagingWriteBmp = Bitmap.createBitmap(encWidth, encHeight, Bitmap.Config.ARGB_8888)
                stagingReadBmp = Bitmap.createBitmap(encWidth, encHeight, Bitmap.Config.ARGB_8888)
                hasFreshFrame = false
                pendingPose = null
            }

            externalSurfaceMode = false
            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vCodec.createInputSurface()
            vCodec.start()
            videoCodec = vCodec
            inputSurface = surface

            // Optional AAC audio track (gracefully degrades to video-only if mic busy/denied)
            var aRecord: AudioRecord? = null
            var aCodec: MediaCodec? = null
            var useAudio = false
            if (enableAudio && hasAudioPermission) {
                try {
                    val sampleRate = 44100
                    val minBuf = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(2048)
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
                        }
                        ac.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        ac.start()
                        rec.startRecording()
                        aRecord = rec
                        aCodec = ac
                        useAudio = true
                    } else {
                        rec.release()
                    }
                } catch (_: Exception) {
                    try { aRecord?.release() } catch (_: Exception) {}
                    try { aCodec?.release() } catch (_: Exception) {}
                    aRecord = null
                    aCodec = null
                    useAudio = false
                }
            }

            audioRecord = aRecord
            audioCodec = aCodec
            audioExpected = useAudio

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerGuard.reset()
            videoTrackIndex = -1
            audioTrackIndex = -1
            currentOutputFile = outputFile
            bytesCallback = onBytesWritten
            startTimeNs = System.nanoTime()

            isStoppingFlag.set(false)
            isRecordingFlag.set(true)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            encoderScope = scope

            videoJob = scope.launch {
                runSurfaceVideoEncodeLoop(vCodec, surface, onError)
            }
            if (useAudio && aCodec != null && aRecord != null) {
                audioJob = scope.launch {
                    runAudioEncodeLoop(aCodec, aRecord)
                }
            }
            true
        } catch (e: Exception) {
            cleanupResources()
            isRecordingFlag.set(false)
            onError(e.localizedMessage ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * Starts hardware H.264/AAC recording where frames are pushed directly from an OpenGL ES
     * producer ([GlVisorDualRenderer]) into [ExternalSurfaceSession.inputSurface] at 25-30 FPS
     * with zero CPU Bitmap allocations.
     */
    @SuppressLint("MissingPermission")
    fun startForExternalSurface(
        outputFile: File,
        sampleWidth: Int,
        sampleHeight: Int,
        enableAudio: Boolean,
        hasAudioPermission: Boolean,
        onBytesWritten: (Long) -> Unit,
        onError: (String) -> Unit
    ): ExternalSurfaceSession? {
        if (isRecordingFlag.get()) return null

        return try {
            val (tw, th) = VisorYuvPacker.computeTargetDimensions(sampleWidth, sampleHeight, maxLongSide = 960)
            encWidth = tw
            encHeight = th
            externalSurfaceMode = true

            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vCodec.createInputSurface()
            vCodec.start()
            videoCodec = vCodec
            inputSurface = surface

            var aRecord: AudioRecord? = null
            var aCodec: MediaCodec? = null
            var useAudio = false
            if (enableAudio && hasAudioPermission) {
                try {
                    val sampleRate = 44100
                    val minBuf = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(2048)
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
                        }
                        ac.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        ac.start()
                        rec.startRecording()
                        aRecord = rec
                        aCodec = ac
                        useAudio = true
                    } else {
                        rec.release()
                    }
                } catch (_: Exception) {
                    try { aRecord?.release() } catch (_: Exception) {}
                    try { aCodec?.release() } catch (_: Exception) {}
                    aRecord = null
                    aCodec = null
                    useAudio = false
                }
            }

            audioRecord = aRecord
            audioCodec = aCodec
            audioExpected = useAudio

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerGuard.reset()
            videoTrackIndex = -1
            audioTrackIndex = -1
            currentOutputFile = outputFile
            bytesCallback = onBytesWritten
            startTimeNs = System.nanoTime()

            isStoppingFlag.set(false)
            isRecordingFlag.set(true)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            encoderScope = scope

            videoJob = scope.launch {
                runExternalSurfaceDrainLoop(vCodec, onError)
            }
            if (useAudio && aCodec != null && aRecord != null) {
                audioJob = scope.launch {
                    runAudioEncodeLoop(aCodec, aRecord)
                }
            }
            ExternalSurfaceSession(surface, encWidth, encHeight)
        } catch (e: Exception) {
            cleanupResources()
            isRecordingFlag.set(false)
            onError(e.localizedMessage ?: e.javaClass.simpleName)
            null
        }
    }

    fun notifyExternalFramePushed() {
        if (!isRecordingFlag.get() || isStoppingFlag.get()) return
        synchronized(frameLock) {
            hasFreshFrame = true
            frameLock.notifyAll()
        }
    }

    /**
     * Copies [sourceBitmap] into the pre-allocated staging bitmap (0 heap allocations)
     * and wakes up the surface encoding thread immediately.
     */
    fun enqueueFrame(sourceBitmap: Bitmap, poseWireframe: PoseWireframe? = null) {
        if (!isRecordingFlag.get() || isStoppingFlag.get() || externalSurfaceMode || sourceBitmap.isRecycled) return
        try {
            synchronized(frameLock) {
                val dst = stagingWriteBmp
                if (dst != null && !dst.isRecycled) {
                    val canvas = Canvas(dst)
                    val srcRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
                    val dstRect = Rect(0, 0, dst.width, dst.height)
                    canvas.drawBitmap(sourceBitmap, srcRect, dstRect, stagingPaint)
                    pendingPose = poseWireframe
                    hasFreshFrame = true
                    frameLock.notifyAll()
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun stopAndFinalize(onBeforeSignalEos: (() -> Unit)? = null): File? = withContext(Dispatchers.Default) {
        if (!isRecordingFlag.get()) return@withContext null
        try { onBeforeSignalEos?.invoke() } catch (_: Exception) {}
        isStoppingFlag.set(true)
        synchronized(frameLock) {
            frameLock.notifyAll()
        }

        try {
            videoJob?.join()
        } catch (_: Exception) {}
        try {
            audioJob?.join()
        } catch (_: Exception) {}

        val outFile = currentOutputFile
        cleanupResources()
        isRecordingFlag.set(false)
        isStoppingFlag.set(false)
        externalSurfaceMode = false

        if (outFile != null && outFile.exists() && outFile.length() > 0L) outFile else null
    }

    private fun runExternalSurfaceDrainLoop(
        codec: MediaCodec,
        onError: (String) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        var eosSignaled = false
        var eosDrained = false

        try {
            while (!eosDrained) {
                synchronized(frameLock) {
                    if (!hasFreshFrame && !isStoppingFlag.get()) {
                        try { frameLock.wait(25L) } catch (_: InterruptedException) {}
                    }
                    hasFreshFrame = false
                }
                if (isStoppingFlag.get() && !eosSignaled) {
                    eosSignaled = true
                    try { codec.signalEndOfInputStream() } catch (_: Exception) { eosDrained = true }
                }
                if (drainEncoder(codec, info, isVideo = true, waitForEos = eosSignaled)) {
                    eosDrained = true
                } else if (eosSignaled && !muxerGuard.wroteSample) {
                    eosDrained = true
                }
            }
        } catch (e: Exception) {
            isStoppingFlag.set(true)
            onError(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            isStoppingFlag.set(true)
        }
    }

    private fun runSurfaceVideoEncodeLoop(
        codec: MediaCodec,
        surface: Surface,
        onError: (String) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        val dstRect = Rect(0, 0, encWidth, encHeight)
        var eosSignaled = false
        var eosDrained = false

        try {
            while (!eosDrained) {
                var bitmapToDraw: Bitmap? = null
                var poseToDraw: PoseWireframe? = null

                synchronized(frameLock) {
                    if (!hasFreshFrame && !isStoppingFlag.get()) {
                        try { frameLock.wait(25L) } catch (_: InterruptedException) {}
                    }
                    if (hasFreshFrame) {
                        val tmp = stagingWriteBmp
                        stagingWriteBmp = stagingReadBmp
                        stagingReadBmp = tmp
                        bitmapToDraw = stagingReadBmp
                        poseToDraw = pendingPose
                        hasFreshFrame = false
                    }
                }

                if (bitmapToDraw != null && !bitmapToDraw!!.isRecycled) {
                    val canvas = try {
                        surface.lockHardwareCanvas()
                    } catch (_: Throwable) {
                        try { surface.lockCanvas(null) } catch (_: Throwable) { null }
                    }
                    if (canvas != null) {
                        try {
                            canvas.drawBitmap(bitmapToDraw!!, null, dstRect, stagingPaint)
                            poseToDraw?.let { wf ->
                                drawPoseOnSurfaceCanvas(canvas, wf, encWidth.toFloat(), encHeight.toFloat())
                            }
                        } finally {
                            try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                        }
                    }
                } else if (isStoppingFlag.get() && !eosSignaled) {
                    eosSignaled = true
                    try { codec.signalEndOfInputStream() } catch (_: Exception) { eosDrained = true }
                }

                if (drainEncoder(codec, info, isVideo = true, waitForEos = eosSignaled)) {
                    eosDrained = true
                } else if (eosSignaled) {
                    // Guard against encoder never emitting EOS if zero frames were pushed
                    if (!muxerGuard.wroteSample) {
                        eosDrained = true
                    }
                }
            }
        } catch (e: Exception) {
            isStoppingFlag.set(true)
            onError(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            isStoppingFlag.set(true)
        }
    }

    private fun drawPoseOnSurfaceCanvas(canvas: Canvas, wf: PoseWireframe, w: Float, h: Float) {
        for ((a, b) in wf.bones) {
            canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, bonePaint)
        }
        val r = max(3.5f, w * 0.008f)
        for (j in wf.joints) {
            canvas.drawCircle(j.x * w, j.y * h, r, jointPaint)
        }
    }

    private fun runAudioEncodeLoop(codec: MediaCodec, record: AudioRecord) {
        val pcmChunk = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        var eosDrained = false
        var lastPtsUs = -1L

        try {
            while (!eosDrained) {
                if (!eosSent) {
                    val stopping = isStoppingFlag.get()
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inputIdx)
                        inBuf?.clear()
                        if (stopping) {
                            val ptsUs = max(0L, (System.nanoTime() - startTimeNs) / 1000L)
                            codec.queueInputBuffer(inputIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val maxRead = min(pcmChunk.size, inBuf?.remaining() ?: pcmChunk.size)
                            val readBytes = record.read(pcmChunk, 0, maxRead)
                            if (readBytes > 0 && inBuf != null) {
                                inBuf.put(pcmChunk, 0, readBytes)
                                var ptsUs = max(0L, (System.nanoTime() - startTimeNs) / 1000L)
                                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 23_220L
                                lastPtsUs = ptsUs
                                codec.queueInputBuffer(inputIdx, 0, readBytes, ptsUs, 0)
                            } else {
                                codec.queueInputBuffer(inputIdx, 0, 0, max(0L, (System.nanoTime() - startTimeNs) / 1000L), 0)
                            }
                        }
                    }
                }
                if (drainEncoder(codec, info, isVideo = false, waitForEos = eosSent)) {
                    eosDrained = true
                } else if (eosSent && !muxerGuard.started) {
                    eosDrained = true
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Drains available encoded buffers. Returns true once BUFFER_FLAG_END_OF_STREAM is observed.
     */
    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        isVideo: Boolean,
        waitForEos: Boolean
    ): Boolean {
        val timeoutUs = if (waitForEos) 10_000L else 0L
        var safetyLoops = 0
        while (safetyLoops++ < 64) {
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEos) return false
                    if (safetyLoops > 14) return false
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        val m = muxer ?: return false
                        if (!muxerGuard.started) {
                            val newFormat = codec.outputFormat
                            if (isVideo && videoTrackIndex < 0) {
                                videoTrackIndex = m.addTrack(newFormat)
                            } else if (!isVideo && audioTrackIndex < 0) {
                                audioTrackIndex = m.addTrack(newFormat)
                            }
                            val ready = videoTrackIndex >= 0 && (!audioExpected || audioTrackIndex >= 0)
                            if (ready) {
                                m.start()
                                muxerGuard.markStarted()
                                try {
                                    videoCodec?.setParameters(Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    })
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                outIdx >= 0 -> {
                    val outBuf: ByteBuffer? = codec.getOutputBuffer(outIdx)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (outBuf != null && info.size > 0 && !isConfig) {
                        synchronized(muxerLock) {
                            val m = muxer
                            val trackIdx = if (isVideo) videoTrackIndex else audioTrackIndex
                            if (m != null && muxerGuard.started && !muxerGuard.stopped && trackIdx >= 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                m.writeSampleData(trackIdx, outBuf, info)
                                muxerGuard.markSampleWritten()
                                if (isVideo) {
                                    currentOutputFile?.let { bytesCallback?.invoke(it.length()) }
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun cleanupResources() {
        synchronized(frameLock) {
            stagingWriteBmp?.let { if (!it.isRecycled) it.recycle() }
            stagingReadBmp?.let { if (!it.isRecycled) it.recycle() }
            stagingWriteBmp = null
            stagingReadBmp = null
            hasFreshFrame = false
            pendingPose = null
        }

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null

        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null

        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null

        synchronized(muxerLock) {
            if (muxerGuard.consumeStopIfNeeded()) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            videoTrackIndex = -1
            audioTrackIndex = -1
        }
        try { encoderScope?.cancel() } catch (_: Exception) {}
        encoderScope = null
    }
}
