/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AI sync for Android — the same algorithms as Resona for iPad
 * (LyricsAligner.swift, TempoCalibration.swift), so both apps give the
 * same result for the same recognition:
 * - forced alignment of recognized words onto known lyrics (global
 *   Needleman–Wunsch with fuzzy + phonetic word similarity),
 * - outliers removed by the longest non-decreasing run of matched times,
 *   unmatched words interpolated, monotonic times,
 * - tempo calibration by Theil–Sen (time_here = factor × time_lyrics + offset),
 * - partial sync for remixes (every place a line is sung).
 */
data class RecognizedWord(val text: String, val start: Double, val end: Double)

data class AlignedWord(val text: String, val start: Double, val end: Double)

data class AlignedLine(val text: String, val startTime: Double?, val words: List<AlignedWord>? = null)

data class TimeAnchor(val original: Double, val observed: Double)

data class TempoCalibration(val factor: Double, val offset: Double, val anchors: Int, val rms: Double)

object LyricsAligner {
    data class Result(val lines: List<AlignedLine>, val matchedRatio: Double)

    private val canonical = mapOf(
        "cause" to "because", "cuz" to "because", "coz" to "because", "bc" to "because",
        "til" to "until", "till" to "until", "ya" to "you", "yah" to "you", "u" to "you",
        "em" to "them", "tho" to "though", "thru" to "through", "ur" to "your", "yall" to "youall",
        "nothin" to "nothing", "somethin" to "something", "everythin" to "everything",
        "lovin" to "loving", "goin" to "going", "feelin" to "feeling", "tryin" to "trying",
        "ok" to "okay", "imma" to "ima", "oh" to "o", "ooh" to "o", "yeah" to "yea", "yea" to "yea",
    )

