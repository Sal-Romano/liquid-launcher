package dev.sal.glass.ui.glass

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity

enum class GlassVariant { Tile, Pill, Sheet }

/**
 * Marks a composable as a glass panel. The glass itself is painted by [GlassLayer] (OpenGL ES,
 * real refraction); this modifier only reports where the panel is and clips the content to the
 * squircle. [lift] 0..1 is the focus lift; [alpha] lets a hiding row fade its glass too.
 */
fun Modifier.glass(
    shape: SquircleShape,
    variant: GlassVariant = GlassVariant.Tile,
    focused: Boolean = false,
    lift: Float = if (focused) 1f else 0f,
    alpha: Float = 1f,
): Modifier = composed {
    val id = remember { Any() }
    val cornerPx = with(LocalDensity.current) { shape.radiusDp.toPx() }
    DisposableEffect(id) { onDispose { GlassPanels.remove(id) } }
    this
        .onGloballyPositioned { c ->
            val pos = c.positionInRoot()
            val size = c.size
            GlassPanels.update(
                id, Rect(pos.x, pos.y, pos.x + size.width, pos.y + size.height),
                cornerPx, variant, lift, alpha,
            )
        }
        .clip(shape)
}

/** Root size in px, set once by the home screen. */
object ScreenSize {
    var width = 0f
    var height = 0f
}
