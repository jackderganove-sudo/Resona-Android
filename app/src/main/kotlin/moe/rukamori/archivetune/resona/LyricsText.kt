/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import moe.rukamori.archivetune.lyrics.LyricsUtils
import java.util.Locale

/**
 * Lyrics as readable text for everything a person sees or edits (self-sync,
 * lyrics editor, translation input): TTML, LRC, enhanced LRC, QRC and plain
 * text all become lines; TTML is never shown as XML. When ArchiveTune's
 * parsers can't run (or return nothing), a small TTML reader takes over.
 */
object ResonaLyricsText {
    private val pRegex = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val auxSpanRegex = Regex(
        """<span\b[^>]*role\s*=\s*"x-(translation|roman|romanization)"[^>]*>.*?</span>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespace = Regex("""\s+""")

    fun looksLikeTtml(lyrics: String): Boolean {
        val trimmed = lyrics.trimStart('﻿', ' ', '\n', '\r', '\t')
        return trimmed.startsWith("<") && (trimmed.contains("<tt", ignoreCase = true) || trimmed.contains("<p", ignoreCase = true))
    }

    /** Visible text lines, no timestamps, no markup, no empty lines. */
    fun lines(lyrics: String): List<String> {
        if (lyrics.isBlank()) return emptyList()
        val parsed = runCatching { LyricsUtils.displayLyricsText(lyrics) }.getOrNull()
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (parsed.isNotEmpty() && parsed.none { it.startsWith("<") || it.contains("xmlns") }) return parsed
        return if (looksLikeTtml(lyrics)) ttmlLines(lyrics) else LyricsTempo.stripTimestamps(lyrics).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Text lines of TTML <p> elements (translation/romanization spans dropped, entities decoded). */
    fun ttmlLines(xml: String): List<String> =
        pRegex.findAll(xml).map { match ->
            val inner = auxSpanRegex.replace(match.groupValues[1], "")
            decode(tagRegex.replace(inner, "")).replace(whitespace, " ").trim()
        }.filter { it.isNotEmpty() }.toList()

    private fun decode(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    /**
     * For the lyrics editor and the translation input: TTML becomes LRC
     * (line times, word times as enhanced LRC when present) so nothing is
     * shown as XML and the timing survives; other formats stay as they are.
     */
    fun editable(lyrics: String): String {
        if (!looksLikeTtml(lyrics)) return lyrics
        val entries = runCatching { LyricsUtils.parseTtml(lyrics) }.getOrNull().orEmpty()
        if (entries.isEmpty()) return ttmlLines(lyrics).joinToString("\n")
        return entries.joinToString("\n") { entry ->
            val words = entry.words.orEmpty().filterNot { it.isBackground }
            val body =
                if (words.isEmpty()) {
                    entry.text
                } else {
                    // Word pieces either carry their own spaces (then they add up to
                    // the line text) or are bare words that need one between them.
                    val glued = words.joinToString("") { it.text }.replace(whitespace, " ").trim() ==
                        entry.text.replace(whitespace, " ").trim()
                    words.joinToString(if (glued) "" else " ") { word ->
                        stamp(word.startTime * 1000, '<', '>') + (if (glued) word.text else word.text.trim())
                    }.trim()
                }
            stamp(entry.time.toDouble(), '[', ']') + body
        }
    }

    private fun stamp(ms: Double, open: Char, close: Char): String {
        val centis = Math.round(ms / 10).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%c%02d:%02d.%02d%c", open, centis / 6000, centis / 100 % 60, centis % 100, close)
    }
}
