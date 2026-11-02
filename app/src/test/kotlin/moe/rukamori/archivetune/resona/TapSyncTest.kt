/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Test

/** Invented placeholder lines only. */
class TapSyncTest {
    @Test
    fun linesWithoutTimestampsAndBlankLines() {
        assertEquals(
            listOf("Erste Zeile", "Zweite Zeile"),
            TapSync.lines("[00:01.00]Erste Zeile\n\n   \n[00:05.00]Zweite Zeile\n"),
        )
    }

    @Test
    fun buildsLrcFromTappedTimes() {
        val lrc = TapSync.buildLrc(listOf("Eins", "Zwei", "Drei"), listOf(1_234L, 65_996L))
        assertEquals("[00:01.23]Eins\n[01:06.00]Zwei", lrc)
    }
}
