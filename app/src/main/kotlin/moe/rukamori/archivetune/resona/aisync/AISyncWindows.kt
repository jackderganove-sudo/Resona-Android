/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import kotlin.math.max
import kotlin.math.min

/** A stretch of the song to transcribe, in seconds. */
data class AudioWindow(val start: Double, val end: Double) {
    val seconds get() = end - start
}

object AISyncWindows {
    /** Whole song in equal pieces. */
    fun fixed(total: Double, length: Double = 30.0): List<AudioWindow> {
        val out = ArrayList<AudioWindow>()
        var at = 0.0
        while (at < total) {
            out += AudioWindow(at, min(total, at + length))
            at += length
        }
        return out
    }

    /**
     * Only around the lines the synced lyrics expect: each line from its start
     * (minus [pad], so slightly early lyrics stay covered) to the next line's
     * start, at most [lineMax] seconds. Instrumental gaps are skipped.
     */
    fun fromLines(lines: List<AlignedLine>, total: Double, length: Double = 30.0, pad: Double = 3.0, lineMax: Double = 10.0): List<AudioWindow> {
        val starts = lines.mapNotNull { it.startTime }.sorted()
        val merged = ArrayList<Pair<Double, Double>>()
        starts.forEachIndexed { index, start ->
            val next = starts.getOrNull(index + 1) ?: (start + lineMax)
            val from = start - pad
            val to = min(next, start + lineMax) + pad
            val last = merged.lastOrNull()
            if (last != null && from <= last.second) merged[merged.size - 1] = last.first to max(last.second, to) else merged += from to to
        }
        return fromSpeech(merged, total, length)
    }

    /**
     * Only where someone sings: speech segments joined into windows of at most
     * [length] seconds. A segment longer than that is split; the gaps are skipped.
     */
    fun fromSpeech(speech: List<Pair<Double, Double>>, total: Double, length: Double = 30.0): List<AudioWindow> {
        val out = ArrayList<AudioWindow>()
        var start = -1.0
        var end = -1.0
        for ((rawStart, rawEnd) in speech.sortedBy { it.first }) {
            var segmentStart = max(0.0, rawStart)
            val segmentEnd = min(total, rawEnd)
            if (segmentEnd <= segmentStart) continue
            if (start >= 0 && segmentEnd - start > length) {
                out += AudioWindow(start, end)
                start = -1.0
            }
            while (segmentEnd - segmentStart > length) {
                out += AudioWindow(segmentStart, segmentStart + length)
                segmentStart += length
            }
            if (start < 0) start = segmentStart
            end = segmentEnd
        }
        if (start >= 0) out += AudioWindow(start, end)
        return out
    }
}
