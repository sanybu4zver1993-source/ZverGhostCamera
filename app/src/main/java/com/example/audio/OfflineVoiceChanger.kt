package com.example.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 100% Offline Voice Anonymizer / Pitch-Timbre Transformer for Vault MP4 recordings.
 * - Preserves video track 1:1 (zero video re-encoding, zero quality loss).
 * - Decodes audio track -> PCM16 -> Overlap-Add (OLA) time-preserving pitch shift + optional timbre mod -> AAC re-encode.
 * - Keeps original output sample count identical to input so audio-video sync remains locked (0 ms drift).
 */
object OfflineVoiceChanger {

    enum class VoicePreset(
        val labelRu: String,
        val badgeRu: String,
        val pitchFactor: Float,
        val ringModHz: Float = 0f,
        val softClipDrive: Float = 1.0f
    ) {
        ORIGINAL("Оригинал", "ОРИГ", 1.0f),
        DEEP_ANON("Аноним (Низкий)", "АНОНИМ", 0.74f, ringModHz = 0f, softClipDrive = 1.15f),
        GRAVE_BASS("Тяжёлый бас", "БАС", 0.62f, ringModHz = 0f, softClipDrive = 1.25f),
        CYBER_ROBOT("Кибер-робот", "РОБОТ", 0.86f, ringModHz = 46f, softClipDrive = 1.40f),
        HIGH_PITCH("Высокий", "ВЫС", 1.34f, ringModHz = 0f, softClipDrive = 1.05f)
    }

    data class VoiceProcessResult(
        val success: Boolean,
        val hasAudioTrack: Boolean,
        val processedSamples: Int,
        val detailRu: String
    )

    /**
     * Pure Kotlin OLA pitch shifter + optional ring modulation on interleaved PCM-16 samples.
     * Output length strictly equals [input].size so A/V synchronization stays exact.
     */
    fun transformPcm16Samples(
        input: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        preset: VoicePreset
    ): ShortArray {
        if (input.isEmpty() || channelCount <= 0 || preset == VoicePreset.ORIGINAL) {
            return input.copyOf()
        }
        val ch = channelCount.coerceIn(1, 2)
        val totalFrames = input.size / ch
        if (totalFrames < 64) return input.copyOf()

        val sr = sampleRate.coerceIn(8000, 96000)
        val pitch = preset.pitchFactor.coerceIn(0.45f, 2.2f)

        // Windowed Overlap-Add (Hann window) to shift pitch without changing playback duration.
        // Synthesis hop stays fixed at hopOut; analysis grain reads at step `pitch` inside local frames.
        val winFrames = ((sr * 0.036f).roundToInt().coerceIn(512, 2048)) and -2
        val hopOut = winFrames / 4
        val outAccum = FloatArray(totalFrames * ch)
        val normAccum = FloatArray(totalFrames)
        val hann = FloatArray(winFrames) { i ->
            (0.5 * (1.0 - cos((2.0 * PI * i) / (winFrames - 1)))).toFloat()
        }

        var pos = 0
        while (pos < totalFrames) {
            val grainCenterIn = pos.toFloat()
            val halfWin = winFrames / 2f
            for (k in 0 until winFrames) {
                val outFrame = pos + k
                if (outFrame >= totalFrames) break
                val w = hann[k]

                // Map output grain sample offset around grain center with `pitch` speed
                val srcPosF = grainCenterIn + (k - halfWin) * pitch + halfWin * pitch
                val idx0 = srcPosF.toInt().coerceIn(0, totalFrames - 1)
                val idx1 = min(idx0 + 1, totalFrames - 1)
                val frac = (srcPosF - srcPosF.toInt()).coerceIn(0f, 1f)

                for (c in 0 until ch) {
                    val s0 = input[idx0 * ch + c].toFloat()
                    val s1 = input[idx1 * ch + c].toFloat()
                    val interp = s0 + (s1 - s0) * frac
                    outAccum[outFrame * ch + c] += interp * w
                }
                normAccum[outFrame] += w
            }
            pos += hopOut
        }

        val out = ShortArray(input.size)
        val ringHz = preset.ringModHz
        val drive = preset.softClipDrive
        for (f in 0 until totalFrames) {
            val denom = if (normAccum[f] > 1e-4f) normAccum[f] else 1f
            val ringFactor = if (ringHz > 0f) {
                val t = f.toDouble() / sr.toDouble()
                (0.55 + 0.45 * sin(2.0 * PI * ringHz * t)).toFloat()
            } else {
                1f
            }
            for (c in 0 until ch) {
                val raw = (outAccum[f * ch + c] / denom) * ringFactor
                val normSample = (raw / 32768f) * drive
                // Gentle cubic soft-clip to prevent harsh integer wrap
                val clipped = when {
                    normSample >= 1f -> 0.96f
                    normSample <= -1f -> -0.96f
                    drive > 1.01f -> normSample - (normSample * normSample * normSample) / 3f
                    else -> normSample
                }
                out[f * ch + c] = (clipped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    /**
     * Rewrites [inputMp4] to [outputMp4] on device offline:
     * Video track is copied losslessly without re-encoding; audio track is decoded to PCM16,
     * pitch-transformed with [preset], and re-encoded to AAC-LC.
     */
    fun processMp4Offline(
        inputMp4: File,
        outputMp4: File,
        preset: VoicePreset,
        onProgress: (Float) -> Unit = {}
    ): VoiceProcessResult {
        if (!inputMp4.exists() || inputMp4.length() <= 0L) {
            return VoiceProcessResult(false, false, 0, "Входной файл не найден")
        }
        if (preset == VoicePreset.ORIGINAL) {
            inputMp4.copyTo(outputMp4, overwrite = true)
            onProgress(1f)
            return VoiceProcessResult(true, true, 0, "Оригинальная дорожка скопирована")
        }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false

        try {
            extractor.setDataSource(inputMp4.absolutePath)
            var videoTrackSrc = -1
            var audioTrackSrc = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackSrc < 0) {
                    videoTrackSrc = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/") && audioTrackSrc < 0) {
                    audioTrackSrc = i
                    audioFormat = fmt
                }
            }

            if (audioTrackSrc < 0 || audioFormat == null) {
                return VoiceProcessResult(
                    success = false,
                    hasAudioTrack = false,
                    processedSamples = 0,
                    detailRu = "В видео нет аудиодорожки (режим без микрофона)"
                )
            }

            onProgress(0.15f)
            val pcmStream = ByteArrayOutputStream()
            var detectedSampleRate = audioFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var detectedChannels = audioFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            // 1. Decode entire audio track to PCM16 in memory (typical short tactical clips fit easily)
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
            decoder = MediaCodec.createDecoderByType(audioMime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            extractor.selectTrack(audioTrackSrc)
            val decInfo = MediaCodec.BufferInfo()
            var decInputDone = false
            var decOutputDone = false

            while (!decOutputDone) {
                if (!decInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decInputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = decoder.outputFormat
                        detectedSampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, detectedSampleRate)
                        detectedChannels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, detectedChannels)
                    }
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && decInfo.size > 0) {
                            outBuf.position(decInfo.offset)
                            outBuf.limit(decInfo.offset + decInfo.size)
                            val chunk = ByteArray(decInfo.size)
                            outBuf.get(chunk)
                            pcmStream.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decOutputDone = true
                        }
                    }
                }
            }
            extractor.unselectTrack(audioTrackSrc)
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            decoder = null

