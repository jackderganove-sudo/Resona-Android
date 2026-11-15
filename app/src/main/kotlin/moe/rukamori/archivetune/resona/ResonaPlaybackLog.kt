/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Playback error log (round 12, same as iPad v0.8.19): every stream attempt
 * and failure with time, song id, YouTube client, stage, HTTP status and a
 * redacted text; the last 200 entries, shareable from Settings → About.
 * Never cookies, tokens, account data or full stream URLs.
 */
@Serializable
data class PlaybackLogEntry(
    val time: Long,
    val videoId: String,
    val client: String,
    /** resolve, validate, player, success */
    val stage: String,
    val httpStatus: Int? = null,
    val message: String,
) {
    fun line(zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(time).atZone(zone).withNano(0))
        val status = httpStatus?.let { "HTTP $it" } ?: "-"
        return "$stamp  $videoId  $client  $stage  $status  $message"
    }
}

object PlaybackLogRedaction {
    private val url = Regex("https?://[^\\s\"'<>]+")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val secret = Regex("(?i)\\b(cookie|sapisid|authorization|token|potoken|key|sig|signature|visitor_?data|session)\\b\\s*[=:]\\s*[^\\s,;&]+")
    private val long = Regex("[A-Za-z0-9_\\-+/=]{32,}")

    /** URLs → host (+ itag), key=value secrets, long tokens and e-mail addresses removed. */
    fun redact(text: String): String {
        var out = url.replace(text) { m ->
            val uri = runCatching { java.net.URI(m.value) }.getOrNull()
            val host = uri?.host ?: return@replace "<url>"
            val itag = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("itag=") }?.substringAfter('=')
            if (itag != null) "$host itag=$itag" else host
        }
        out = email.replace(out, "<email>")
        out = secret.replace(out) { m -> m.value.takeWhile { it != '=' && it != ':' }.trim() + "=<hidden>" }
        out = long.replace(out, "<hidden>")
        return out.take(300)
    }

    /** First HTTP status (4xx/5xx) mentioned in a message. */
    fun httpStatus(text: String?): Int? = text?.let { Regex("\\b([45]\\d\\d)\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

object ResonaPlaybackLog {
    const val LIMIT = 200
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Executors.newSingleThreadExecutor()
    private val entries = ArrayList<PlaybackLogEntry>()
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "playback-errors.json")
        synchronized(entries) {
            entries.clear()
            runCatching { entries += json.decodeFromString<List<PlaybackLogEntry>>(f.readText()) }
        }
        file = f
    }

    fun record(videoId: String, client: String, stage: String, httpStatus: Int?, message: String, time: Long = System.currentTimeMillis()) {
        val entry = PlaybackLogEntry(time, videoId, client, stage, httpStatus ?: PlaybackLogRedaction.httpStatus(message).takeIf { stage != "success" }, PlaybackLogRedaction.redact(message))
        val snapshot =
            synchronized(entries) {
                entries += entry
                if (entries.size > LIMIT) entries.subList(0, entries.size - LIMIT).clear()
                entries.toList()
            }
        val target = file ?: return
        writer.execute { runCatching { target.writeText(json.encodeToString(snapshot)) } }
    }

    fun all(): List<PlaybackLogEntry> = synchronized(entries) { entries.toList() }

    fun exportText(appVersion: String, system: String, list: List<PlaybackLogEntry> = all()): String =
        buildString {
            append("Resona ").append(appVersion).append(" · ").append(system).append('\n')
            append("Playback error log (").append(list.size).append(" entries): time  video  client  stage  status  message\n\n")
            list.forEach { append(it.line()).append('\n') }
        }

    /** Settings → About → "Share error log". */
    fun share(context: Context, appVersion: String) {
        val system = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"
        val folder = File(context.cacheDir, "error-log").apply { mkdirs() }
        val out = File(folder, "Resona-Fehlerprotokoll.txt")
        val text = exportText(appVersion, system)
        out.writeText(text)
        // wip builds only: a copy the device test script can pull (scripts/device-tests-runde12.sh).
        if (moe.rukamori.archivetune.BuildConfig.RESONA_MEASURE) {
            runCatching { context.getExternalFilesDir(null)?.let { File(it, out.name).writeText(text) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
