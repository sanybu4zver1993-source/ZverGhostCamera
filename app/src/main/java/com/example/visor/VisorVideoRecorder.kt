package com.example.visor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
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
 * Pure Kotlin YUV420 frame packer for ARGB_8888 Bitmaps -> H.264 MediaCodec input buffers.
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
 * Hardware-accelerated H.264/AAC MP4 recorder for live CyberVisorEngine frames.
 * Captures the actual filtered Bio-Visor bitmap stream directly into an unencrypted staging MP4
 * (which the caller then strips via Mp4Sanitizer and encrypts into GhostCryptoVault).
 */
class VisorVideoRecorder {

    private val isRecordingFlag = AtomicBoolean(false)
    private val isStoppingFlag = AtomicBoolean(false)

    private var encoderScope: CoroutineScope? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null

    private val frameLock = Any()
    private var pendingBitmap: Bitmap? = null

    private val muxerLock = Any()
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var audioExpected = false

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var encWidth = 480
    private var encHeight = 640
    private var selectedColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var startTimeNs = 0L
    private var currentOutputFile: File? = null
    private var bytesCallback: ((Long) -> Unit)? = null

    val isRecording: Boolean
        get() = isRecordingFlag.get()

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

            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = vCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            selectedColorFormat = chooseSupportedColorFormat(caps.colorFormats)

            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vCodec.start()
            videoCodec = vCodec

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
            muxerStarted = false
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
                runVideoEncodeLoop(vCodec, onError)
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
     * Enqueues a snapshot copy of the active Bio-Visor frame without blocking the CameraX analyzer thread.
     */
    fun enqueueFrame(sourceBitmap: Bitmap) {
        if (!isRecordingFlag.get() || isStoppingFlag.get() || sourceBitmap.isRecycled) return
        try {
            val prepared = if (sourceBitmap.width == encWidth && sourceBitmap.height == encHeight) {
                sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(sourceBitmap, encWidth, encHeight, true)
            }
            synchronized(frameLock) {
                val old = pendingBitmap
                pendingBitmap = prepared
                if (old != null && !old.isRecycled) {
                    old.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun stopAndFinalize(): File? = withContext(Dispatchers.Default) {
        if (!isRecordingFlag.get()) return@withContext null
        isStoppingFlag.set(true)

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

        if (outFile != null && outFile.exists() && outFile.length() > 0L) outFile else null
    }

    private fun chooseSupportedColorFormat(formats: IntArray): Int {
        return when {
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    private fun runVideoEncodeLoop(codec: MediaCodec, onError: (String) -> Unit) {
        val argb = IntArray(encWidth * encHeight)
        val yuvSize = (encWidth * encHeight * 3) / 2
        val yuvBuf = ByteArray(yuvSize)
        val info = MediaCodec.BufferInfo()
        var lastPtsUs = -1L
        var eosSent = false

        try {
            while (!eosSent) {
                var frameBmp: Bitmap? = null
                synchronized(frameLock) {
                    frameBmp = pendingBitmap
                    pendingBitmap = null
                }

                val stopping = isStoppingFlag.get()
                if (frameBmp != null) {
                    try {
                        frameBmp!!.getPixels(argb, 0, encWidth, 0, 0, encWidth, encHeight)
                    } finally {
                        if (!frameBmp!!.isRecycled) frameBmp!!.recycle()
                    }
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        var ptsUs = max(0L, (System.nanoTime() - startTimeNs) / 1000L)
                        if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 33_333L
                        lastPtsUs = ptsUs

                        val inputImage = if (selectedColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                            try { codec.getInputImage(inputIdx) } catch (_: Exception) { null }
                        } else null

                        if (inputImage != null) {
                            VisorYuvPacker.writeArgbToCodecImage(argb, encWidth, encHeight, inputImage)
                            codec.queueInputBuffer(inputIdx, 0, yuvSize, ptsUs, 0)
                        } else {
                            if (selectedColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                                VisorYuvPacker.argbToYuv420Planar(argb, encWidth, encHeight, yuvBuf)
                            } else {
                                VisorYuvPacker.argbToYuv420SemiPlanar(argb, encWidth, encHeight, yuvBuf)
                            }
                            val inBuf = codec.getInputBuffer(inputIdx)
                            inBuf?.clear()
                            val writeLen = min(yuvBuf.size, inBuf?.remaining() ?: 0)
                            inBuf?.put(yuvBuf, 0, writeLen)
                            codec.queueInputBuffer(inputIdx, 0, writeLen, ptsUs, 0)
                        }
                    }
                } else if (stopping) {
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val ptsUs = max(0L, (System.nanoTime() - startTimeNs) / 1000L)
                        codec.queueInputBuffer(inputIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                } else {
                    Thread.sleep(10)
                }

                drainEncoder(codec, info, isVideo = true, waitForEos = eosSent)
            }
        } catch (e: Exception) {
            isStoppingFlag.set(true)
            onError(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            isStoppingFlag.set(true)
        }
    }

    private fun runAudioEncodeLoop(codec: MediaCodec, record: AudioRecord) {
        val pcmChunk = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        var lastPtsUs = -1L

        try {
            while (!eosSent) {
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
                drainEncoder(codec, info, isVideo = false, waitForEos = eosSent)
            }
        } catch (_: Exception) {}
    }

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        isVideo: Boolean,
        waitForEos: Boolean
    ) {
        val timeoutUs = if (waitForEos) 10_000L else 0L
        var safetyLoops = 0
        while (safetyLoops++ < 64) {
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEos) break
                    if (safetyLoops > 12) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        val m = muxer ?: return
                        if (!muxerStarted) {
                            val newFormat = codec.outputFormat
                            if (isVideo && videoTrackIndex < 0) {
                                videoTrackIndex = m.addTrack(newFormat)
                            } else if (!isVideo && audioTrackIndex < 0) {
                                audioTrackIndex = m.addTrack(newFormat)
                            }
                            val ready = videoTrackIndex >= 0 && (!audioExpected || audioTrackIndex >= 0)
                            if (ready) {
                                m.start()
                                muxerStarted = true
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
                            if (m != null && muxerStarted && trackIdx >= 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                m.writeSampleData(trackIdx, outBuf, info)
                                if (isVideo) {
                                    currentOutputFile?.let { bytesCallback?.invoke(it.length()) }
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun cleanupResources() {
        synchronized(frameLock) {
            pendingBitmap?.let { if (!it.isRecycled) it.recycle() }
            pendingBitmap = null
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

        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
        }
        try { encoderScope?.cancel() } catch (_: Exception) {}
        encoderScope = null
    }
}
