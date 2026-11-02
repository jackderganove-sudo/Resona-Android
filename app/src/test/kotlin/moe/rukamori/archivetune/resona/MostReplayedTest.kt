/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MostReplayedTest {
    @Test
    fun dropIsThePeakAfterTheIntroMinusThreeSeconds() {
        val values = MutableList(100) { 0.1 }
        values[2] = 1.0 // intro peak (first 6 %) is ignored
        values[60] = 0.9
        val heatmap = Heatmap(values, durationSeconds = 200.0)
        assertEquals(60 * 2.0 - 3, heatmap.dropTimeSeconds!!, 0.001)
    }

    @Test
    fun tooFewValuesHaveNoDrop() {
        assertNull(Heatmap(listOf(0.1, 0.9, 0.2), 180.0).dropTimeSeconds)
    }
}
