/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Downloaded audio → .m4a in the iPad's format ([M4aWriter], tags and JPEG cover
 * included). AAC stays as it is (frames copied, no quality loss); Opus/WebM and
 * anything else is decoded and encoded as AAC-LC at 256 kbit/s with MediaCodec.
 */
object ResonaAudioConverter {
    const val AAC_BITRATE = 256_000
    private const val TIMEOUT_US = 10_000L

    suspend fun toM4a(input: File, output: File, tags: Mp4Tags.Tags) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            val track = audioTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val csd = format.getByteBuffer("csd-0")?.let { bytes(it) }
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC && csd != null && csd.isNotEmpty()) {
                remux(extractor, format, csd, output, tags)
            } else {
                transcode(extractor, format, output, tags)
            }
        } finally {
            extractor.release()
        }
    }

    fun audioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track")

    private fun bytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private suspend fun remux(extractor: MediaExtractor, format: MediaFormat, csd: ByteArray, output: File, tags: Mp4Tags.Tags) {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 128_000
        val writer = M4aWriter(sampleRate, channels, csd, bitrate, output)
        try {
            val buffer = ByteBuffer.allocate(64 * 1024)
            val frame = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.get(frame, 0, size)
                buffer.clear()
                writer.addFrame(frame, 0, size, extractor.sampleTime)
                extractor.advance()
            }
            writer.finish(output, tags)
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }
    }

    private suspend fun transcode(extractor: MediaExtractor, inputFormat: MediaFormat, output: File, tags: Mp4Tags.Tags) {
        var writer: M4aWriter? = null
        var encoder: MediaCodec? = null
        var asc: ByteArray? = null
        val pending = ArrayDeque<ByteArray>()
        var framesIn = 0L
        var encoderInputDone = false
        var encoderDone = false
        val encInfo = MediaCodec.BufferInfo()
        var sampleRate = 0
        var channels = 0
        try {
            decodePcm(extractor, inputFormat, onFormat = { rate, ch ->
                sampleRate = rate
                channels = ch
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }) { pcm, end ->
                if (pcm != null) pending.addLast(pcm)
                val enc = encoder ?: return@decodePcm
                // Feed what fits, drain what is ready.
                while (true) {
                    if (!encoderInputDone && (pending.isNotEmpty() || end)) {
                        val index = enc.dequeueInputBuffer(if (end) TIMEOUT_US else 0)
                        if (index >= 0) {
                            val buffer = enc.getInputBuffer(index)!!
                            buffer.clear()
                            val next = pending.removeFirstOrNull()
                            if (next != null) {
                                val take = minOf(next.size, buffer.remaining())
                                buffer.put(next, 0, take)
                                val timeUs = framesIn * 1_000_000L / sampleRate
                                framesIn += take / (2 * channels)
                                enc.queueInputBuffer(index, 0, take, timeUs, 0)
                                if (take < next.size) pending.addFirst(next.copyOfRange(take, next.size))
                            } else {
                                enc.queueInputBuffer(index, 0, 0, framesIn * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encoderInputDone = true
                            }
                        }
                    }
                    val out = enc.dequeueOutputBuffer(encInfo, if (end) TIMEOUT_US else 0)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (asc == null) asc = enc.outputFormat.getByteBuffer("csd-0")?.let { bytes(it) }
                        continue
                    }
                    if (out < 0) {
                        if (!end || encoderDone) break
                        continue
                    }
                    val buffer = enc.getOutputBuffer(out)!!
                    buffer.position(encInfo.offset)
                    buffer.limit(encInfo.offset + encInfo.size)
                    val data = ByteArray(encInfo.size).also { buffer.get(it) }
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (asc == null) asc = data
                    } else if (data.isNotEmpty()) {
                        val w = writer ?: M4aWriter(sampleRate, channels, asc ?: ResonaAudioConverterAsc.lc(sampleRate, channels), AAC_BITRATE, output).also { writer = it }
                        w.addFrame(data, 0, data.size, encInfo.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(out, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                        break
                    }
                }
            }
            (writer ?: error("no audio")).finish(output, tags)
        } catch (e: Throwable) {
            writer?.abort()
            throw e
        } finally {
            encoder?.let { runCatching { it.stop() }; it.release() }
        }
    }

    /**
     * Decodes [extractor]'s selected track to 16-bit PCM. [onFormat] once with rate and
     * channels; [onPcm] with each chunk, and finally with (null, end = true).
     */
    suspend fun decodePcm(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        onFormat: (Int, Int) -> Unit,
        onPcm: suspend (ByteArray?, Boolean) -> Unit,
    ) {
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var announced = false
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
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
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (index < 0) continue
                if (!announced) {
                    onFormat(rate, channels)
                    announced = true
                }
                if (info.size > 0) {
                    val buffer = decoder.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size).also { buffer.get(it) }
                    decoder.releaseOutputBuffer(index, false)
                    onPcm(pcm, false)
                } else {
                    decoder.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (!announced) onFormat(rate, channels)
            onPcm(null, true)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }
}

/** Cover for tags and folder images: JPEG (baseline), at most 600×600, like the iPad's. */
object CoverArt {
    const val MAX_SIDE = 600

    fun jpeg(source: ByteArray?): ByteArray? {
        if (source == null || source.isEmpty()) return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(source, 0, source.size) }.getOrNull() ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val scaled =
            if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }
}
