/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Lyrics depth as one pass over the whole list instead of one blur per line.
 * The library's per-line RenderEffect ran every frame and filled the GPU cache
 * (Pixel, portrait: 213 scratch surfaces, 0 bytes purgeable), after which every
 * text raster was re-uploaded every frame: 66 % janky frames. The active line
 * always sits at the same height, so the distance on screen gives the same
 * depth as the iPad — sharp at the active line, stronger with distance
 * (2.8 / 6 dp), constant from about four lines, symmetric, sharp while
 * scrolling by hand. Android 13+ (runtime shader); below that no blur.
 */
object LyricsDepth {
    val BLUR_NEAR = 2.8.dp
    val BLUR_FAR = 6.dp

    /** Where each level fades out, in pixels of distance from the sharp band. */
    data class Bands(val top: Float, val bottom: Float, val sharpEnd: Float, val nearEnd: Float)

    /** The band around the active line (its top is at [anchorTop]; up to two rows are fully sharp). */
    fun bands(anchorTop: Float, rowHeight: Float): Bands =
        Bands(
            top = max(0f, anchorTop - rowHeight * 0.25f),
            bottom = anchorTop + rowHeight * 2f,
            sharpEnd = rowHeight * 1.25f,
            nearEnd = rowHeight * 3.5f,
        )

    /** Same curve as the shader's smoothstep. Share of a level at [distance] from the band. */
    fun share(distance: Float, end: Float): Float {
        if (end <= 0f) return if (distance <= 0f) 1f else 0f
        val t = (distance.coerceAtLeast(0f) / end).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    const val MASK_SHADER = """
        uniform shader content;
        uniform float top;
        uniform float bottom;
        uniform float end;
        uniform float gain;
        uniform float amount;
        half4 main(float2 p) {
            float d = max(0.0, max(top - p.y, p.y - bottom)) * amount;
            float m = end > 0.0 ? 1.0 - smoothstep(0.0, end, d) : 1.0;
            return content.eval(p) * half(m * gain);
        }
    """

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun mask(bands: Bands, end: Float, gain: Float, amount: Float): RenderEffect {
        val shader = RuntimeShader(MASK_SHADER).apply {
            setFloatUniform("top", bands.top)
            setFloatUniform("bottom", bands.bottom)
            setFloatUniform("end", end)
            setFloatUniform("gain", gain)
            setFloatUniform("amount", amount)
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    /** sharp over near blur over far blur; [amount] 0 = all sharp (manual scrolling), 1 = full depth. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun effect(bands: Bands, nearPx: Float, farPx: Float, amount: Float, levels: Int = 2): RenderEffect {
        val sharp = mask(bands, bands.sharpEnd, 1f, amount)
        if (levels == 1) {
            val only = RenderEffect.createChainEffect(
                mask(bands, 0f, amount, 1f),
                RenderEffect.createBlurEffect(nearPx, nearPx, Shader.TileMode.DECAL),
            )
            return RenderEffect.createBlendModeEffect(only, sharp, android.graphics.BlendMode.SRC_OVER)
        }
        val near = RenderEffect.createChainEffect(
            mask(bands, bands.nearEnd, amount, 1f),
            RenderEffect.createBlurEffect(nearPx, nearPx, Shader.TileMode.DECAL),
        )
        val far = RenderEffect.createChainEffect(
            mask(bands, 0f, amount, 1f),
            RenderEffect.createBlurEffect(farPx, farPx, Shader.TileMode.DECAL),
        )
        return RenderEffect.createBlendModeEffect(
            RenderEffect.createBlendModeEffect(far, near, android.graphics.BlendMode.SRC_OVER),
            sharp,
            android.graphics.BlendMode.SRC_OVER,
        )
    }
}

/**
 * Measuring only (must not reach a release): variants from a file on the device,
 * e.g. "levels=1 blur=4 lib=0 hide=1". Re-read each time the lyrics open.
 */
object LyricsDepthBench {
    var levels by mutableIntStateOf(0)
    var blurDp by mutableFloatStateOf(0f)
    var library by mutableStateOf(false)
    var hideBelow by mutableStateOf(false)

    fun load(context: android.content.Context) {
        val values = runCatching {
            java.io.File(context.getExternalFilesDir(null), "lyrics-depth.txt").readText()
                .trim().split(Regex("\\s+")).associate { it.substringBefore('=') to it.substringAfter('=') }
        }.getOrDefault(emptyMap())
        levels = values["levels"]?.toIntOrNull() ?: 0
        blurDp = values["blur"]?.toFloatOrNull() ?: 0f
        library = values["lib"] == "1"
        hideBelow = values["hide"] == "1"
        android.util.Log.i("ResonaLyricsDepth", "levels=$levels blur=$blurDp lib=$library hide=$hideBelow")
    }
}

/** See [LyricsDepth]. [anchorTop]: top of the active line within this layout. */
@SuppressLint("NewApi") // Guarded by LyricsDepth.supported.
@Composable
fun Modifier.resonaLyricsDepth(
    enabled: Boolean,
    anchorTop: Dp,
    rowHeight: Dp,
    manualScrolling: Boolean,
    levels: Int = 2,
): Modifier {
    if (!enabled || !LyricsDepth.supported) return this
    val amount by animateFloatAsState(
        targetValue = if (manualScrolling) 0f else 1f,
        animationSpec = tween(if (manualScrolling) 100 else 300),
        label = "lyricsDepth",
    )
    val density = LocalDensity.current
    val bands = remember(anchorTop, rowHeight, density) {
        with(density) { LyricsDepth.bands(anchorTop.toPx(), rowHeight.toPx()) }
    }
    val nearPx = with(density) { if (LyricsDepthBench.blurDp > 0f) LyricsDepthBench.blurDp.dp.toPx() else LyricsDepth.BLUR_NEAR.toPx() }
    val farPx = with(density) { LyricsDepth.BLUR_FAR.toPx() }
    // One effect object per state; only rebuilt while the amount animates.
    val settled = remember(bands, nearPx, farPx, levels) {
        runCatching { LyricsDepth.effect(bands, nearPx, farPx, 1f, levels).asComposeRenderEffect() }.getOrNull()
    } ?: return this
    return graphicsLayer {
        val current = amount
        renderEffect = when {
            current <= 0f -> null
            current >= 1f -> settled
            else -> LyricsDepth.effect(bands, nearPx, farPx, current, levels).asComposeRenderEffect()
        }
    }
}
