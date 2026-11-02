/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Slowed / sped-up versions (Resona): providers often return the ORIGINAL's
 * synced lyrics. When the title is such a variant, or the last timestamp
 * runs past this upload, the original's length is looked up on LRCLIB and
 * every line and word time is scaled by played / original (only 0.6–1.5×).
 * Lyrics whose timing can't be fixed lose their timestamps instead of
 * running out of sync. Same rules as Resona for iPad.
 */
object LyricsTempo {
    private val variantRegex =
        Regex("""\b((super|ultra)\s+)?(slowed|sped\s*up|speed\s*up|nightcore|daycore|reverb)\b""", RegexOption.IGNORE_CASE)
    private val lineTime = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTime = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val originalCache = ConcurrentHashMap<String, Double>()

    fun isSpeedVariant(title: String) = variantRegex.containsMatchIn(title)

    /** "Song (Slowed + Reverb)" → "Song" (brackets naming the variant, then loose words). */
    fun cleanTitle(title: String): String {
        var result = title.replace(Regex("""\s*[(\[][^)\]]*(slowed|sped|speed\s*up|nightcore|daycore|reverb)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("""\s*[-–|]\s*((super|ultra)\s+)?(slowed|sped\s*up|speed\s*up|nightcore|daycore)(\s*(\+|&|and)\s*reverb)?.*$""", RegexOption.IGNORE_CASE), "")
        return result.trim().ifEmpty { title.trim() }
    }

    private fun seconds(match: MatchResult): Double {
        val (m, s, f) = match.destructured
        val fraction = if (f.isEmpty()) 0.0 else f.toDouble() / Math.pow(10.0, f.length.toDouble())
        return m.toDouble() * 60 + s.toDouble() + fraction
    }

    private fun format(time: Double, open: Char, close: Char): String {
        val centis = Math.round(time * 100).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%c%02d:%02d.%02d%c", open, centis / 6000, centis / 100 % 60, centis % 100, close)
    }

    fun lastTimestamp(lrc: String): Double? = lineTime.findAll(lrc).map(::seconds).maxOrNull()

    fun scale(lrc: String, factor: Double): String =
        wordTime.replace(lineTime.replace(lrc) { format(seconds(it) * factor, '[', ']') }) { format(seconds(it) * factor, '<', '>') }

    fun stripTimestamps(lrc: String): String =
        lrc.lines().joinToString("\n") { wordTime.replace(lineTime.replace(it, ""), "").trim() }

    /** Pure decision (unit-tested). */
    fun adjusted(lrc: String, played: Double, original: Double?, isVariant: Boolean): String {
        val last = lastTimestamp(lrc) ?: return lrc
        if (played <= 0) return lrc
        val mismatch = last > played + 3
        if (!isVariant && !mismatch) return lrc
        if (original != null && original > 0) {
            val factor = played / original
            if (factor in 0.6..1.5 && abs(factor - 1) > 0.02) return scale(lrc, factor)
            if (abs(factor - 1) <= 0.02) return lrc
        }
        return if (mismatch) stripTimestamps(lrc) else lrc
    }

    /** Entry point for LyricsHelper: only LRC is touched (TTML stays as is). */
    suspend fun adjust(lyrics: String, title: String, artist: String, durationSeconds: Int): String {
        if (durationSeconds <= 0 || lyrics.trimStart().startsWith("<")) return lyrics
        val last = lastTimestamp(lyrics) ?: return lyrics
        val isVariant = isSpeedVariant(title)
        if (!isVariant && last <= durationSeconds + 3) return lyrics
        val original = originalDuration(cleanTitle(title), artist)
        return adjusted(lyrics, durationSeconds.toDouble(), original, isVariant)
    }

    private suspend fun originalDuration(title: String, artist: String): Double? {
        val key = "$title|$artist".lowercase(Locale.ROOT)
        originalCache[key]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(
                    "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(title, "UTF-8") +
                        "&artist_name=" + URLEncoder.encode(artist, "UTF-8"),
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", "Resona Android (https://github.com/jackderganove-sudo/Resona-Android)")
                }
                try {
                    if (connection.responseCode != 200) return@runCatching null
                    val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                    val wanted = norm(title)
                    val items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                        .filter { norm(it.optString("trackName")) == wanted && !isSpeedVariant(it.optString("trackName")) }
                    val pick = items.firstOrNull { it.optString("syncedLyrics").isNotBlank() } ?: items.firstOrNull()
                    pick?.optDouble("duration", 0.0)?.takeIf { it > 0 }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
        if (found != null) originalCache[key] = found
        return found
    }
}