    private val numberWords = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen", "twenty",
    )

    const val MATCH_THRESHOLD = 0.5

    fun normalize(word: String): String {
        val folded = Normalizer.normalize(word, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val plain = folded.lowercase().filter { it.isLetterOrDigit() }
        plain.toIntOrNull()?.let { if (it in numberWords.indices) return numberWords[it] }
        return canonical[plain] ?: plain
    }

    fun phoneticKey(word: String): String {
        var s = word
        for ((from, to) in listOf(
            "ght" to "t", "ph" to "f", "ck" to "k", "qu" to "kw", "wh" to "w", "th" to "t",
            "c" to "k", "q" to "k", "z" to "s", "v" to "f", "y" to "i",
        )) {
            s = s.replace(from, to)
        }
        val first = s.firstOrNull() ?: return ""
        val key = StringBuilder().append(first)
        for (char in s.drop(1)) {
            if (char !in "aeiou" && key.last() != char) key.append(char)
        }
        return key.toString()
    }

    fun wordSimilarity(a: String, b: String): Double {
        val spelled = similarity(a, b)
        if (spelled >= 0.9 || a.length < 4 || b.length < 4) return spelled
        return max(spelled, min(0.9, similarity(phoneticKey(a), phoneticKey(b))))
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
            }
            val swap = previous; previous = current; current = swap
        }
        return 1 - previous[b.length].toDouble() / max(a.length, b.length)
    }

    private fun splitWords(line: String) = line.split(' ', '\t').filter { it.isNotEmpty() }

    fun align(lines: List<String>, recognized: List<RecognizedWord>, duration: Double): Result {
        data class LyricWord(val line: Int, val text: String, val norm: String)
        val lyric = lines.flatMapIndexed { index, line -> splitWords(line).map { LyricWord(index, it, normalize(it)) } }
        val heard = recognized.filter { normalize(it.text).isNotEmpty() }
        val heardNorm = heard.map { normalize(it.text) }
        val n = lyric.size
        if (n == 0) return Result(emptyList(), 0.0)

        val match = matchIndices(lyric.map { it.norm }, heardNorm)
        val starts = arrayOfNulls<Double>(n)
        val ends = arrayOfNulls<Double>(n)
        for (k in 0 until n) match[k]?.let { hit -> starts[k] = heard[hit].start; ends[k] = heard[hit].end }
        val matched = match.count { it != null }
        val anchors = longestNonDecreasing(starts.toList()).toSet()
        for (k in 0 until n) if (starts[k] != null && k !in anchors) { starts[k] = null; ends[k] = null }

        var k = 0
        while (k < n) {
            if (starts[k] != null) { k++; continue }
            var e = k
            while (e < n && starts[e] == null) e++
            val from = if (k > 0) (ends[k - 1] ?: starts[k - 1] ?: 0.0) else 0.0
            val to = if (e < n) (starts[e] ?: duration) else max(from + 0.5, min(duration, from + (e - k) * 0.5))
            val weights = (k until e).map { max(1, lyric[it].norm.length).toDouble() }
            val total = weights.sum()
            var t = from
            for ((offset, idx) in (k until e).withIndex()) {
                val span = max(0.05, (to - from) * weights[offset] / max(1.0, total))
                starts[idx] = t; ends[idx] = t + span; t += span
            }
            k = e
        }
        val minWord = 0.05
        for (idx in 1 until n) {
            val prev = starts[idx - 1]; val cur = starts[idx]
            if (prev != null && cur != null && cur < prev + minWord) starts[idx] = min(prev + minWord, max(prev, duration))
        }

        val result = lines.mapIndexed { lineIndex, text ->
            val indices = lyric.indices.filter { lyric[it].line == lineIndex }
            val first = indices.firstOrNull()
            val start = first?.let { starts[it] }
            if (first == null || start == null) {
                AlignedLine(text, null)
            } else {
                val words = indices.mapIndexed { position, idx ->
                    val s = starts[idx] ?: start
                    var e = ends[idx] ?: (s + 0.3)
                    if (idx + 1 < n) starts[idx + 1]?.let { next -> e = min(e, max(s + 0.05, next)) }
                    val word = lyric[idx].text + if (position < indices.size - 1) " " else ""
                    AlignedWord(word, s, max(e, s + 0.05))
                }
                AlignedLine(text, start, if (words.size > 1) words else null)
            }
        }
        return Result(monotonicTiming(result), matched.toDouble() / n)
    }

    /** Global alignment: for every lyric word the index of the recognized word it matches, or null. */
    fun matchIndices(lyricNorm: List<String>, heardNorm: List<String>): List<Int?> {
        val n = lyricNorm.size
        val m = heardNorm.size
        if (n == 0) return emptyList()
        val gap = -0.4
        val score = Array(n + 1) { DoubleArray(m + 1) }
        val move = Array(n + 1) { ByteArray(m + 1) }
        for (i in 1..n) { score[i][0] = i * gap; move[i][0] = 2 }
        for (j in 1..m) { score[0][j] = j * gap * 0.5; move[0][j] = 3 }
        for (i in 1..n) {
            for (j in 1..m) {
                val sim = wordSimilarity(lyricNorm[i - 1], heardNorm[j - 1])
                val matchScore = if (sim >= MATCH_THRESHOLD) sim * 2 else -0.6
                val diag = score[i - 1][j - 1] + matchScore
                val up = score[i - 1][j] + gap
                val left = score[i][j - 1] + gap * 0.5
                if (diag >= up && diag >= left) { score[i][j] = diag; move[i][j] = 1 }
                else if (up >= left) { score[i][j] = up; move[i][j] = 2 }
                else { score[i][j] = left; move[i][j] = 3 }
            }
        }
        val match = arrayOfNulls<Int>(n)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val step = if (i > 0 && j > 0) move[i][j].toInt() else if (i > 0) 2 else 3
            when (step) {
                1 -> {
                    if (wordSimilarity(lyricNorm[i - 1], heardNorm[j - 1]) >= MATCH_THRESHOLD) match[i - 1] = j - 1
                    i--; j--
                }
                2 -> i--
                else -> j--
            }
        }
        return match.toList()
    }

    /** Pairs (time in the given lyrics, time heard) from confidently recognized words (≥ 0.85, 3+ letters). */
    fun anchors(lines: List<AlignedLine>, recognized: List<RecognizedWord>): List<TimeAnchor> {
        val norms = ArrayList<String>()
        val times = ArrayList<Double?>()
        for (line in lines) {
            val words = line.words
            if (!words.isNullOrEmpty()) {
                for (word in words) {
                    word.text.split(' ').filter { it.isNotEmpty() }.forEachIndexed { k, part ->
                        norms += normalize(part); times += if (k == 0) word.start else null
                    }
                }
            } else {
                splitWords(line.text).forEachIndexed { k, part ->
                    norms += normalize(part); times += if (k == 0) line.startTime else null
                }
            }
        }
        val heard = recognized.filter { normalize(it.text).isNotEmpty() }
        val heardNorm = heard.map { normalize(it.text) }
        val match = matchIndices(norms, heardNorm)
        return match.mapIndexedNotNull { k, hit ->
            val original = times[k]
            if (hit == null || original == null || norms[k].length < 3 || wordSimilarity(norms[k], heardNorm[hit]) < 0.85) null
            else TimeAnchor(original, heard[hit].start)
        }
    }

    data class Occurrence(val lineIndex: Int, val start: Double, val words: List<AlignedWord>, val matched: Int)

    /** Every place in the recording where a lyric line is sung (≥ 70 % of its words in order) — remixes. */
    fun lineOccurrences(lines: List<String>, recognized: List<RecognizedWord>, minShare: Double = 0.7): List<Occurrence> {
        val heard = recognized.filter { normalize(it.text).isNotEmpty() }.sortedBy { it.start }
        val heardNorm = heard.map { normalize(it.text) }
        val candidates = ArrayList<Pair<Occurrence, Double>>()
        lines.forEachIndexed { lineIndex, line ->
            val parts = splitWords(line)
            val norms = parts.map(::normalize)
            if (norms.size < 2) return@forEachIndexed
            val window = norms.size * 2 + 2
            for (startIndex in heardNorm.indices) {
                if (wordSimilarity(norms[0], heardNorm[startIndex]) < MATCH_THRESHOLD &&
                    wordSimilarity(norms[1], heardNorm[startIndex]) < MATCH_THRESHOLD
                ) continue
                var cursor = startIndex
                val hits = norms.map { norm ->
                    var found: Int? = null
                    var probe = cursor
                    while (probe < min(heardNorm.size, startIndex + window)) {
                        if (wordSimilarity(norm, heardNorm[probe]) >= MATCH_THRESHOLD) { found = probe; break }
                        probe++
                    }
                    if (found != null) cursor = found + 1
                    found
                }
                val matchedHits = hits.filterNotNull()
                if (matchedHits.size.toDouble() / norms.size < minShare || matchedHits.isEmpty()) continue
                val first = matchedHits.first()
                val last = matchedHits.last()
                val words = ArrayList<AlignedWord>()
                parts.forEachIndexed { k, part ->
                    val text = part + if (k < parts.size - 1) " " else ""
                    val hit = hits[k]
                    if (hit != null) {
                        words += AlignedWord(text, heard[hit].start, max(heard[hit].end, heard[hit].start + 0.05))
                    } else {
                        val prevEnd = words.lastOrNull()?.end ?: heard[first].start
                        words += AlignedWord(text, prevEnd, prevEnd + 0.2)
                    }
                }
                for (w in 1 until words.size) {
                    if (words[w].start < words[w - 1].start + 0.05) {
                        val start = words[w - 1].start + 0.05
                        words[w] = AlignedWord(words[w].text, start, max(words[w].end, start + 0.05))
                    }
                }
                candidates += Occurrence(lineIndex, heard[first].start, words, matchedHits.size) to heard[last].end
            }
        }
        val ranked = candidates.sortedWith(
            compareByDescending<Pair<Occurrence, Double>> { it.first.matched.toDouble() / max(1, it.first.words.size) }
                .thenByDescending { it.first.matched },
        )
        val accepted = ArrayList<Pair<Occurrence, Double>>()
        for (candidate in ranked) {
            if (accepted.none { candidate.first.start < it.second && it.first.start < candidate.second }) accepted += candidate
        }
        return accepted.map { it.first }.sortedBy { it.start }
    }

    /** Indices of the longest non-decreasing subsequence of the non-null values (O(n log n)). */
    fun longestNonDecreasing(values: List<Double?>): List<Int> {
        val tailValues = ArrayList<Double>()
        val tailIndices = ArrayList<Int>()
        val previous = IntArray(values.size) { -1 }
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            var lo = 0
            var hi = tailValues.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (tailValues[mid] <= value) lo = mid + 1 else hi = mid
            }
            if (lo > 0) previous[index] = tailIndices[lo - 1]
            if (lo == tailValues.size) { tailValues += value; tailIndices += index } else { tailValues[lo] = value; tailIndices[lo] = index }
        }
        val result = ArrayList<Int>()
        var cursor = tailIndices.lastOrNull() ?: -1
        while (cursor >= 0) { result += cursor; cursor = previous[cursor] }
        return result.reversed()
    }

    /** Line starts never go backwards (≥ 0.2 s apart), words ≥ 0.05 s apart. */
    fun monotonicTiming(lines: List<AlignedLine>, minLine: Double = 0.2, minWord: Double = 0.05): List<AlignedLine> {
        var lastLine: Double? = null
        var lastWord: Double? = null
        return lines.map { line ->
            var start = line.startTime ?: return@map line
            lastLine?.let { if (start < it + minLine) start = it + minLine }
            lastLine = start
            val words = line.words?.mapIndexed { index, word ->
                var wordStart = max(word.start, if (index == 0) start else word.start)
                lastWord?.let { if (wordStart < it + minWord) wordStart = it + minWord }
                if (index == 0) wordStart = max(wordStart, start)
                lastWord = wordStart
                AlignedWord(word.text, wordStart, max(word.end, wordStart + minWord))
            }
            if (start == line.startTime && words == line.words) line else line.copy(startTime = start, words = words)
        }
    }
}

