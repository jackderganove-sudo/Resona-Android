/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * The app's one glass surface — same values as the iPad's ResonaGlass:
 * tint = surfaceContainerHighest mixed 50 % with the cover colour at 0.62,
 * saturation 1.4 for what shows through, 1 dp white edge at 0.12, blur
 * ≈ the iPad's ultra-thin material, no noise. Blur via Haze (RenderEffect)
 * on Android 12+; below that the tint alone, nearly opaque.
 */
object ResonaGlass {
    const val COVER_MIX = 0.5f
    const val TINT_OPACITY = 0.62f
    const val FALLBACK_OPACITY = 0.94f
    const val SATURATION = 1.4f
    const val EDGE_OPACITY = 0.12f
    val EDGE_WIDTH = 1.dp
    val BLUR_RADIUS = 28.dp

    /** Cover colour of the current song (the theme seed), published by MainActivity. */
    var coverColor by mutableStateOf(Color(0xFF2E7DB5))

    fun tint(surface: Color, cover: Color): Color = lerp(surface, cover, COVER_MIX)

    val blurAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

@Composable
fun resonaGlassTint(): Color = ResonaGlass.tint(MaterialTheme.colorScheme.surfaceContainerHighest, ResonaGlass.coverColor)

@Composable
fun ResonaGlassBackground(
    hazeState: HazeState?,
    shape: Shape = RectangleShape,
    modifier: Modifier = Modifier,
    edge: Boolean = true,
) {
    val tint = resonaGlassTint()
    val blurred = hazeState != null && ResonaGlass.blurAvailable
    val style = remember(tint) {
        HazeBlurStyle {
            backgroundColor(tint)
            blurRadius(ResonaGlass.BLUR_RADIUS)
            noiseFactor(0f)
            colorEffects(
                listOf(
                    HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(ResonaGlass.SATURATION) })),
                    HazeColorEffect.tint(tint.copy(alpha = ResonaGlass.TINT_OPACITY)),
                ),
            )
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = ResonaGlass.FALLBACK_OPACITY)))
        }
    }
    val input = remember(hazeState) { hazeState?.let { HazeInput.Sources(it) } }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (blurred && input != null) {
                    Modifier.hazeBlur(input = input, style = style, expandLayerBounds = true)
                } else {
                    Modifier.background(tint.copy(alpha = ResonaGlass.FALLBACK_OPACITY))
                },
            )
            .then(if (edge) Modifier.border(ResonaGlass.EDGE_WIDTH, Color.White.copy(alpha = ResonaGlass.EDGE_OPACITY), shape) else Modifier),
    )
}

/**
 * Sheets and dialogs are their own windows: Compose can't blur what lies
 * behind them, but Android 12+ can (cross-window blur). Where it's on, the
 * glass tint sits at 0.62 over the blurred app like on the iPad; otherwise
 * it stays nearly opaque.
 */
@Composable
fun rememberCrossWindowBlur(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled == true
    }
}

@Composable
fun resonaSheetColor(blur: Boolean): Color =
    resonaGlassTint().copy(alpha = if (blur) ResonaGlass.TINT_OPACITY else ResonaGlass.FALLBACK_OPACITY)

/** Call inside a sheet's / dialog's content: blurs the app behind that window (Android 12+). */
@Composable
fun ResonaWindowBlurBehind(enabled: Boolean) {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val radius = with(LocalDensity.current) { ResonaGlass.BLUR_RADIUS.roundToPx() }
    DisposableEffect(window, radius) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radius }
        onDispose { }
    }
}
