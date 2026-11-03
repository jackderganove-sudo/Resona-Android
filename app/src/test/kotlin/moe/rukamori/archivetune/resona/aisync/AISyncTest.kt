/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Same synthetic cases as the iPad's LyricsAlignerTests / TempoCalibrationTests (placeholder texts only). */
class AISyncTest {
    private val lines = listOf("la la erste Zeile", "zweite Zeile mit Wort Wort", "dritte Reihe hier", "vierte Zeile endet bald")

    private fun recognition(lines: List<String>, start: Double = 2.0): Pair<List<RecognizedWord>, List<Double>> {
        var t = start
        val words = ArrayList<RecognizedWord>()
        val starts = ArrayList<Double>()
        for (line in lines) {
            starts += t
            for (word in line.split(" ")) { words += RecognizedWord(word, t, t + 0.35); t += 0.4 }
            t += 1
        }
        return words to starts
    }

    private fun assertMonotonic(result: LyricsAligner.Result) {
        val starts = result.lines.flatMap { line -> line.words?.map { it.start } ?: listOfNotNull(line.startTime) }
        starts.zipWithNext().forEach { (a, b) -> assertTrue("word times must not go backwards", a <= b + 1e-9) }
        result.lines.flatMap { it.words.orEmpty() }.forEach { assertTrue(it.end > it.start) }
    }

    @Test fun exactRecognition() {
        val (words, starts) = recognition(lines)
        val result = LyricsAligner.align(lines, words, 60.0)
        assertEquals(1.0, result.matchedRatio, 0.001)
        result.lines.zip(starts).forEach { (line, expected) -> assertEquals(expected, line.startTime!!, 0.001) }
        assertEquals(lines, result.lines.map { it.text })
        assertMonotonic(result)
    }

    @Test fun missingWordsAreInterpolated() {
        val (all, starts) = recognition(lines)
        val words = all.filterNot { it.text == "mit" || it.text == "hier" }
        val result = LyricsAligner.align(lines, words, 60.0)
        val total = lines.flatMap { it.split(" ") }.size
        assertEquals((total - 2).toDouble() / total, result.matchedRatio, 0.001)
        result.lines.zip(starts).forEach { (line, expected) -> assertEquals(expected, line.startTime!!, 0.001) }
        assertMonotonic(result)
    }

    @Test fun misspelledWordsStillMatch() {
        val (words, starts) = recognition(listOf("la la ersde Zeile", "zwaite Zeile mit Wort Wort", "dritt Reihe hier", "vierte Zeile endett bald"))
        val result = LyricsAligner.align(lines, words, 60.0)
        assertEquals(1.0, result.matchedRatio, 0.001)
        result.lines.zip(starts).forEach { (line, expected) -> assertEquals(expected, line.startTime!!, 0.001) }
        assertEquals(lines, result.lines.map { it.text })
    }

    @Test fun normalization() {
        assertEquals("zeile", LyricsAligner.normalize("Zéile,"))
        assertEquals("worts", LyricsAligner.normalize("Wort's"))
        assertEquals("because", LyricsAligner.normalize("'Cause"))
        assertEquals("two", LyricsAligner.normalize("2"))
        assertEquals("nothing", LyricsAligner.normalize("nothin'"))
        assertEquals(0.0, LyricsAligner.similarity("", ""), 0.0)
        assertTrue(LyricsAligner.wordSimilarity("nite", "night") >= LyricsAligner.MATCH_THRESHOLD)
        assertTrue(LyricsAligner.wordSimilarity("phone", "fone") >= LyricsAligner.MATCH_THRESHOLD)
    }

    @Test fun outlierTimesDoNotMakeLinesJump() {
        val (base, starts) = recognition(lines)
        val words = base.map {
            when (it.text) {
                "dritte" -> RecognizedWord(it.text, 0.3, 0.6)
                "vierte" -> RecognizedWord(it.text, 55.0, 55.3)
                else -> it
            }
        }
        val lineStarts = LyricsAligner.align(lines, words, 60.0).lines.mapNotNull { it.startTime }
        assertEquals(lines.size, lineStarts.size)
        assertEquals(lineStarts.sorted(), lineStarts)
        assertEquals(starts[2], lineStarts[2], 1.2)
        assertEquals(starts[3], lineStarts[3], 1.2)
    }

