/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LAME (libresona_mp3.so): 16-bit PCM → MP3 CBR. */
object Mp3Native {
    val available: Boolean = runCatching { System.loadLibrary("resona_mp3") }.isSuccess

    external fun nativeOpen(sampleRate: Int, channels: Int, kbps: Int): Long
    external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int): ByteArray
    external fun nativeFlush(handle: Long): ByteArray
    external fun nativeClose(handle: Long)
}

/** ID3v2.3 tag (title, artist, album, track, APIC front cover JPEG) – the version most players read. */
object Id3v23 {
    fun tag(tags: Mp4Tags.Tags): ByteArray {
        val frames = ByteArrayOutputStream()
        fun text(id: String, value: String?) {
            if (value.isNullOrBlank()) return
            frames.write(frame(id, encodedText(value)))
        }
        text("TIT2", tags.title)
        text("TPE1", tags.artist)
        text("TPE2", tags.artist)
        text("TALB", tags.album)
        tags.track?.takeIf { it > 0 }?.let { text("TRCK", it.toString()) }
        tags.cover?.takeIf { it.isNotEmpty() }?.let { cover ->
            val body = ByteArrayOutputStream()
            body.write(0) // ISO-8859-1 for mime and description
            body.write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); body.write(0)
            body.write(3) // front cover
            body.write(0) // empty description
            body.write(cover)
            frames.write(frame("APIC", body.toByteArray()))
        }
        val payload = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(3); out.write(0) // version 2.3.0
        out.write(0) // flags
        out.write(synchsafe(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /** Latin-1 when possible, else UTF-16 with BOM (2.3 has no UTF-8). */
    private fun encodedText(value: String): ByteArray {
        val latin = Charsets.ISO_8859_1.newEncoder().canEncode(value)
        return if (latin) byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
        else byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
    }

    private fun frame(id: String, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(10 + body.size)
        b.put(id.toByteArray(Charsets.ISO_8859_1))
        b.putInt(body.size) // 2.3: plain 32-bit size
        b.putShort(0)
        b.put(body)
        return b.array()
    }

    fun synchsafe(size: Int): ByteArray =
        byteArrayOf(((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte())
}

/** FLAC metadata blocks around the encoder's frames: STREAMINFO, VORBIS_COMMENT, PICTURE. */
object FlacMeta {
    fun vorbisComment(tags: Mp4Tags.Tags): ByteArray {
        val le = ByteArrayOutputStream()
        fun int(v: Int) = le.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        val vendor = "Resona".toByteArray()
        int(vendor.size); le.write(vendor)
        val fields = listOfNotNull(
            "TITLE=${tags.title}",
            tags.artist.takeIf { it.isNotBlank() }?.let { "ARTIST=$it" },
            tags.album?.takeIf { it.isNotBlank() }?.let { "ALBUM=$it" },
            tags.track?.takeIf { it > 0 }?.let { "TRACKNUMBER=$it" },
        ).map { it.toByteArray(Charsets.UTF_8) }
        int(fields.size)
        fields.forEach { int(it.size); le.write(it) }
        return le.toByteArray()
    }

    fun picture(jpeg: ByteArray, width: Int, height: Int): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply {
            writeInt(3) // front cover
            val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
            writeInt(mime.size); write(mime)
            writeInt(0) // description
            writeInt(width); writeInt(height); writeInt(24); writeInt(0)
            writeInt(jpeg.size); write(jpeg)
        }
        return b.toByteArray()
    }

    fun block(type: Int, last: Boolean, body: ByteArray): ByteArray {
        val header = ByteArray(4)
        header[0] = ((if (last) 0x80 else 0) or type).toByte()
        header[1] = (body.size shr 16).toByte(); header[2] = (body.size shr 8).toByte(); header[3] = body.size.toByte()
        return header + body
    }

    /** STREAMINFO (34 bytes) from the encoder's CSD, which may start with "fLaC" and a block header. */
    fun streamInfo(csd: ByteArray): ByteArray? {
        var at = 0
        if (csd.size >= 4 && String(csd, 0, 4, Charsets.ISO_8859_1) == "fLaC") at = 4
        if (csd.size >= at + 4 + 34 && (csd[at].toInt() and 0x7F) == 0) return csd.copyOfRange(at + 4, at + 4 + 34)
        if (csd.size - at == 34) return csd.copyOfRange(at, at + 34)
        return null
    }

    /** JPEG width/height from its SOF0/SOF2 marker (0 when unknown). */
    fun jpegSize(jpeg: ByteArray): Pair<Int, Int> {
        var i = 2
        while (i + 9 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return 0 to 0
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xC0 || marker == 0xC2) {
                val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + length
        }
        return 0 to 0
    }
}

/** Stick variants from a song's .m4a: MP3, FLAC, M4A without cover (cover test). */
object StickFormats {
    private const val TIMEOUT_US = 10_000L

    /** MP3 320 kbit/s CBR, ID3v2.3 in front. */
    suspend fun mp3(m4a: File, target: File, tags: Mp4Tags.Tags) {
        check(Mp3Native.available) { "MP3 encoder missing" }
        val extractor = MediaExtractor()
        var handle = 0L
        try {
            extractor.setDataSource(m4a.path)
            val track = ResonaAudioConverter.audioTrack(extractor)
            extractor.selectTrack(track)
            FileOutputStream(target).buffered(256 * 1024).use { out ->
                out.write(Id3v23.tag(tags))
                var channels = 2
                ResonaAudioConverter.decodePcm(extractor, extractor.getTrackFormat(track), onFormat = { rate, ch ->
                    channels = ch
                    handle = Mp3Native.nativeOpen(rate, ch, 320)
                    check(handle != 0L) { "MP3 encoder setup failed" }
                }) { pcm, end ->
                    if (pcm != null && pcm.isNotEmpty()) {
                        val shorts = ShortArray(pcm.size / 2)
                        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        out.write(Mp3Native.nativeEncode(handle, shorts, shorts.size / channels))
                    }
                    if (end) out.write(Mp3Native.nativeFlush(handle))
                }
            }
        } finally {
            if (handle != 0L) Mp3Native.nativeClose(handle)
            extractor.release()
        }
    }

    /** FLAC (MediaCodec encoder) with VORBIS_COMMENT and PICTURE. */
    suspend fun flac(m4a: File, target: File, tags: Mp4Tags.Tags) {
        val extractor = MediaExtractor()
        var encoder: MediaCodec? = null
        val frames = File(target.parentFile, target.name + ".frames")
        try {
            extractor.setDataSource(m4a.path)
            val track = ResonaAudioConverter.audioTrack(extractor)
            extractor.selectTrack(track)
            var csd: ByteArray? = null
            var sampleRate = 44100
            var channels = 2
            var framesIn = 0L
            var inputDone = false
            var done = false
            val info = MediaCodec.BufferInfo()
            val pending = ArrayDeque<ByteArray>()
            FileOutputStream(frames).buffered(256 * 1024).use { out ->
                ResonaAudioConverter.decodePcm(extractor, extractor.getTrackFormat(track), onFormat = { rate, ch ->
                    sampleRate = rate
                    channels = ch
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
                        configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, ch).apply {
                            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                }) { pcm, end ->
                    if (pcm != null) pending.addLast(pcm)
                    val enc = encoder ?: return@decodePcm
                    while (true) {
                        if (!inputDone && (pending.isNotEmpty() || end)) {
                            val index = enc.dequeueInputBuffer(if (end) TIMEOUT_US else 0)
                            if (index >= 0) {
                                val buffer = enc.getInputBuffer(index)!!
                                buffer.clear()
                                val next = pending.removeFirstOrNull()
                                if (next != null) {
                                    val take = minOf(next.size, buffer.remaining())
                                    buffer.put(next, 0, take)
                                    enc.queueInputBuffer(index, 0, take, framesIn * 1_000_000L / sampleRate, 0)
                                    framesIn += take / (2 * channels)
                                    if (take < next.size) pending.addFirst(next.copyOfRange(take, next.size))
                                } else {
                                    enc.queueInputBuffer(index, 0, 0, framesIn * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                }
                            }
                        }
                        val index = enc.dequeueOutputBuffer(info, if (end) TIMEOUT_US else 0)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (csd == null) csd = enc.outputFormat.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                            continue
                        }
                        if (index < 0) {
                            if (!end || done) break
                            continue
                        }
                        val buffer = enc.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size).also { buffer.get(it) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (csd == null) csd = data
                        } else {
                            out.write(data)
                        }
                        enc.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done = true
                            break
                        }
                    }
                }
            }
            val streamInfo = csd?.let { FlacMeta.streamInfo(it) } ?: error("FLAC encoder gave no STREAMINFO")
            FileOutputStream(target).buffered(256 * 1024).use { file ->
                file.write("fLaC".toByteArray(Charsets.ISO_8859_1))
                file.write(FlacMeta.block(0, false, streamInfo))
                val cover = tags.cover
                file.write(FlacMeta.block(4, cover == null, FlacMeta.vorbisComment(tags)))
                if (cover != null) {
                    val (w, h) = FlacMeta.jpegSize(cover)
                    file.write(FlacMeta.block(6, true, FlacMeta.picture(cover, w, h)))
                }
                frames.inputStream().use { it.copyTo(file) }
            }
        } finally {
            frames.delete()
            encoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /** The same AAC frames and tags, without the embedded cover (folder image tests). */
    suspend fun m4aWithoutCover(m4a: File, target: File, tags: Mp4Tags.Tags) =
        ResonaAudioConverter.toM4a(m4a, target, tags.copy(cover = null))
}
