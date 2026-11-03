/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the iPad's CanvasColorTests (synthetic frames). */
class ColorQuantizerTest {
    private fun frame(): IntArray {
        val side = 24
        return IntArray(side * side) { i ->
            val x = i % side
            val y = i / side
            when {
                y < 2 -> 0xFF000000.toInt()
                x < side / 2 -> (0xFF shl 24) or (220 shl 16) or (30 shl 8) or 30
                else -> (0xFF shl 24) or (20 shl 16) or (40 shl 8) or 200
            }
        }
    }

    @Test
    fun findsTheMainColours() {
        val colors = ResonaColorQuantizer.dominant(frame())
        assertEquals(4, colors.size)
        assertTrue("red", colors.any { it.r > 0.7 && it.b < 0.3 })
        assertTrue("blue", colors.any { it.b > 0.6 && it.r < 0.3 })
    }

    @Test
    fun readableLimits() {
        val out = ResonaColorQuantizer.readable(listOf(ResonaColorQuantizer.Rgb(1.0, 1.0, 0.0), ResonaColorQuantizer.Rgb(0.0, 0.0, 0.0)))
        assertTrue(out[0].max <= 0.6201)
        assertTrue(out[0].saturation <= 0.8501)
        assertTrue(out[1].max >= 0.1199)
    }
}
