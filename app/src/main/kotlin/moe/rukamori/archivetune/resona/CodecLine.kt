/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.formattedFileSize
import moe.rukamori.archivetune.db.entities.formattedSampleRate

/** Audio details line in the iPad's format: "Opus · WEBM • 149 kbps • 48 kHz • 2.2 MB". */
object ResonaCodecLine {
    /** "opus" → "Opus", "mp4a.40.2" → "AAC", "mp4a.40.5" → "HE-AAC"; from codecs or the mime's codecs="…". */
    fun codecName(codecs: String?, mime: String): String? {
        var raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) {
            raw = Regex("""codecs="?([^";]+)"?""").find(mime)?.groupValues?.get(1).orEmpty()
        }
        val first = raw.split(",").firstOrNull()?.trim()?.lowercase().orEmpty()
        if (first.isEmpty()) return null
        return when {
            first.startsWith("opus") -> "Opus"
            first.startsWith("mp4a.40.5") || first.startsWith("mp4a.40.29") -> "HE-AAC"
            first.startsWith("mp4a") -> "AAC"
            first.startsWith("vorbis") -> "Vorbis"
            first.startsWith("flac") -> "FLAC"
            first.startsWith("alac") -> "ALAC"
            first.startsWith("ec-3") -> "E-AC-3"
            first.startsWith("ac-3") -> "AC-3"
            else -> first.uppercase()
        }
    }

    fun container(mime: String): String = mime.substringAfter("/").substringBefore(";").trim().uppercase()

    /** "Opus · WEBM" (codec and container) or just the container when they say the same. */
    fun label(codecs: String?, mime: String): String {
        val container = container(mime)
        val codec = codecName(codecs, mime)
        return if (codec != null && !codec.equals(container, ignoreCase = true) && container.isNotEmpty()) "$codec · $container" else (codec ?: container)
    }

    fun label(format: FormatEntity): String = label(format.codecs, format.mimeType)

    fun bitrate(format: FormatEntity): String = if (format.bitrate > 0) "${format.bitrate / 1000} kbps" else ""

    /** "48 kHz • 2.2 MB" */
    fun extras(format: FormatEntity): String =
        listOfNotNull(format.formattedSampleRate(), format.formattedFileSize().takeIf { it.isNotBlank() }).joinToString(" • ")
}
