/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented placeholder lyrics only — never real song texts. */
class LyricsTextTest {
    private val ttml =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line" xml:lang="de">""" +
            """<head><metadata><iTunesMetadata leadingSilence="1.0"><songwriters><songwriter>Niemand</songwriter></songwriters></iTunesMetadata></metadata></head>""" +
            """<body dur="1:00.000"><div begin="1.000" end="30.000">""" +
            """<p begin="1.000" end="4.000">Erste &amp; Platzhalterzeile</p>""" +
            """<p begin="4.000" end="8.000"><span begin="4.0" end="5.0">Zweite</span> <span begin="5.0" end="6.0">Zeile</span>""" +
            """<span ttm:role="x-translation" xml:lang="en">Second line</span></p>""" +
            """<p begin="8.000" end="9.000"></p>""" +
            """</div></body></tt>"""

    @Test
    fun ttmlBecomesLinesWithoutMarkup() {
        val lines = ResonaLyricsText.ttmlLines(ttml)
        assertEquals(listOf("Erste & Platzhalterzeile", "Zweite Zeile"), lines)
        assertTrue(ResonaLyricsText.looksLikeTtml(ttml))
    }

    @Test
    fun selfSyncNeverShowsMarkup() {
        val lines = TapSync.lines(ttml)
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains("<") || it.contains("xmlns") })
    }

    @Test
    fun lrcEnhancedLrcAndPlain() {
        assertEquals(listOf("Eins", "Zwei"), ResonaLyricsText.lines("[00:01.00]Eins\n[00:02.00]Zwei"))
        assertEquals(listOf("Drei Vier"), ResonaLyricsText.lines("[00:01.00]<00:01.00>Drei <00:01.50>Vier"))
        assertEquals(listOf("Nur Text", "Noch eine Zeile"), ResonaLyricsText.lines("Nur Text\n\nNoch eine Zeile\n"))
        assertFalse(ResonaLyricsText.looksLikeTtml("[00:01.00]Eins"))
    }

    @Test
    fun editableKeepsNonTtmlAndNeverReturnsXml() {
        val lrc = "[00:01.00]Eins"
        assertEquals(lrc, ResonaLyricsText.editable(lrc))
        val editable = ResonaLyricsText.editable(ttml)
        assertFalse(editable.contains("<tt") || editable.contains("<p") || editable.contains("xmlns"))
        assertTrue(editable.contains("Platzhalterzeile"))
    }
}
