/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Downloaded audio → .m4a. AAC stays as it is (remuxed, no quality loss);
 * Opus/WebM and anything else is decoded and encoded as AAC-LC at 256 kbit/s
 * with MediaCodec. Tags and cover are added afterwards ([Mp4Tags]).
 */
object ResonaAudioConverter {
    const val AAC_BITRATE = 256_000
    private const val TIMEOUT_US = 10_000L

    suspend fun toM4a(input: File, output: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
                remux(extractor, format, output)
            } else {
                transcode(extractor, format, output)
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun remux(extractor: MediaExtractor, format: MediaFormat, output: File) {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val track = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    private suspend fun transcode(extractor: MediaExtractor, inputFormat: MediaFormat, output: File) {
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pending = ArrayDeque<Pair<ByteArray, Long>>()
        var inputDone = false
        var decoderDone = false
        var encoderInputDone = false
        var encoderDone = false
        var framesWritten = 0L
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        fun startEncoder() {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        try {
            while (!encoderDone) {
                coroutineContext.ensureActive()
                // 1. Feed the decoder.
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 2. Decoded PCM → queue.
                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = decoder.outputFormat
                            sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        index >= 0 -> {
                            if (decInfo.size > 0) {
                                val buffer = decoder.getOutputBuffer(index)!!
                                buffer.position(decInfo.offset)
                                buffer.limit(decInfo.offset + decInfo.size)
                                val pcm = ByteArray(decInfo.size)
                                buffer.get(pcm)
                                pending.addLast(pcm to decInfo.presentationTimeUs)
                            }
                            decoder.releaseOutputBuffer(index, false)
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        }
                    }
                }
                if (encoder == null && (pending.isNotEmpty() || decoderDone)) startEncoder()
                val enc = encoder ?: continue
                // 3. PCM → encoder (in slices that fit its input buffers).
                if (!encoderInputDone && (pending.isNotEmpty() || decoderDone)) {
                    val index = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = enc.getInputBuffer(index)!!
                        buffer.clear()
                        val next = pending.removeFirstOrNull()
                        when {
                            next != null -> {
                                val (pcm, _) = next
                                val take = minOf(pcm.size, buffer.remaining())
                                buffer.put(pcm, 0, take)
                                // Timestamps from the frames written, so they stay monotonic.
                                val timeUs = framesWritten * 1_000_000L / sampleRate
                                framesWritten += take / (2 * channels)
                                enc.queueInputBuffer(index, 0, take, timeUs, 0)
                                if (take < pcm.size) pending.addFirst(pcm.copyOfRange(take, pcm.size) to 0L)
                            }
                            else -> {
                                enc.queueInputBuffer(index, 0, 0, framesWritten * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encoderInputDone = true
                            }
                        }
                    }
                }
                // 4. AAC → muxer.
                val out = enc.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer!!.addTrack(enc.outputFormat)
                        muxer!!.start()
                    }
                    out >= 0 -> {
                        val buffer = enc.getOutputBuffer(out)!!
                        val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && encInfo.size > 0 && muxerTrack >= 0) {
                            buffer.position(encInfo.offset)
                            buffer.limit(encInfo.offset + encInfo.size)
                            muxer!!.writeSampleData(muxerTrack, buffer, encInfo)
                        }
                        enc.releaseOutputBuffer(out, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
            muxer?.stop()
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            encoder?.let { runCatching { it.stop() }; it.release() }
            muxer?.let { runCatching { it.release() } }
        }
    }
}
