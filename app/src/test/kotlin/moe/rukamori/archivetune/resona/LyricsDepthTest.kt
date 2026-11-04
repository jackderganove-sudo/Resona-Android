/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Depth by distance: sharp at the active line, monotonic, no "sharp again" further away. */
class LyricsDepthTest {
    private val bands = LyricsDepth.bands(anchorTop = 700f, rowHeight = 120f)

    private fun distance(y: Float) = maxOf(0f, bands.top - y, y - bands.bottom)

    @Test
    fun activeLineIsSharp() {
        assertEquals(1f, LyricsDepth.share(distance(700f), bands.sharpEnd), 0f)
        assertEquals(1f, LyricsDepth.share(distance(700f + 2 * 120f), bands.sharpEnd), 0f)
    }

    @Test
    fun sharpnessFallsMonotonicallyBothWays() {
        for (direction in listOf(-1, 1)) {
            var previousSharp = 2f
            var previousNear = 2f
            for (step in 0..40) {
                val y = (if (direction > 0) bands.bottom else bands.top) + direction * step * 40f
                val sharp = LyricsDepth.share(distance(y), bands.sharpEnd)
                val near = LyricsDepth.share(distance(y), bands.nearEnd)
                assertTrue(sharp <= previousSharp)
                assertTrue(near <= previousNear)
                assertTrue("near blur reaches further than sharp", near >= sharp)
                previousSharp = sharp
                previousNear = near
            }
        }
    }

    @Test
    fun farLinesOnlyKeepTheStrongestBlur() {
        assertEquals(0f, LyricsDepth.share(bands.nearEnd, bands.sharpEnd), 0f)
        assertEquals(0f, LyricsDepth.share(bands.nearEnd + 2000f, bands.nearEnd), 0f)
    }

    @Test
    fun bandStaysInsideTheList() {
        val nearTop = LyricsDepth.bands(anchorTop = 10f, rowHeight = 120f)
        assertEquals(0f, nearTop.top, 0f)
        assertTrue(nearTop.bottom > nearTop.top)
    }
}
