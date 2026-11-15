/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * iTunes-style tags (title, artist, album, track number, cover) for an .m4a
 * written by MediaMuxer, which writes none. The tags go into moov/udta/meta/
 * ilst; when moov sits before mdat, every chunk offset (stco/co64) moves by
 * the size difference.
 */
object Mp4Tags {
    data class Tags(
        val title: String,
        val artist: String,
        val album: String?,
        val track: Int?,
        val cover: ByteArray?,
    )

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    fun write(file: File, tags: Tags) {
        val bytes = file.readBytes()
        val out = apply(bytes, tags)
        val tmp = File(file.parentFile, file.name + ".tags")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    /** The whole file with tags; pure, for tests. */
    fun apply(file: ByteArray, tags: Tags): ByteArray {
        val top = children(file, 0, file.size.toLong())
        val moov = top.firstOrNull { it.type == "moov" } ?: error("no moov")
        val mdat = top.firstOrNull { it.type == "mdat" }
        val oldMoov = file.copyOfRange(moov.start.toInt(), (moov.start + moov.size).toInt())
        var newMoov = withUdta(oldMoov, ilst(tags))
        val delta = newMoov.size - oldMoov.size
        if (mdat != null && mdat.start > moov.start && delta != 0) {
            newMoov = shiftChunkOffsets(newMoov, delta.toLong())
        }
        val out = ByteArrayOutputStream(file.size + delta)
        out.write(file, 0, moov.start.toInt())
        out.write(newMoov)
        val after = (moov.start + moov.size).toInt()
        out.write(file, after, file.size - after)
        return out.toByteArray()
    }

    private fun children(data: ByteArray, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(data, at)
            val type = String(data, (at + 4).toInt(), 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = u64(data, at + 8)
                header = 16
            } else if (size == 0L) {
                size = to - at
            }
            if (size < header || at + size > to) break
            out += Box(type, at, size, header)
            at += size
        }
        return out
    }

    /** moov with its old udta and Android's moov-level "meta" (if any) replaced by our udta. */
    private fun withUdta(moov: ByteArray, ilst: ByteArray): ByteArray {
        val kids = children(moov, 8, moov.size.toLong()).filter { it.type != "udta" && it.type != "meta" }
        val body = ByteArrayOutputStream()
        kids.forEach { body.write(moov, it.start.toInt(), it.size.toInt()) }
        body.write(udtaFor(ilst))
        return box("moov", body.toByteArray())
    }

    /** moov/udta/meta(hdlr "mdir")/ilst – where iTunes, ffmpeg and the iPad put the tags. */
    fun udta(tags: Tags): ByteArray = udtaFor(ilst(tags))

    private fun udtaFor(ilst: ByteArray): ByteArray {
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "mdir".toByteArray() + "appl".toByteArray() + ByteArray(8) + byteArrayOf(0))
        val meta = box("meta", ByteArray(4) + hdlr + ilst)
        return box("udta", meta)
    }

    private fun ilst(tags: Tags): ByteArray {
        val items = ByteArrayOutputStream()
        fun text(type: String, value: String?) {
            if (value.isNullOrBlank()) return
            items.write(box(type, data(1, value.toByteArray(Charsets.UTF_8))))
        }
        text("©nam", tags.title)
        text("©ART", tags.artist)
        text("aART", tags.artist)
        text("©alb", tags.album)
        tags.track?.takeIf { it > 0 }?.let { track ->
            val value = ByteBuffer.allocate(8).putShort(0).putShort(track.toShort()).putShort(0).putShort(0).array()
            items.write(box("trkn", data(0, value)))
        }
        tags.cover?.takeIf { it.isNotEmpty() }?.let { cover ->
            val isPng = cover.size > 4 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()
            items.write(box("covr", data(if (isPng) 14 else 13, cover)))
        }
        return box("ilst", items.toByteArray())
    }

    private fun data(type: Int, payload: ByteArray): ByteArray =
        box("data", ByteBuffer.allocate(8).putInt(type).putInt(0).array() + payload)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + payload.size)
        buffer.putInt(8 + payload.size)
        buffer.put(type.toByteArray(Charsets.ISO_8859_1))
        buffer.put(payload)
        return buffer.array()
    }

    /** Adds [delta] to every stco/co64 entry inside moov (moov/trak/mdia/minf/stbl). */
    private fun shiftChunkOffsets(moov: ByteArray, delta: Long): ByteArray {
        val copy = moov.copyOf()
        fun walk(from: Long, to: Long) {
            for (b in children(copy, from, to)) {
                when (b.type) {
                    "trak", "mdia", "minf", "stbl" -> walk(b.start + b.header, b.start + b.size)
                    "stco" -> {
                        val count = u32(copy, b.start + b.header + 4)
                        for (i in 0 until count) {
                            val at = b.start + b.header + 8 + i * 4
                            putU32(copy, at, u32(copy, at) + delta)
                        }
                    }
                    "co64" -> {
                        val count = u32(copy, b.start + b.header + 4)
                        for (i in 0 until count) {
                            val at = b.start + b.header + 8 + i * 8
                            putU64(copy, at, u64(copy, at) + delta)
                        }
                    }
                }
            }
        }
        walk(8, copy.size.toLong())
        return copy
    }

    /** Reads the tags back (tests and the duplicate check). */
    fun read(file: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(from: Long, to: Long, path: String) {
            for (b in children(file, from, to)) {
                val inner = b.start + b.header
                when {
                    b.type == "moov" || b.type == "udta" || b.type == "ilst" -> walk(inner, b.start + b.size, path + "/" + b.type)
                    b.type == "meta" -> walk(inner + 4, b.start + b.size, path + "/meta")
                    path.endsWith("/ilst") -> {
                        val dataBox = children(file, inner, b.start + b.size).firstOrNull { it.type == "data" } ?: continue
                        val payloadAt = (dataBox.start + dataBox.header + 8).toInt()
                        val payloadEnd = (dataBox.start + dataBox.size).toInt()
                        out[b.type] =
                            when (b.type) {
                                "trkn" -> u16(file, payloadAt + 2L).toString()
                                "covr" -> "${payloadEnd - payloadAt} bytes"
                                else -> String(file, payloadAt, payloadEnd - payloadAt, Charsets.UTF_8)
                            }
                    }
                }
            }
        }
        walk(0, file.size.toLong(), "")
        return out
    }

    private fun u16(d: ByteArray, at: Long): Int = ((d[at.toInt()].toInt() and 0xFF) shl 8) or (d[at.toInt() + 1].toInt() and 0xFF)

    private fun u32(d: ByteArray, at: Long): Long {
        val i = at.toInt()
        return ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or
            ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)
    }

    private fun u64(d: ByteArray, at: Long): Long = (u32(d, at) shl 32) or u32(d, at + 4)

    private fun putU32(d: ByteArray, at: Long, v: Long) {
        val i = at.toInt()
        d[i] = (v shr 24).toByte()
        d[i + 1] = (v shr 16).toByte()
        d[i + 2] = (v shr 8).toByte()
        d[i + 3] = v.toByte()
    }

    private fun putU64(d: ByteArray, at: Long, v: Long) {
        putU32(d, at, v ushr 32)
        putU32(d, at + 4, v and 0xFFFFFFFFL)
    }
}
