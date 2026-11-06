/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AISyncPartialTest {
    private val texts = listOf("placeholder lantern river", "invented maple window", "made up copper garden", "sample velvet harbor")

    /** Invented text, spoken at the given line starts. */
    private fun words(starts: List<Double>, count: Int = texts.size): List<RecognizedWord> =
        texts.take(count).zip(starts).flatMap { (line, start) ->
            line.split(' ').mapIndexed { i, word -> RecognizedWord(" $word", start + i * 0.5, start + i * 0.5 + 0.4) }
        }

    @Test
    fun plainNeedsTwoFoundLines() {
        assertNull(AISyncPartial.lrc(texts, emptyList(), words(listOf(10.0), 1), 100.0))
        assertNotNull(AISyncPartial.lrc(texts, emptyList(), words(listOf(10.0, 20.0), 2), 100.0))
    }

    @Test
    fun syncedNeedsAFit() {
        val synced = texts.mapIndexed { i, text -> AlignedLine(text, 10.0 + i * 10) }
        assertNull(AISyncPartial.lrc(texts, synced, emptyList(), 100.0))
    }

    @Test
    fun previewOnlyForTheSameSongAndDroppedOnClear() {
        ResonaLyricsPreview.show("song", "[00:01.00]placeholder")
        assertEquals("[00:01.00]placeholder", ResonaLyricsPreview.forSong("song"))
        assertNull(ResonaLyricsPreview.forSong("other"))
        ResonaLyricsPreview.show("song", null)
        assertTrue(ResonaLyricsPreview.forSong("song") != null)
        ResonaLyricsPreview.clear()
        assertNull(ResonaLyricsPreview.forSong("song"))
    }
}