            val rawPcmBytes = pcmStream.toByteArray()
            if (rawPcmBytes.size < 4) {
                return VoiceProcessResult(false, false, 0, "Аудиодорожка пустая")
            }

            onProgress(0.45f)
            val shortCount = rawPcmBytes.size / 2
            val inShorts = ShortArray(shortCount)
            ByteBuffer.wrap(rawPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inShorts)

            // 2. Apply offline pitch/timbre DSP transform
            val transformedShorts = transformPcm16Samples(
                input = inShorts,
                sampleRate = detectedSampleRate,
                channelCount = detectedChannels,
                preset = preset
            )
            val transformedBytes = ByteArray(transformedShorts.size * 2)
            ByteBuffer.wrap(transformedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(transformedShorts)

            onProgress(0.65f)
            // 3. Prepare Muxer + AAC Encoder, copy Video track losslessly first or alongside
            muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = if (videoFormat != null) muxer.addTrack(videoFormat) else -1

            val encFmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                detectedSampleRate,
                detectedChannels.coerceIn(1, 2)
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var outAudioTrack = -1
            var feedOffset = 0
            var totalSamplesFed = 0L
            var encInputDone = false
            var encOutputDone = false
            var wroteAudioSamples = false
            val encInfo = MediaCodec.BufferInfo()
            val channelsSafe = detectedChannels.coerceIn(1, 2)

            while (!encOutputDone) {
                if (!encInputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)
                        inBuf?.clear()
                        if (feedOffset >= transformedBytes.size) {
                            val eosPts = (totalSamplesFed * 1_000_000L) / detectedSampleRate
                            encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInputDone = true
                        } else if (inBuf != null) {
                            val chunkLen = min(inBuf.remaining(), transformedBytes.size - feedOffset)
                            val alignedLen = chunkLen - (chunkLen % (2 * channelsSafe))
                            if (alignedLen > 0) {
                                inBuf.put(transformedBytes, feedOffset, alignedLen)
                                val ptsUs = (totalSamplesFed * 1_000_000L) / detectedSampleRate
                                encoder.queueInputBuffer(inIdx, 0, alignedLen, ptsUs, 0)
                                feedOffset += alignedLen
                                totalSamplesFed += alignedLen / (2 * channelsSafe)
                            } else {
                                val eosPts = (totalSamplesFed * 1_000_000L) / detectedSampleRate
                                encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encInputDone = true
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(encInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            outAudioTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            // Write video frames right once muxer is started
                            if (videoTrackSrc >= 0 && outVideoTrack >= 0) {
                                copyVideoTrackSamples(extractor, videoTrackSrc, muxer, outVideoTrack)
                            }
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (outBuf != null && encInfo.size > 0 && !isConfig && muxerStarted && outAudioTrack >= 0) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(outAudioTrack, outBuf, encInfo)
                            wroteAudioSamples = true
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encOutputDone = true
                        }
                    }
                }
            }

            onProgress(1f)
            if (muxerStarted && !muxerStopped) {
                muxer.stop()
                muxerStopped = true
            }
            return VoiceProcessResult(
                success = wroteAudioSamples && outputMp4.exists() && outputMp4.length() > 0L,
                hasAudioTrack = true,
                processedSamples = transformedShorts.size,
                detailRu = "Голос изменён (${preset.labelRu})"
            )
        } catch (e: Exception) {
            return VoiceProcessResult(
                success = false,
                hasAudioTrack = true,
                processedSamples = 0,
                detailRu = "Сбой обработки звука: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            if (muxerStarted && !muxerStopped) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun copyVideoTrackSamples(
        extractor: MediaExtractor,
        srcTrackIndex: Int,
        muxer: MediaMuxer,
        dstTrackIndex: Int
    ) {
        extractor.selectTrack(srcTrackIndex)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocateDirect(512 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(dstTrackIndex, buffer, info)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(srcTrackIndex)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultVal: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultVal
    }
}