    @Test fun longestNonDecreasing() {
        val values = listOf(1.0, null, 3.0, 2.0, 4.0, 0.0, 5.0, 5.0)
        val picked = LyricsAligner.longestNonDecreasing(values)
        assertEquals(5, picked.size)
        val pickedValues = picked.mapNotNull { values[it] }
        assertEquals(pickedValues.sorted(), pickedValues)
    }

    @Test fun monotonicTimingPullsBackwardLinesUp() {
        val fixed = LyricsAligner.monotonicTiming(
            listOf(AlignedLine("la la eins", 1.0), AlignedLine("la la zwei", 5.0), AlignedLine("la la drei", 3.0), AlignedLine("la la vier", 7.0), AlignedLine("ohne Zeit", null)),
        )
        fixed.mapNotNull { it.startTime }.zip(listOf(1.0, 5.0, 5.2, 7.0)).forEach { (got, want) -> assertEquals(want, got, 1e-9) }
        assertNull(fixed[4].startTime)
    }

    // Calibration

    private class Lcg(var state: ULong) {
        fun next(): Double {
            state = state * 6364136223846793005UL + 1442695040888963407UL
            return (state shr 11).toDouble() / (1UL shl 53).toDouble()
        }
    }

    private fun anchors(factor: Double, offset: Double, count: Int, span: Double, noise: Double, outliers: Double, seed: ULong = 42UL): List<TimeAnchor> {
        val random = Lcg(seed)
        return (0 until count).map { index ->
            val original = span * index / maxOf(1, count - 1)
            var observed = factor * original + offset + (random.next() - 0.5) * 2 * noise
            if (random.next() < outliers) observed = random.next() * span * 1.5
            TimeAnchor(original, observed)
        }
    }

    @Test fun fitsFactorAndOffsetDespiteNoiseAndOutliers() {
        val outcome = TempoCalibrator.fit(anchors(1.193, 0.4, 60, 180.0, 0.15, 0.2))
        val c = (outcome as? TempoCalibrator.Outcome.Fitted)?.calibration ?: return fail("expected a fit, got $outcome")
        assertEquals(1.193, c.factor, 0.003)
        assertEquals(0.4, c.offset, 0.25)
        assertTrue(c.rms < 0.2)
        assertTrue(c.anchors >= 40)
    }

    @Test fun fewButSpreadAnchorsAreEnough() {
        val c = (TempoCalibrator.fit(anchors(0.87, -1.2, 8, 200.0, 0.1, 0.0)) as? TempoCalibrator.Outcome.Fitted)?.calibration ?: return fail("expected a fit")
        assertEquals(0.87, c.factor, 0.004)
        assertEquals(-1.2, c.offset, 0.3)
    }

    @Test fun tooFewAndClustered() {
        assertEquals(TempoCalibrator.Outcome.TooFewAnchors(5), TempoCalibrator.fit(anchors(1.2, 0.0, 5, 180.0, 0.0, 0.0)))
        assertTrue(TempoCalibrator.fit(anchors(1.2, 0.0, 20, 10.0, 0.05, 0.0)) is TempoCalibrator.Outcome.PoorFit)
    }

    @Test fun lineOccurrencesFindRepeatsAndReordering() {
        val texts = listOf("alpha beta gamma delta", "epsilon zeta eta theta", "kappa lambda my ny")
        val recognized = ArrayList<RecognizedWord>()
        var t = 5.0
        fun sing(text: String) { for (w in text.split(" ")) { recognized += RecognizedWord(w, t, t + 0.3); t += 0.4 }; t += 2 }
        sing("kappa lambda my ny"); sing("ooh yeah"); sing("alpha beta gamma delta"); sing("kappa lambda my ny"); sing("kappa lambda ny")
        val occurrences = LyricsAligner.lineOccurrences(texts, recognized)
        assertEquals(listOf(2, 0, 2, 2), occurrences.map { it.lineIndex })
        assertEquals(5.0, occurrences.first().start, 0.001)
    }
}
