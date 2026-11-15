/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Plain AAC .m4a like the iPad's (ffmpeg "ipod" muxer), written by hand instead
 * of MediaMuxer (round 12): MediaMuxer wrote brand "mp42", one chunk with 64-bit
 * offsets and an extra Android "meta" box in moov – the PS5 refused those files
 * and players showed the file name instead of the title.
 *
 * Layout: ftyp "M4A " · moov (mvhd, trak, udta/meta/ilst with tags and JPEG
 * cover) · mdat. moov first (no fragments), 32-bit stco, about one chunk per
 * second, one AAC-LC track.
 */
class M4aWriter(
    private val sampleRate: Int,
    private val channels: Int,
    /** AudioSpecificConfig (MediaFormat "csd-0"). */
    private val audioSpecificConfig: ByteArray,
    private val bitrate: Int,
    work: File,
) {
    private val raw = File(work.parentFile, work.name + ".frames")
    private val out = FileOutputStream(raw).buffered(256 * 1024)
    private val sizes = ArrayList<Int>()
    private val durations = ArrayList<Int>()
    private var maxFrame = 0

    /** Samples per frame: 1024 for AAC-LC (object type 2), else from the timestamps. */
    private val fixedFrameSamples: Int? = if (audioObjectType(audioSpecificConfig) == 2) 1024 else null
    private var lastTimeUs = -1L

    fun addFrame(data: ByteArray, offset: Int, length: Int, timeUs: Long) {
        if (length <= 0) return
        out.write(data, offset, length)
        sizes += length
        maxFrame = maxOf(maxFrame, length)
        if (fixedFrameSamples == null) {
            if (lastTimeUs >= 0 && durations.size < sizes.size - 1) {
                durations += (((timeUs - lastTimeUs) * sampleRate + 500_000) / 1_000_000).toInt().coerceAtLeast(1)
            }
            lastTimeUs = timeUs
        }
    }

    /** Writes the finished file; [target] is replaced. */
    fun finish(target: File, tags: Mp4Tags.Tags) {
        out.close()
        try {
            val frameDurations = IntArray(sizes.size) { i ->
                fixedFrameSamples ?: durations.getOrElse(i) { durations.lastOrNull() ?: 1024 }
            }
            val totalSamples = frameDurations.fold(0L) { s, d -> s + d }
            val chunkFrames = maxOf(1, sampleRate / (fixedFrameSamples ?: 1024))
            val chunkCount = (sizes.size + chunkFrames - 1) / chunkFrames
            val ftyp = ftyp()
            // moov's size does not depend on the offsets (fixed 32-bit entries): build it once with
            // placeholder offsets to know where mdat starts, then again with the real ones.
            val probe = moov(IntArray(chunkCount), frameDurations, totalSamples, chunkFrames, tags)
            val mdatStart = ftyp.size + probe.size
            val offsets = IntArray(chunkCount)
            var at = (mdatStart + 8).toLong()
            for (c in 0 until chunkCount) {
                offsets[c] = at.toInt()
                val from = c * chunkFrames
                val to = minOf(sizes.size, from + chunkFrames)
                for (i in from until to) at += sizes[i]
            }
            require(at < Int.MAX_VALUE) { "too large for 32-bit offsets" }
            val moov = moov(offsets, frameDurations, totalSamples, chunkFrames, tags)
            check(moov.size == probe.size)
            val mdatSize = 8L + raw.length()
            RandomAccessFile(target, "rw").use { file ->
                file.setLength(0)
                file.write(ftyp)
                file.write(moov)
                file.writeInt(mdatSize.toInt())
                file.write("mdat".toByteArray(Charsets.ISO_8859_1))
                raw.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        file.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            raw.delete()
        }
    }

    fun abort() {
        runCatching { out.close() }
        raw.delete()
    }

    // --- boxes ---------------------------------------------------------------

    private fun ftyp(): ByteArray = box("ftyp") {
        ascii("M4A ")
        writeInt(0x200)
        ascii("M4A "); ascii("isom"); ascii("iso2")
    }

    private fun moov(offsets: IntArray, frameDurations: IntArray, totalSamples: Long, chunkFrames: Int, tags: Mp4Tags.Tags): ByteArray {
        val durationMs = totalSamples * 1000 / sampleRate
        return box("moov") {
            write(box("mvhd") {
                writeInt(0) // version 0, flags
                writeInt(0); writeInt(0) // creation, modification
                writeInt(1000) // timescale
                writeInt(durationMs.toInt())
                writeInt(0x00010000) // rate 1.0
                writeShort(0x0100) // volume 1.0
                write(ByteArray(10))
                matrix()
                write(ByteArray(24)) // pre-defined
                writeInt(2) // next track id
            })
            write(trak(offsets, frameDurations, totalSamples, durationMs, chunkFrames))
            write(Mp4Tags.udta(tags))
        }
    }

    private fun trak(offsets: IntArray, frameDurations: IntArray, totalSamples: Long, durationMs: Long, chunkFrames: Int): ByteArray =
        box("trak") {
            write(box("tkhd") {
                writeInt(3) // version 0, flags: enabled | in movie
                writeInt(0); writeInt(0)
                writeInt(1) // track id
                writeInt(0)
                writeInt(durationMs.toInt())
                write(ByteArray(8))
                writeShort(0) // layer
                writeShort(1) // alternate group
                writeShort(0x0100) // volume
                writeShort(0)
                matrix()
                writeInt(0); writeInt(0) // width, height
            })
            write(box("mdia") {
                write(box("mdhd") {
                    writeInt(0)
                    writeInt(0); writeInt(0)
                    writeInt(sampleRate)
                    writeInt(totalSamples.toInt())
                    writeShort(0x55C4) // "und"
                    writeShort(0)
                })
                write(box("hdlr") {
                    writeInt(0); writeInt(0)
                    ascii("soun")
                    write(ByteArray(12))
                    write("SoundHandler".toByteArray(Charsets.ISO_8859_1)); writeByte(0)
                })
                write(box("minf") {
                    write(box("smhd") { writeInt(0); writeInt(0) })
                    write(box("dinf") {
                        write(box("dref") {
                            writeInt(0); writeInt(1)
                            write(box("url ") { writeInt(1) })
                        })
                    })
                    write(stbl(offsets, frameDurations, chunkFrames))
                })
            })
        }

    private fun stbl(offsets: IntArray, frameDurations: IntArray, chunkFrames: Int): ByteArray = box("stbl") {
        write(box("stsd") {
            writeInt(0); writeInt(1)
            write(box("mp4a") {
                write(ByteArray(6)); writeShort(1) // data reference index
                write(ByteArray(8)) // version, revision, vendor
                writeShort(channels)
                writeShort(16)
                writeShort(0); writeShort(0)
                writeInt(sampleRate shl 16)
                write(esds())
            })
        })
        // stts: run-length of frame durations.
        val runs = ArrayList<Pair<Int, Int>>()
        for (d in frameDurations) {
            val last = runs.lastOrNull()
            if (last != null && last.second == d) runs[runs.size - 1] = last.first + 1 to d else runs += 1 to d
        }
        write(box("stts") {
            writeInt(0); writeInt(runs.size)
            runs.forEach { (count, d) -> writeInt(count); writeInt(d) }
        })
        // stsc: full chunks, then the last (shorter) one.
        val last = sizes.size - (offsets.size - 1) * chunkFrames
        write(box("stsc") {
            writeInt(0)
            if (offsets.size > 1 && last != chunkFrames) {
                writeInt(2)
                writeInt(1); writeInt(chunkFrames); writeInt(1)
                writeInt(offsets.size); writeInt(last); writeInt(1)
            } else {
                writeInt(if (offsets.isEmpty()) 0 else 1)
                if (offsets.isNotEmpty()) { writeInt(1); writeInt(if (offsets.size == 1) sizes.size else chunkFrames); writeInt(1) }
            }
        })
        write(box("stsz") {
            writeInt(0); writeInt(0); writeInt(sizes.size)
            sizes.forEach { writeInt(it) }
        })
        write(box("stco") {
            writeInt(0); writeInt(offsets.size)
            offsets.forEach { writeInt(it) }
        })
    }

    private fun esds(): ByteArray = box("esds") {
        writeInt(0)
        fun descriptor(tag: Int, body: ByteArray) {
            writeByte(tag)
            // Four-byte length like ffmpeg (0x80 0x80 0x80 n).
            writeByte(0x80 or ((body.size shr 21) and 0x7F)); writeByte(0x80 or ((body.size shr 14) and 0x7F))
            writeByte(0x80 or ((body.size shr 7) and 0x7F)); writeByte(body.size and 0x7F)
            write(body)
        }
        val dsi = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply {
            writeByte(5)
            writeByte(0x80); writeByte(0x80); writeByte(0x80); writeByte(audioSpecificConfig.size)
            write(audioSpecificConfig)
        } }.toByteArray()
        val decoderConfig = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply {
            writeByte(0x40) // MPEG-4 audio
            writeByte(0x15) // audio stream
            val buffer = maxOf(maxFrame, 1)
            writeByte(buffer shr 16); writeShort(buffer and 0xFFFF)
            writeInt(bitrate); writeInt(bitrate)
            write(dsi)
        } }.toByteArray()
        val es = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply {
            writeShort(1) // ES id
            writeByte(0)
            writeByte(4)
            writeByte(0x80); writeByte(0x80); writeByte(0x80); writeByte(decoderConfig.size)
            write(decoderConfig)
            writeByte(6); writeByte(0x80); writeByte(0x80); writeByte(0x80); writeByte(1); writeByte(2) // SL config
        } }.toByteArray()
        descriptor(3, es)
    }

    companion object {
        /** MPEG-4 audio object type from an AudioSpecificConfig (2 = AAC-LC). */
        fun audioObjectType(asc: ByteArray): Int = if (asc.isEmpty()) 0 else (asc[0].toInt() and 0xFF) shr 3

        internal fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply(body).flush()
            val payload = bytes.toByteArray()
            val outBytes = ByteArrayOutputStream(payload.size + 8)
            DataOutputStream(outBytes).apply {
                writeInt(payload.size + 8)
                write(type.toByteArray(Charsets.ISO_8859_1))
                write(payload)
            }
            return outBytes.toByteArray()
        }

        private fun DataOutputStream.ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

        private fun DataOutputStream.matrix() {
            intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { writeInt(it) }
        }
    }
}

/** AAC-LC AudioSpecificConfig for an encoder that hands none out. */
object ResonaAudioConverterAsc {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun lc(sampleRate: Int, channels: Int): ByteArray {
        val index = rates.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
        return byteArrayOf(((2 shl 3) or (index shr 1)).toByte(), (((index and 1) shl 7) or (channels shl 3)).toByte())
    }
}
