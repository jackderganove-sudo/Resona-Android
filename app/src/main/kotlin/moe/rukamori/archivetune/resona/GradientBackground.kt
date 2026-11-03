/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlaybackCache
import moe.rukamori.archivetune.utils.rememberEnumPreference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Player background = only a colour gradient, never video (Resona, same as
 * the iPad). CANVAS: the song's canvas clip is sampled into a colour
 * timeline (eight 24×24 frames, decoded once) that plays in step with the
 * clip; COVER: slowly flowing cover colours; STATIC: cover colours, still.
 */
enum class ResonaGradientMode { CANVAS, COVER, STATIC }

val ResonaGradientModeKey = stringPreferencesKey("resona_player_gradient")

/** Same rules as the iPad's CanvasColorQuantizer (ARGB ints instead of BGRA bytes). */
object ResonaColorQuantizer {
    data class Rgb(val r: Double, val g: Double, val b: Double) {
        val max get() = max(r, max(g, b))
        val min get() = min(r, min(g, b))
        val saturation get() = if (max <= 0) 0.0 else (max - min) / max
        val luminance get() = 0.2126 * r + 0.7152 * g + 0.0722 * b
        fun mix(o: Rgb, t: Double) = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
        fun toColor() = Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f))
    }

    fun dominant(argb: IntArray): List<Rgb> {
        class Bucket { var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0 }
        val buckets = HashMap<Int, Bucket>()
        for (p in argb) {
            val r = (p shr 16 and 0xFF) / 255.0
            val g = (p shr 8 and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val key = ((r * 15).toInt() shl 8) or ((g * 15).toInt() shl 4) or (b * 15).toInt()
            val bucket = buckets.getOrPut(key) { Bucket() }
            bucket.r += r; bucket.g += g; bucket.b += b; bucket.n += 1
        }
        val ranked = buckets.values.map { Rgb(it.r / it.n, it.g / it.n, it.b / it.n) to it.n }
            .map { (rgb, n) -> rgb to n * (0.25 + rgb.saturation) * (if (rgb.max < 0.08) 0.2 else 1.0) }
            .sortedByDescending { it.second }
        val picked = ArrayList<Rgb>()
        for ((rgb, _) in ranked) {
            if (picked.size >= 4) break
            if (picked.all { abs(it.r - rgb.r) + abs(it.g - rgb.g) + abs(it.b - rgb.b) > 0.12 }) picked += rgb
        }
        if (picked.isEmpty()) return emptyList()
        val first = picked.first()
        while (picked.size < 4) picked += picked[0].mix(first, 0.3)
        return picked.sortedByDescending { it.luminance }
    }

    /** Keeps white text readable: brightness 0.12…0.62, saturation ≤ 0.85. */
    fun readable(colors: List<Rgb>): List<Rgb> = colors.map { c ->
        val v = c.max
        val s = c.saturation
        val targetV = v.coerceIn(0.12, 0.62)
        val targetS = min(s, 0.85)
        if (v <= 0) return@map Rgb(targetV, targetV, targetV)
        // Scale value, then pull towards grey for saturation.
        val scaled = Rgb(c.r / v * targetV, c.g / v * targetV, c.b / v * targetV)
        val grey = Rgb(targetV, targetV, targetV)
        if (s <= targetS || s == 0.0) scaled else grey.mix(scaled, targetS / s)
    }
}

object CanvasColorTimeline {
    data class Timeline(val frames: List<List<Color>>, val durationMs: Long)

    private val cache = ConcurrentHashMap<String, Timeline>()
    private const val FRAMES = 8
    private const val SIDE = 24

    suspend fun forMedia(mediaId: String): Timeline? {
        cache[mediaId]?.let { return it }
        val timeline = withContext(Dispatchers.IO) {
            runCatching {
                val artwork = CanvasArtworkPlaybackCache.get(mediaId, preferCachedOnly = false) ?: return@runCatching null
                val url = artwork.videoUrl ?: artwork.preferredAnimationUrl ?: return@runCatching null
                build(url)
            }.getOrNull()
        } ?: return null
        cache[mediaId] = timeline
        return timeline
    }

