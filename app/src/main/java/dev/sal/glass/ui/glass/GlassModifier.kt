package dev.sal.glass.ui.glass

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

enum class GlassVariant { Tile, Pill, Sheet }

/**
 * Liquid-glass surface for Android 11 (no RenderEffect): the panel paints the pre-blurred
 * wallpaper that lies BEHIND IT on screen, slightly magnified, then frost, highlight, rim and
 * shadow on top. Everything here is a plain fill or stroke on one path, so a frame costs the
 * GPU nothing beyond the fill rate.
 *
 * @param lift 0..1, how far the panel is "lifted" (focus): deeper shadow, brighter rim.
 */
fun Modifier.glass(
    shape: SquircleShape,
    variant: GlassVariant = GlassVariant.Tile,
    focused: Boolean = false,
    lift: Float = if (focused) 1f else 0f,
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val path = shape.pathFor(size, density)
            val luma = backdropLuma(backdrop, origin, size)
            // Frost adapts to what is behind: white over dark photos, a dark smoke over bright ones
            // (snowfields, daytime skylines) so the icon and digits stay readable.
            val bright = luma > 0.55f
            val frost = if (bright) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = frostAlpha(variant))
            val rimTop = 0.55f + 0.35f * lift
            val rimBottom = 0.15f + 0.10f * lift
            val shadowAlpha = 0.18f + 0.22f * lift
            val lens = 1.04f
            val strokePx = 1.5f * density.density
            onDrawBehind {
                // Shadow: three offset copies, cheaper and softer-looking than a hardware shadow on a path.
                for (i in 1..3) {
                    translate(top = (2f + 4f * lift) * i) {
                        drawPath(path, Color.Black.copy(alpha = shadowAlpha / (i * 2f)))
                    }
                }
                // Backdrop: the blurred wallpaper under this panel, magnified about its centre.
                drawBackdrop(backdrop.current, 1f - backdrop.progress, origin, lens, path)
                drawBackdrop(backdrop.next, backdrop.progress, origin, lens, path)
                // Frost tint.
                drawPath(path, frost)
                // Inner highlight: light enters from the top-left.
                drawPath(
                    path,
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.35f), 0.55f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent,
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    ),
                )
                // Focus specular: a soft bloom along the top edge.
                if (lift > 0f) {
                    drawPath(
                        path,
                        Brush.radialGradient(
                            0f to Color.White.copy(alpha = 0.30f * lift), 1f to Color.Transparent,
                            center = Offset(size.width / 2f, 0f), radius = size.width * 0.7f,
                        ),
                    )
                }
                // Rim: bright on top, faint at the bottom, like a polished edge catching light.
                drawPath(
                    path,
                    Brush.verticalGradient(0f to Color.White.copy(alpha = rimTop), 1f to Color.White.copy(alpha = rimBottom)),
                    style = Stroke(width = strokePx),
                )
            }
        }
}

private fun frostAlpha(variant: GlassVariant) = when (variant) {
    GlassVariant.Tile -> 0.22f
    GlassVariant.Pill -> 0.26f
    GlassVariant.Sheet -> 0.34f
}

/**
 * Paints [thumb] so that the part of the wallpaper under this panel appears inside it. The thumb is
 * a downscaled copy of the full-screen wallpaper, so screen px → thumb px is one scale, offset by
 * where the panel sits on screen.
 */
private fun DrawScope.drawBackdrop(thumb: Bitmap?, alpha: Float, origin: Offset, lens: Float, path: Path) {
    if (thumb == null || alpha <= 0.01f) return
    val screenW = ScreenSize.width
    val screenH = ScreenSize.height
    if (screenW <= 0f) return
    val sx = screenW / thumb.width
    val sy = screenH / thumb.height
    // Thumb px → screen px, shifted so screen (0,0) lands at this panel's top-left, then magnified
    // about the panel centre. android.graphics.Matrix: postX applies after what is already there.
    val matrix = android.graphics.Matrix().apply {
        setScale(sx, sy)
        postTranslate(-origin.x, -origin.y)
        postScale(lens, lens, size.width / 2f, size.height / 2f)
    }
    val shader = ImageShader(thumb.asImageBitmap(), TileMode.Clamp, TileMode.Clamp)
    shader.setLocalMatrix(matrix)
    drawPath(path, ShaderBrush(shader), alpha = alpha)
}

/** Mean luma (0..1) of the thumb pixels under the panel, sampled on a coarse grid. */
private fun backdropLuma(backdrop: GlassBackdrop, origin: Offset, size: Size): Float {
    val thumb = (if (backdrop.progress > 0.5f) backdrop.next else backdrop.current) ?: return 0.3f
    val screenW = ScreenSize.width
    val screenH = ScreenSize.height
    if (screenW <= 0f || size.width <= 0f) return 0.3f
    val x0 = (origin.x / screenW * thumb.width).roundToInt().coerceIn(0, thumb.width - 1)
    val y0 = (origin.y / screenH * thumb.height).roundToInt().coerceIn(0, thumb.height - 1)
    val x1 = ((origin.x + size.width) / screenW * thumb.width).roundToInt().coerceIn(x0, thumb.width - 1)
    val y1 = ((origin.y + size.height) / screenH * thumb.height).roundToInt().coerceIn(y0, thumb.height - 1)
    var sum = 0f; var n = 0
    val stepX = maxOf(1, (x1 - x0) / 6); val stepY = maxOf(1, (y1 - y0) / 4)
    var y = y0
    while (y <= y1) {
        var x = x0
        while (x <= x1) {
            val c = thumb.getPixel(x, y)
            sum += (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
            n++; x += stepX
        }
        y += stepY
    }
    return if (n == 0) 0.3f else sum / n
}

/** Root size in px, set once by the home screen so panels can map their position to the thumb. */
object ScreenSize {
    var width = 0f
    var height = 0f
}
