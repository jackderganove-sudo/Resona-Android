/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AISyncWindowsTest {
    @Test
    fun fixedCoversTheWholeSong() {
        val windows = AISyncWindows.fixed(70.0, 30.0)
        assertEquals(listOf(AudioWindow(0.0, 30.0), AudioWindow(30.0, 60.0), AudioWindow(60.0, 70.0)), windows)
    }

    @Test
    fun speechSkipsIntroAndGaps() {
        val windows = AISyncWindows.fromSpeech(listOf(20.0 to 25.0, 26.0 to 40.0, 100.0 to 110.0), 200.0, 30.0)
        assertEquals(listOf(AudioWindow(20.0, 40.0), AudioWindow(100.0, 110.0)), windows)
    }

    @Test
    fun windowsNeverExceedTheLength() {
        val windows = AISyncWindows.fromSpeech(listOf(0.0 to 95.0, 96.0 to 120.0), 120.0, 30.0)
        assertTrue(windows.all { it.seconds <= 30.0 })
        assertEquals(0.0, windows.first().start, 0.0)
        assertEquals(120.0, windows.last().end, 0.0)
    }

    @Test
    fun clampsToTheSongAndDropsEmptySegments() {
        val windows = AISyncWindows.fromSpeech(listOf(-1.0 to 5.0, 50.0 to 50.0, 58.0 to 70.0), 60.0, 30.0)
        assertEquals(listOf(AudioWindow(0.0, 5.0), AudioWindow(58.0, 60.0)), windows)
    }

    @Test
    fun linesSkipLongGapsAndKeepPadding() {
        val lines = listOf(AlignedLine("a", 20.0), AlignedLine("b", 24.0), AlignedLine("c", 90.0))
        val windows = AISyncWindows.fromLines(lines, 200.0, 30.0, pad = 3.0, lineMax = 10.0)
        assertEquals(listOf(AudioWindow(17.0, 37.0), AudioWindow(87.0, 103.0)), windows)
    }

    @Test
    fun linesWithoutTimesGiveNoWindows() {
        assertTrue(AISyncWindows.fromLines(listOf(AlignedLine("a", null)), 100.0).isEmpty())
    }
}