    private fun build(url: String): Timeline? {
        val retriever = MediaMetadataRetriever()
        try {
            if (url.startsWith("http")) retriever.setDataSource(url, emptyMap()) else retriever.setDataSource(url.removePrefix("file://"))
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (durationMs <= 0) return null
            val frames = (0 until FRAMES).mapNotNull { i ->
                val timeUs = durationMs * 1000 * i / FRAMES
                val bitmap =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE, SIDE)
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, SIDE, SIDE, true) }
                    } ?: return@mapNotNull null
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val colors = ResonaColorQuantizer.readable(ResonaColorQuantizer.dominant(pixels))
                colors.takeIf { it.size == 4 }?.map { it.toColor() }
            }
            return if (frames.size >= 2) Timeline(frames, durationMs) else null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private fun Context.powerSave(): Boolean = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

/**
 * The gradient itself: four colours as soft radial blobs over a vertical
 * gradient, drifting slowly while music plays; colour changes fade over
 * ~0.9 s; a dark lower part keeps controls readable.
 */
@Composable
fun ResonaGradientBackground(
    mediaId: String?,
    coverColors: List<Color>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mode by rememberEnumPreference(ResonaGradientModeKey, ResonaGradientMode.CANVAS)
    val powerSave = remember(isPlaying) { context.powerSave() }
    val moving = isPlaying && mode != ResonaGradientMode.STATIC && !powerSave

    val timeline by produceState<CanvasColorTimeline.Timeline?>(null, mediaId, mode, powerSave) {
        value = if (mode == ResonaGradientMode.CANVAS && !powerSave && mediaId != null) CanvasColorTimeline.forMedia(mediaId) else null
    }
    val coverFour = remember(coverColors) {
        val base = coverColors.ifEmpty { listOf(Color(0xFF2B3A55)) }
        List(4) { base[it % base.size] }.map { c ->
            val rgb = ResonaColorQuantizer.readable(listOf(ResonaColorQuantizer.Rgb(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble()))).first()
            rgb.toColor()
        }
    }
    var frameIndex by remember(mediaId) { mutableStateOf(0) }
    LaunchedEffect(timeline, moving) {
        val t = timeline ?: return@LaunchedEffect
        val step = max(500L, t.durationMs / t.frames.size)
        while (isActive && moving) {
            delay(step)
            frameIndex = (frameIndex + 1) % t.frames.size
        }
    }
    val source = timeline?.frames?.getOrNull(frameIndex) ?: coverFour
    val c0 by animateColorAsState(source[0], tween(900), label = "g0")
    val c1 by animateColorAsState(source[1], tween(900), label = "g1")
    val c2 by animateColorAsState(source[2], tween(900), label = "g2")
    val c3 by animateColorAsState(source[3], tween(900), label = "g3")

    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L) phase += (now - last) / 1_000_000_000f
                last = now
            }
            // ~20 fps is plenty for a slow drift.
            delay(33)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun wave(period: Float, offset: Float) = sin(phase * 2f * Math.PI.toFloat() / period + offset)
            drawRect(Brush.verticalGradient(listOf(c0, c1, c3)))
            val blobs = listOf(
                Triple(c0, Offset(w * (0.3f + 0.15f * wave(19f, 0f)), h * (0.25f + 0.08f * wave(23f, 1f))), max(w, h) * 0.55f),
                Triple(c1, Offset(w * (0.75f + 0.12f * wave(17f, 2f)), h * (0.45f + 0.1f * wave(21f, 0.5f))), max(w, h) * 0.5f),
                Triple(c2, Offset(w * (0.4f + 0.18f * cos(phase * 2f * Math.PI.toFloat() / 22f)), h * (0.7f + 0.06f * wave(18f, 3f))), max(w, h) * 0.5f),
            )
            for ((color, center, radius) in blobs) {
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)), center, radius), radius, center)
            }
            // Calm, darker lower part for title, times and controls.
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.08f), Color.Transparent, Color.Black.copy(alpha = 0.32f))))
        }
    }
}
