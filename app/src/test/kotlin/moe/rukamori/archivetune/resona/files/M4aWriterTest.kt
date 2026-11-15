/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The written file has the properties of the iPad's (ffmpeg) .m4a that plays on
 * the PS5: brand M4A, no fragments, one AAC-LC track, 32-bit chunk offsets,
 * title/artist/album/track in moov/udta/meta/ilst, JPEG cover – and no
 * Android "meta" box in moov. Synthetic frames, invented tags.
 */
class M4aWriterTest {
    private class Box(val type: String, val start: Int, val size: Int, val header: Int = 8) {
        val end get() = start + size
        val body get() = start + header
    }

    private fun boxes(d: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            val size = ByteBuffer.wrap(d, at, 4).int
            val type = String(d, at + 4, 4, Charsets.ISO_8859_1)
            out += Box(type, at, size)
            at += size
        }
        return out
    }

    private fun child(d: ByteArray, parent: Box, type: String, skip: Int = 0) =
        boxes(d, parent.body + skip, parent.end).firstOrNull { it.type == type }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(40) { it.toByte() }
    private val tags = Mp4Tags.Tags("Erfundener Titel", "Platzhalter Band", "Ausgedachtes Album", 4, jpeg)

    private fun write(frames: Int, rate: Int = 44100): Pair<ByteArray, List<Int>> {
        val dir = createTempDir()
        val target = File(dir, "song.m4a")
        val writer = M4aWriter(rate, 2, ResonaAudioConverterAsc.lc(rate, 2), 256_000, target)
        val sizes = (0 until frames).map { 300 + it % 50 }
        sizes.forEachIndexed { i, size -> writer.addFrame(ByteArray(size) { (i + it).toByte() }, 0, size, i * 1024L * 1_000_000 / rate) }
        writer.finish(target, tags)
        val bytes = target.readBytes()
        dir.deleteRecursively()
        return bytes to sizes
    }

    @Test
    fun layoutLikeTheIpadFile() {
        val (d, sizes) = write(frames = 200)
        val top = boxes(d, 0, d.size)
        assertEquals(listOf("ftyp", "moov", "mdat"), top.map { it.type })
        assertEquals("M4A ", String(d, 8, 4, Charsets.ISO_8859_1))
        assertFalse("no fragments", top.any { it.type == "moof" || it.type == "mvex" })

        val moov = top.first { it.type == "moov" }
        assertEquals(listOf("mvhd", "trak", "udta"), boxes(d, moov.body, moov.end).map { it.type })
        val stbl = child(d, child(d, child(d, child(d, moov, "trak")!!, "mdia")!!, "minf")!!, "stbl")!!
        val names = boxes(d, stbl.body, stbl.end).map { it.type }
        assertTrue("32-bit offsets", "stco" in names && "co64" !in names)

        // mp4a, two channels, AAC-LC in the esds.
        val stsd = child(d, stbl, "stsd")!!
        assertEquals("mp4a", String(d, stsd.body + 12, 4, Charsets.ISO_8859_1))
        val esdsAt = String(d, stsd.start, stsd.size, Charsets.ISO_8859_1).indexOf("esds") + stsd.start
        val asc = String(d, esdsAt, 80, Charsets.ISO_8859_1).indexOf("\u0005\u0080\u0080\u0080\u0002") + esdsAt + 5
        assertEquals(2, (d[asc].toInt() and 0xFF) shr 3)

        // About one chunk per second; every frame where the offsets say.
        val stco = child(d, stbl, "stco")!!
        val chunks = ByteBuffer.wrap(d, stco.body + 4, 4).int
        assertEquals((sizes.size + 43 - 1) / 43, chunks)
        val firstOffset = ByteBuffer.wrap(d, stco.body + 8, 4).int
        val mdat = top.first { it.type == "mdat" }
        assertEquals(mdat.body, firstOffset)
        assertEquals(0.toByte(), d[firstOffset])
        assertEquals(sizes.sum() + 8, mdat.size)
    }

    @Test
    fun tagsAndJpegCover() {
        val (d, _) = write(frames = 50)
        val read = Mp4Tags.read(d)
        assertEquals("Erfundener Titel", read["©nam"])
        assertEquals("Platzhalter Band", read["©ART"])
        assertEquals("Ausgedachtes Album", read["©alb"])
        assertEquals("4", read["trkn"])
        assertNotNull(read["covr"])
        // covr data type 13 = JPEG, payload starts with the JPEG marker.
        val at = String(d, Charsets.ISO_8859_1).indexOf("covr")
        assertEquals(13, ByteBuffer.wrap(d, at + 12, 4).int)
        assertArrayEquals(jpeg.copyOfRange(0, 4), d.copyOfRange(at + 20, at + 24))
        // Only one meta box, the one in udta (MediaMuxer added another in moov).
        val moov = boxes(d, 0, d.size).first { it.type == "moov" }
        assertFalse(boxes(d, moov.body, moov.end).any { it.type == "meta" })
    }

    @Test
    fun durationFromFrames() {
        val (d, _) = write(frames = 441, rate = 44100)
        val mdhdAt = String(d, Charsets.ISO_8859_1).indexOf("mdhd")
        val timescale = ByteBuffer.wrap(d, mdhdAt + 16, 4).int
        val duration = ByteBuffer.wrap(d, mdhdAt + 20, 4).int
        assertEquals(44100, timescale)
        assertEquals(441 * 1024, duration)
    }

    @Test
    fun ascForAacLc() {
        assertArrayEquals(byteArrayOf(0x12, 0x10), ResonaAudioConverterAsc.lc(44100, 2))
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), ResonaAudioConverterAsc.lc(48000, 2))
        assertEquals(2, M4aWriter.audioObjectType(ResonaAudioConverterAsc.lc(48000, 2)))
    }
}
