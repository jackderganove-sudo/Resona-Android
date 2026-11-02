/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented placeholder lines only. */
class LyricsTempoTest {
    private val lrc = "[00:10.00]Erste Platzhalterzeile\n[01:00.00]<01:00.00>Zweite <01:02.50>Zeile\n[03:00.00]Letzte Zeile"

    @Test
    fun detectsVariantsAndCleansTitles() {
        assertTrue(LyricsTempo.isSpeedVariant("Erfundenes Lied (Slowed + Reverb)"))
        assertTrue(LyricsTempo.isSpeedVariant("Erfundenes Lied - sped up"))
        assertFalse(LyricsTempo.isSpeedVariant("Erfundenes Lied (Remix)"))
        assertEquals("Erfundenes Lied", LyricsTempo.cleanTitle("Erfundenes Lied (Slowed + Reverb)"))
        assertEquals("Erfundenes Lied", LyricsTempo.cleanTitle("Erfundenes Lied - super slowed"))
    }

    @Test
    fun scalesLineAndWordTimes() {
        // Original 200 s, slowed upload 250 s → factor 1.25.
        val scaled = LyricsTempo.adjusted(lrc, played = 250.0, original = 200.0, isVariant = true)
        assertEquals("[00:12.50]Erste Platzhalterzeile", scaled.lines()[0])
        assertEquals("[01:15.00]<01:15.00>Zweite <01:18.13>Zeile", scaled.lines()[1])
        assertEquals(225.0, LyricsTempo.lastTimestamp(scaled)!!, 0.01)
    }

    @Test
    fun leavesFittingLyricsAlone() {
        assertEquals(lrc, LyricsTempo.adjusted(lrc, played = 200.0, original = null, isVariant = false))
        assertEquals(lrc, LyricsTempo.adjusted(lrc, played = 200.0, original = 201.0, isVariant = true))
    }

    @Test
    fun dropsTimingThatCannotBeFixed() {
        val stripped = LyricsTempo.adjusted(lrc, played = 120.0, original = null, isVariant = false)
        assertEquals(listOf("Erste Platzhalterzeile", "Zweite Zeile", "Letzte Zeile"), stripped.lines())
    }
}
