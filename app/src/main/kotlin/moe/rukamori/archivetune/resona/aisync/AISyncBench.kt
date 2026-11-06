/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Measuring only, never in a release: with a list in the app's external files
 * folder (aisync-bench.txt), "calibrate" runs every line and logs numbers
 * (time, word count, match, timing error against the synced lyrics), never text.
 * Line: name model=ggml-base-q5_1.bin threads=6 opts=single=1 vad=0 windows=30
 */
object AISyncBench {
    private const val TAG = "ResonaAISync"
    private const val MODELS = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val VAD = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/"

    private fun fetch(dir: File, name: String, base: String): File {
        val target = File(dir, name)
        if (target.isFile && target.length() > 100_000) return target
        val partial = File(dir, "$name.part")
        val connection = (URL(base + name).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        partial.renameTo(target)
        return target
    }

    private fun temperature(context: Context): Double {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    }

    private fun charging(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    suspend fun runIfConfigured(
        context: Context,
        audio: FloatArray,
        texts: List<String>,
        synced: List<AlignedLine>,
        duration: Double,
        language: String?,
        onProgress: (Double) -> Unit,
    ): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return false
        val list = File(dir, "aisync-bench.txt")
        if (!list.isFile) return false
        val lines = list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val total = audio.size / 16_000.0
        Log.i(TAG, "BENCH start: ${lines.size} configs, %.0f s audio, lang %s, %d lyric lines, %d synced".format(Locale.ROOT, total, language ?: "auto", texts.size, synced.size))
        withContext(Dispatchers.Default) {
            lines.forEachIndexed { index, line ->
                currentCoroutineContext().ensureActive()
                val parts = line.split(Regex("\\s+"))
                val name = parts.first()
                val config = parts.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
                onProgress(index.toDouble() / lines.size)
                measure(context, dir, name, config, audio, texts, synced, duration, language, total)
            }
        }
        Log.i(TAG, "BENCH done")
        return true
    }

    private suspend fun measure(
        context: Context,
        dir: File,
        name: String,
        config: Map<String, String>,
        audio: FloatArray,
        texts: List<String>,
        synced: List<AlignedLine>,
        duration: Double,
        language: String?,
        total: Double,
    ) {
        val model = fetch(dir, config["model"] ?: "ggml-base.bin", MODELS)
        val threads = config["threads"]?.toIntOrNull() ?: 6
        val options = config["opts"]
        val length = config["windows"]?.toDoubleOrNull() ?: 30.0
        val tempBefore = temperature(context)
        val started = SystemClock.elapsedRealtime()
        var vadSeconds = 0.0
        val windows =
            if (config["vad"] == "1") {
                val vadModel = fetch(dir, config["vadmodel"] ?: "ggml-silero-v6.2.0.bin", VAD)
                val vadStarted = SystemClock.elapsedRealtime()
                val raw = WhisperNative.nativeVad(
                    vadModel.path, audio, threads,
                    config["vadthr"]?.toFloatOrNull() ?: 0.35f,
                    config["vadminspeech"]?.toIntOrNull() ?: 200,
                    config["vadminsilence"]?.toIntOrNull() ?: 300,
                    config["vadpad"]?.toIntOrNull() ?: 300,
                )
                vadSeconds = (SystemClock.elapsedRealtime() - vadStarted) / 1000.0
                val speech = (0 until raw.size / 2).map { raw[2 * it].toDouble() to raw[2 * it + 1].toDouble() }
                val covered = speech.sumOf { it.second - it.first }
                Log.i(TAG, "BENCH $name vad: %.1f s, %d segments, %.0f s speech of %.0f s".format(Locale.ROOT, vadSeconds, speech.size, covered, total))
                AISyncWindows.fromSpeech(speech, total, length)
            } else if (config["align"] == "lines") {
                AISyncWindows.fromLines(synced, total, length)
            } else {
                AISyncWindows.fixed(total, length)
            }
        val handle = WhisperNative.nativeInit(model.path)
        if (handle == 0L) {
            Log.w(TAG, "BENCH $name: model failed to load")
            return
        }
        val words = ArrayList<RecognizedWord>()
        val perWindow = ArrayList<String>()
        try {
            WhisperNative.nativeCancel(false)
            for (window in windows) {
                currentCoroutineContext().ensureActive()
                val from = (window.start * 16_000).toInt()
                val to = min(audio.size, (window.end * 16_000).toInt())
                val windowStarted = SystemClock.elapsedRealtime()
                val bytes = WhisperNative.nativeTranscribe(handle, audio.copyOfRange(from, to), threads, window.start, language, options)
                val found = WhisperNative.parse(String(bytes, Charsets.UTF_8))
                words += found
                perWindow += "%d/%.0fs".format(Locale.ROOT, found.size, (SystemClock.elapsedRealtime() - windowStarted) / 1000.0)
            }
        } finally {
            WhisperNative.nativeFree(handle)
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        Log.i(TAG, "BENCH $name windows cover %.0f s of %.0f s".format(Locale.ROOT, windows.sumOf { it.seconds }, total))
        val sorted = words.sortedBy { it.start }
        val aligned = LyricsAligner.align(texts, sorted, maxOf(duration, total))
        // Timing error against the synced lyrics (the reference we have): median per line.
        val errors =
            if (synced.size == aligned.lines.size) {
                aligned.lines.zip(synced).mapNotNull { (ours, theirs) ->
                    val a = ours.startTime
                    val b = theirs.startTime
                    if (a != null && b != null) abs(a - b) else null
                }.sorted()
            } else {
                emptyList()
            }
        val median = errors.getOrNull(errors.size / 2) ?: -1.0
        val within1 = if (errors.isEmpty()) -1 else errors.count { it <= 1.0 } * 100 / errors.size
        val calibration = TempoCalibrator.fit(LyricsAligner.anchors(synced, sorted))
        val fitted = (calibration as? TempoCalibrator.Outcome.Fitted)?.calibration
        Log.i(
            TAG,
            "BENCH %s: %.1f s (%.2f x song), %d words, match %d %%, line error median %.2f s, within 1 s %d %%, calibration %s, %d windows, %.1f→%.1f C, %s, threads %d, %s".format(
                Locale.ROOT, name, seconds, seconds / total, words.size, (aligned.matchedRatio * 100).toInt(), median, within1,
                fitted?.let { "%.4f/%+.2f/%d".format(Locale.ROOT, it.factor, it.offset, it.anchors) } ?: "none",
                windows.size, tempBefore, temperature(context), if (charging(context)) "power" else "battery", threads, perWindow.joinToString(" "),
            ),
        )
    }
}
