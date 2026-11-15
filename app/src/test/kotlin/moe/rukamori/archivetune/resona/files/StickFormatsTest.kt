/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ID3v2.3 and FLAC metadata for the stick's MP3/FLAC variants. Invented tags. */
class StickFormatsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0x02, 0x58, 0x02, 0x58) + ByteArray(20)
    private val tags = Mp4Tags.Tags("Erfundener Titel", "Platzhalter Band", "Ausgedachtes Album", 7, jpeg)

    private fun frames(tag: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var at = 10
        while (at + 10 <= tag.size) {
            val id = String(tag, at, 4, Charsets.ISO_8859_1)
            val size = ByteBuffer.wrap(tag, at + 4, 4).int
            out[id] = tag.copyOfRange(at + 10, at + 10 + size)
            at += 10 + size
        }
        return out
    }

    @Test
    fun id3v23Header() {
        val tag = Id3v23.tag(tags)
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1))
        assertEquals(3, tag[3].toInt())
        assertEquals(0, tag[4].toInt())
        val size = (tag[6].toInt() shl 21) or (tag[7].toInt() shl 14) or (tag[8].toInt() shl 7) or tag[9].toInt()
        assertEquals(tag.size - 10, size)
        assertArrayEquals(byteArrayOf(0, 0, 0x02, 0x7F), Id3v23.synchsafe(383))
    }

    @Test
    fun id3v23FramesAndFrontCover() {
        val f = frames(Id3v23.tag(tags))
        assertEquals(listOf("TIT2", "TPE1", "TPE2", "TALB", "TRCK", "APIC"), f.keys.toList())
        assertEquals("Erfundener Titel", String(f.getValue("TIT2"), 1, f.getValue("TIT2").size - 1, Charsets.ISO_8859_1))
        assertEquals("7", String(f.getValue("TRCK"), 1, 1, Charsets.ISO_8859_1))
        val apic = f.getValue("APIC")
        val mime = "image/jpeg"
        assertEquals(mime, String(apic, 1, mime.length, Charsets.ISO_8859_1))
        assertEquals(3, apic[1 + mime.length + 1].toInt()) // front cover
        assertArrayEquals(jpeg, apic.copyOfRange(1 + mime.length + 3, apic.size))
    }

    @Test
    fun id3v23NonLatinUsesUtf16() {
        val f = frames(Id3v23.tag(tags.copy(title = "Ausgedacht ♥", cover = null)))
        val tit2 = f.getValue("TIT2")
        assertEquals(1, tit2[0].toInt())
        assertEquals("Ausgedacht ♥", String(tit2, 3, tit2.size - 3, Charsets.UTF_16LE))
        assertTrue("APIC" !in f)
    }

    @Test
    fun flacBlocksAndPicture() {
        val header = FlacMeta.block(6, true, ByteArray(300))
        assertEquals(0x86, header[0].toInt() and 0xFF)
        assertEquals(300, ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF))
        val (w, h) = FlacMeta.jpegSize(jpeg)
        assertEquals(600 to 600, w to h)
        val picture = FlacMeta.picture(jpeg, w, h)
        assertEquals(3, ByteBuffer.wrap(picture, 0, 4).int)
        assertEquals("image/jpeg", String(picture, 8, 10, Charsets.ISO_8859_1))
    }

    @Test
    fun streamInfoFromEncoderCsd() {
        val info = ByteArray(34) { it.toByte() }
        assertArrayEquals(info, FlacMeta.streamInfo("fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info))
        assertArrayEquals(info, FlacMeta.streamInfo(info))
        assertNull(FlacMeta.streamInfo(ByteArray(5)))
    }

    @Test
    fun vorbisCommentFields() {
        val c = FlacMeta.vorbisComment(tags)
        val b = ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN)
        val vendor = b.int
        b.position(4 + vendor)
        assertEquals(4, b.int)
        val first = b.int
        assertEquals("TITLE=Erfundener Titel", String(c, b.position(), first, Charsets.UTF_8))
    }
}
