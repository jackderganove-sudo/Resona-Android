/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** Synthetic MP4 boxes (no real audio), invented tag values. */
class Mp4TagsTest {
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private fun stco(vararg offsets: Int): ByteArray {
        val b = ByteBuffer.allocate(8 + 4 * offsets.size).putInt(0).putInt(offsets.size)
        offsets.forEach { b.putInt(it) }
        return box("stco", b.array())
    }

    private fun moov(stco: ByteArray, withOldUdta: Boolean = false): ByteArray {
        val trak = box("trak", box("mdia", box("minf", box("stbl", stco))))
        return if (withOldUdta) box("moov", box("mvhd", ByteArray(12)), trak, box("udta", box("xyz ", ByteArray(3))))
        else box("moov", box("mvhd", ByteArray(12)), trak)
    }

    private fun readStco(file: ByteArray): List<Int> {
        val at = String(file, Charsets.ISO_8859_1).indexOf("stco")
        val b = ByteBuffer.wrap(file, at + 4, file.size - at - 4)
        b.int
        val count = b.int
        return List(count) { b.int }
    }

    private val tags = Mp4Tags.Tags("Erfundener Titel", "Platzhalter Band", "Ausgedachtes Album", 7, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))

    @Test
    fun moovBeforeMdatShiftsChunkOffsets() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        val mdatPayload = ByteArray(32) { it.toByte() }
        // Chunk offsets point into mdat; compute them from the layout.
        val provisional = moov(stco(0, 0))
        val mdatStart = ftyp.size + provisional.size
        val original = ftyp + moov(stco(mdatStart + 8, mdatStart + 24)) + box("mdat", mdatPayload)

        val tagged = Mp4Tags.apply(original, tags)
        val grow = tagged.size - original.size
        val offsets = readStco(tagged)
        assertEquals(listOf(mdatStart + 8 + grow, mdatStart + 24 + grow), offsets)
        // The audio bytes sit exactly where the shifted offsets point.
        assertEquals(mdatPayload[0], tagged[offsets[0]])
        assertEquals(mdatPayload[16], tagged[offsets[1]])

        val read = Mp4Tags.read(tagged)
        assertEquals("Erfundener Titel", read["©nam"])
        assertEquals("Platzhalter Band", read["©ART"])
        assertEquals("Ausgedachtes Album", read["©alb"])
        assertEquals("7", read["trkn"])
        assertEquals("5 bytes", read["covr"])
    }

    @Test
    fun moovAfterMdatKeepsOffsetsAndReplacesOldUdta() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        val mdat = box("mdat", ByteArray(16) { 7 })
        val original = ftyp + mdat + moov(stco(ftyp.size + 8), withOldUdta = true)
        val tagged = Mp4Tags.apply(original, tags)
        assertEquals(listOf(ftyp.size + 8), readStco(tagged))
        assertArrayEquals(original.copyOfRange(0, ftyp.size + mdat.size), tagged.copyOfRange(0, ftyp.size + mdat.size))
        assertEquals(-1, String(tagged, Charsets.ISO_8859_1).indexOf("xyz "))
        // Tagging twice gives the same file (no growing udta).
        assertArrayEquals(tagged, Mp4Tags.apply(tagged, tags))
    }
}
