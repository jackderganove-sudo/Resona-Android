/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class CtcAlignerTest {
    private val words = "placeholder lantern river invented maple window".split(' ')

    /** Synthetic emissions: the given word starts, letters 3 frames each, blank elsewhere. */
    private fun emissions(wordStarts: List<Int>, frames: Int, chosen: List<String> = words): Array<FloatArray> {
        val top = Array(frames) { CtcAligner.BLANK }
        chosen.zip(wordStarts).forEach { (word, start) ->
            CtcAligner.letters(word).forEachIndexed { i, id ->
                for (f in 0 until 3) top[start + i * 3 + f] = id
            }
        }
        return Array(frames) { t ->
            FloatArray(32) { if (it == top[t]) ln(0.9f) else ln(0.1f / 31) }
        }
    }

    @Test
    fun lettersFoldUmlautsAndDropOther() {
        assertEquals(CtcAligner.letters("MUEDE").size - 1, CtcAligner.letters("müde").size)
        assertTrue(CtcAligner.letters("123 !?").isEmpty())
        assertEquals(CtcAligner.letters("strasse").toList(), CtcAligner.letters("straße").toList())
    }

    @Test
    fun separatorsOnlyBetweenWords() {
        val tokens = CtcAligner.tokens(listOf("ab", "--", "cd"))
        assertEquals(listOf(7, 24, 4, 19, 14), tokens.map { it.id })
    }

    @Test
    fun findsTheWordsWhereTheyAre() {
        val starts = listOf(100, 180, 260, 400, 480, 540)
        val found = CtcAligner.words(emissions(starts, 800), words, 0.0)
        assertEquals(words.size, found.size)
        found.zip(starts).forEach { (word, start) ->
            assertTrue("${word.start} vs $start", abs(word.start - start * CtcAligner.FRAME_SECONDS) <= 0.1)
            assertTrue(word.end > word.start)
        }
    }

    @Test
    fun timesAreMonotonic() {
        val found = CtcAligner.words(emissions(listOf(50, 120, 190, 260, 330, 400), 600), words, 0.0)
        assertTrue(found.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    @Test
    fun wordsTheModelDoesNotHearAreLeftOut() {
        // Only the first three words are in the audio.
        val found = CtcAligner.words(emissions(listOf(50, 130, 210), 400, words.take(3)), words, 0.5)
        assertTrue(found.size in 3..4)
        assertEquals(words.take(3), found.take(3).map { it.text })
    }

    @Test
    fun aFewFramesDoNotCrash() {
        val found = CtcAligner.words(Array(3) { FloatArray(32) { ln(1f / 32) } }, words, 0.0)
        assertTrue(found.size <= words.size)
    }

    @Test
    fun goesThroughTheSameAligner() {
        val starts = listOf(100, 180, 260, 400, 480, 540)
        val found = CtcAligner.words(emissions(starts, 800), words, 0.0)
        val lines = listOf("placeholder lantern river", "invented maple window")
        val aligned = LyricsAligner.align(lines, found, 16.0)
        assertEquals(1.0, aligned.matchedRatio, 1e-9)
        assertEquals(2.0, aligned.lines[0].startTime!!, 0.2)
        assertEquals(8.0, aligned.lines[1].startTime!!, 0.2)
    }
}
