/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartDownloadsTest {
    @Test
    fun downloadsMissingTopSongsAndRemovesOnlyOwnDroppedOnes() {
        val plan = SmartDownloads.plan(
            mostPlayed = listOf("a", "b", "c"),
            liked = listOf("c", "d", "e"),
            downloaded = setOf("b", "x", "old"),
            previousSmart = setOf("b", "old"),
            maxSongs = 4,
        )
        assertEquals(listOf("a", "c", "d"), plan.toDownload)
        assertEquals(setOf("old"), plan.toRemove)
        assertEquals(setOf("a", "b", "c", "d"), plan.smartIds)
    }

    @Test
    fun manualDownloadsInTheSelectionDoNotBecomeSmart() {
        val plan = SmartDownloads.plan(listOf("m"), emptyList(), downloaded = setOf("m"), previousSmart = emptySet())
        assertEquals(emptyList<String>(), plan.toDownload)
        assertEquals(emptySet<String>(), plan.smartIds)
    }
}
