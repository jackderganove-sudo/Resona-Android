/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.aisync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.DownloadUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** whisper.cpp through JNI (libresona_whisper). */
object WhisperNative {
    val available: Boolean = runCatching { System.loadLibrary("resona_whisper") }.isSuccess

    external fun nativeInit(path: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeCancel(cancel: Boolean)
    external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int, offsetSeconds: Double): ByteArray

    /** Parses the bridge's "text\tstart\tend" lines. */
    fun parse(tsv: String): List<RecognizedWord> =
        tsv.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val text = parts[0].trim()
            val start = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val end = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            if (text.isEmpty() || text.startsWith("[") || text.startsWith("(")) null else RecognizedWord(text, start, max(end, start + 0.05))
        }.toList()
}

/** Whisper models, downloaded on demand (same choice as the iPad: fast / accurate). */
enum class WhisperModel(val fileName: String, val approxMb: Int) {
    FAST("ggml-base.bin", 148),
    ACCURATE("ggml-small.bin", 488);

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    fun file(context: Context) = File(File(context.filesDir, "whisper"), fileName)

    fun isDownloaded(context: Context) = file(context).let { it.isFile && it.length() > approxMb * 900_000L }
}

object WhisperModels {
    fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Downloads into a temp file and renames when complete; reports 0…1. */
    suspend fun download(context: Context, model: WhisperModel, onProgress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        val target = model.file(context)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Resona Android")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: (model.approxMb * 1_000_000L)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(min(1.0, done.toDouble() / total))
                    }
                }
            }
            if (!partial.renameTo(target)) error("could not store the model")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    fun delete(context: Context, model: WhisperModel) {
        model.file(context).delete()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AISyncEntryPoint {
    fun downloadUtil(): DownloadUtil
}

/** The song's audio as 16 kHz mono PCM, from the download or playback cache (nothing is streamed again). */
object CachedSongAudio {
    class NotCachedException : Exception("not cached")

    suspend fun pcm16k(context: Context, mediaId: String): FloatArray = withContext(Dispatchers.IO) {
        val util = EntryPointAccessors.fromApplication(context.applicationContext, AISyncEntryPoint::class.java).downloadUtil()
        val file = File(context.cacheDir, "aisync-audio")
        try {
            if (!copy(util.downloadCache, mediaId, file) && !copy(util.playerCache, mediaId, file)) throw NotCachedException()
            decode(file)
        } finally {
            file.delete()
        }
    }

    private fun copy(cache: Cache, key: String, target: File): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || !cache.isCached(key, 0L, length)) return false
        val source = CacheDataSource(cache, null)
        return try {
            source.open(DataSpec.Builder().setUri(Uri.parse("resona-cache://$key")).setKey(key).setLength(C.LENGTH_UNSET.toLong()).build())
            target.outputStream().use { out ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { source.close() }
        }
    }

    /** MediaExtractor + MediaCodec → mono float → linear resample to 16 kHz. */
    private fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val mono = FloatArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = buffer.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                mono.add(sum / channels)
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += sb.get() / 32768f }
                                mono.add(sum / channels)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return resample(mono.toArray(), sampleRate, 16_000)
    }

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLength = (input.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(outLength) { i ->
            val position = i * ratio
            val index = position.toInt()
            val frac = (position - index).toFloat()
            val a = input[min(index, input.size - 1)]
            val b = input[min(index + 1, input.size - 1)]
            a + (b - a) * frac
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 20)
        private var size = 0
        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }
        fun toArray() = data.copyOf(size)
    }
}

/**
 * The pipeline (same as the iPad's AILyricsSync): transcribe → calibrate
 * synced lyrics (Theil–Sen) → or align all words → low match: offer partial
 * sync (remix) or "lyrics from audio".
 */
class ResonaAISync(private val context: Context) {
    sealed interface Phase {
        data object Idle : Phase
        data class DownloadingModel(val fraction: Double) : Phase
        data object PreparingAudio : Phase
        data class Transcribing(val fraction: Double) : Phase
        data object Aligning : Phase
        data class Done(val matched: Double, val lrc: String) : Phase
        data class Calibrated(val calibration: TempoCalibration, val lrc: String) : Phase
        data class CalibrationFailed(val anchors: Int) : Phase
        data class LowMatch(val ratio: Double, val partialLines: Int) : Phase
        data class Failed(val reason: String) : Phase
    }

    enum class Mode { AUTO, CALIBRATE, FULL_SYNC }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var last: Triple<List<RecognizedWord>, List<String>, Double>? = null