object TempoCalibrator {
    const val MIN_ANCHORS = 6

    sealed interface Outcome {
        data class Fitted(val calibration: TempoCalibration) : Outcome
        data class TooFewAnchors(val count: Int) : Outcome
        data class PoorFit(val calibration: TempoCalibration) : Outcome
    }

    /** Robust fit observed = factor × original + offset (Theil–Sen, then least squares on inliers ≤ 1 s). */
    fun fit(anchors: List<TimeAnchor>): Outcome {
        if (anchors.size < MIN_ANCHORS) return Outcome.TooFewAnchors(anchors.size)
        val step = max(1, anchors.size / 400)
        val points = anchors.indices.step(step).map { anchors[it] }
        val slopes = ArrayList<Double>()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val dx = points[j].original - points[i].original
            if (abs(dx) > 1) slopes += (points[j].observed - points[i].observed) / dx
        }
        if (slopes.isEmpty()) return Outcome.TooFewAnchors(anchors.size)
        val slope = median(slopes)
        val intercept = median(anchors.map { it.observed - slope * it.original })
        val inliers = anchors.filter { abs(it.observed - (slope * it.original + intercept)) <= 1.0 }
        if (inliers.size < MIN_ANCHORS) return Outcome.TooFewAnchors(inliers.size)
        val (factor, offset) = leastSquares(inliers) ?: (slope to intercept)
        val rms = sqrt(inliers.map { val r = it.observed - (factor * it.original + offset); r * r }.sum() / inliers.size)
        val calibration = TempoCalibration(factor, offset, inliers.size, rms)
        val originals = inliers.map { it.original }
        val spread = (originals.maxOrNull() ?: 0.0) - (originals.minOrNull() ?: 0.0)
        val total = (anchors.maxOf { it.original }) - (anchors.minOf { it.original })
        return if (factor in 0.6..1.6 && rms <= 0.6 && spread >= max(20.0, total * 0.3)) Outcome.Fitted(calibration) else Outcome.PoorFit(calibration)
    }

    fun apply(lines: List<AlignedLine>, calibration: TempoCalibration): List<AlignedLine> {
        fun map(t: Double) = max(0.0, calibration.factor * t + calibration.offset)
        return lines.map { line ->
            line.copy(startTime = line.startTime?.let(::map), words = line.words?.map { AlignedWord(it.text, map(it.start), map(it.end)) })
        }
    }

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun leastSquares(points: List<TimeAnchor>): Pair<Double, Double>? {
        val n = points.size.toDouble()
        val sx = points.sumOf { it.original }
        val sy = points.sumOf { it.observed }
        val sxx = points.sumOf { it.original * it.original }
        val sxy = points.sumOf { it.original * it.observed }
        val denominator = n * sxx - sx * sx
        if (abs(denominator) <= 1e-9) return null
        val factor = (n * sxy - sx * sy) / denominator
        return factor to (sy - factor * sx) / n
    }
}