    /** [texts] = lyric lines; [synced] = the current timed lines (for calibration) or empty. */
    suspend fun run(mediaId: String, texts: List<String>, synced: List<AlignedLine>, duration: Double, mode: Mode, model: WhisperModel) {
        try {
            if (texts.size < 2) return fail(NO_LYRICS)
            if (!WhisperNative.available) return fail(UNAVAILABLE)
            if (!model.isDownloaded(context)) {
                _phase.value = Phase.DownloadingModel(0.0)
                WhisperModels.download(context, model) { _phase.value = Phase.DownloadingModel(it) }
            }
            _phase.value = Phase.PreparingAudio
            val audio = try {
                CachedSongAudio.pcm16k(context, mediaId)
            } catch (e: CachedSongAudio.NotCachedException) {
                return fail(NOT_CACHED)
            }
            val audioSeconds = audio.size / 16_000.0
            val words = transcribe(model, audio)
            last = Triple(words, texts, max(duration, audioSeconds))

            if (synced.any { it.startTime != null } && mode != Mode.FULL_SYNC) {
                _phase.value = Phase.Aligning
                when (val outcome = TempoCalibrator.fit(LyricsAligner.anchors(synced, words))) {
                    is TempoCalibrator.Outcome.Fitted -> {
                        Log.i(TAG, "calibration factor=%.4f offset=%.2f inliers=%d".format(Locale.ROOT, outcome.calibration.factor, outcome.calibration.offset, outcome.calibration.anchors))
                        _phase.value = Phase.Calibrated(outcome.calibration, Lrc.serialize(TempoCalibrator.apply(synced, outcome.calibration)))
                        return
                    }
                    is TempoCalibrator.Outcome.TooFewAnchors -> if (mode == Mode.CALIBRATE) { _phase.value = Phase.CalibrationFailed(outcome.count); return }
                    is TempoCalibrator.Outcome.PoorFit -> if (mode == Mode.CALIBRATE) { _phase.value = Phase.CalibrationFailed(outcome.calibration.anchors); return }
                }
            }
            _phase.value = Phase.Aligning
            val aligned = LyricsAligner.align(texts, words, max(duration, audioSeconds))
            Log.i(TAG, "matched ${(aligned.matchedRatio * 100).toInt()} % of ${texts.size} lines' words")
            if (aligned.matchedRatio >= 0.3) {
                _phase.value = Phase.Done(aligned.matchedRatio, Lrc.serialize(aligned.lines))
                return
            }
            _phase.value = Phase.LowMatch(aligned.matchedRatio, LyricsAligner.lineOccurrences(texts, words).size)
        } catch (e: CancellationException) {
            WhisperNative.nativeCancel(true)
            _phase.value = Phase.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AI sync failed: ${e.javaClass.simpleName}")
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** "Only sync the matching lines" (remix). */
    fun partialSync(): String? {
        val (words, texts, _) = last ?: return null
        val occurrences = LyricsAligner.lineOccurrences(texts, words)
        if (occurrences.size < 2) return null
        val lines = occurrences.map { AlignedLine(texts[it.lineIndex], it.start, if (it.words.size > 1) it.words else null) }
        return Lrc.serialize(lines).also { _phase.value = Phase.Done(occurrences.size.toDouble() / texts.size, it) }
    }

    /** "Create lyrics from the audio": recognized words grouped into lines at pauses ≥ 0.8 s. */
    fun lyricsFromAudio(): String? {
        val (words, _, _) = last ?: return null
        val lines = ArrayList<AlignedLine>()
        var current = ArrayList<AlignedWord>()
        fun flush() {
            if (current.isEmpty()) return
            val text = current.joinToString("") { it.text }.trim()
            lines += AlignedLine(text, current.first().start, if (current.size > 1) current.toList() else null)
            current = ArrayList()
        }
        words.filter { LyricsAligner.normalize(it.text).isNotEmpty() }.forEachIndexed { index, word ->
            if (current.isNotEmpty() && (word.start - current.last().end > 0.8 || current.size >= 10)) flush()
            current += AlignedWord(word.text.trim() + " ", word.start, max(word.end, word.start + 0.05))
        }
        flush()
        if (lines.isEmpty()) return null
        return Lrc.serialize(LyricsAligner.monotonicTiming(lines)).also { _phase.value = Phase.Done(1.0, it) }
    }

    private suspend fun transcribe(model: WhisperModel, audio: FloatArray): List<RecognizedWord> = withContext(Dispatchers.Default) {
        WhisperNative.nativeCancel(false)
        val handle = WhisperNative.nativeInit(model.file(context).path)
        if (handle == 0L) error("model could not be loaded")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val chunk = 30 * 16_000
        val words = ArrayList<RecognizedWord>()
        val started = SystemClock.elapsedRealtime()
        try {
            var offset = 0
            while (offset < audio.size) {
                currentCoroutineContext().ensureActive()
                _phase.value = Phase.Transcribing(offset.toDouble() / audio.size)
                val piece = audio.copyOfRange(offset, min(audio.size, offset + chunk))
                val bytes = WhisperNative.nativeTranscribe(handle, piece, threads, offset / 16_000.0)
                words += WhisperNative.parse(String(bytes, Charsets.UTF_8))
                offset += chunk
            }
        } finally {
            WhisperNative.nativeFree(handle)
        }
        // Timing only, never recognized text.
        Log.i(TAG, "transcribed %.0f s audio in %.1f s (model %s, %d threads, %d words)".format(
            Locale.ROOT, audio.size / 16_000.0, (SystemClock.elapsedRealtime() - started) / 1000.0, model.name, threads, words.size))
        words
    }

    private fun fail(reason: String) {
        _phase.value = Phase.Failed(reason)
    }

    companion object {
        const val TAG = "ResonaAISync"
        const val NOT_CACHED = "not-cached"
        const val UNAVAILABLE = "unavailable"
        const val NO_LYRICS = "no-lyrics"
    }
}

/** Enhanced LRC ("[mm:ss.xx]<mm:ss.xx>word …") for ArchiveTune's lyrics store. */
object Lrc {
    private fun stamp(seconds: Double, open: Char, close: Char): String {
        val centis = Math.round(seconds * 100).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%c%02d:%02d.%02d%c", open, centis / 6000, centis / 100 % 60, centis % 100, close)
    }

    fun serialize(lines: List<AlignedLine>): String =
        lines.filter { it.startTime != null }.joinToString("\n") { line ->
            val words = line.words
            val body = if (words.isNullOrEmpty()) line.text else words.joinToString("") { stamp(it.start, '<', '>') + it.text }.trimEnd()
            stamp(line.startTime!!, '[', ']') + body
        }
}
