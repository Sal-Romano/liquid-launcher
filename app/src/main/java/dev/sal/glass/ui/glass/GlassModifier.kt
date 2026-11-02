package dev.sal.glass.ui.glass

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

enum class GlassVariant { Tile, Pill, Sheet }

/**
 * Marks a composable as a glass panel. The glass itself is painted by a [GlassLayer] (OpenGL ES,
 * real refraction); this modifier only reports where the panel is and clips the content to the
 * squircle. [lift] 0..1 is the focus lift; [alpha] lets a hiding row fade its glass too; [layer]
 * picks the GL pass (home chrome vs overlays, which must paint above the clock and dock).
 *
 * The reported rect is the TRANSFORMED bounds (boundsInRoot), so a tile that scales up with a
 * graphicsLayer on focus carries its glass with it; the corner radius scales the same way.
 */
fun Modifier.glass(
    shape: SquircleShape,
    variant: GlassVariant = GlassVariant.Tile,
    focused: Boolean = false,
    lift: Float = if (focused) 1f else 0f,
    alpha: Float = 1f,
    layer: GlassLayerId = GlassLayerId.Home,
): Modifier = composed {
    val id = remember { Any() }
    val registry = GlassPanels.of(layer)
    val cornerPx = with(LocalDensity.current) { shape.radiusDp.toPx() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(id) { onDispose { registry.remove(id) } }

    fun report(c: LayoutCoordinates) {
        if (!c.isAttached) return
        val r = c.boundsInRoot()
        if (r.width <= 0f || r.height <= 0f) return
        val scale = if (c.size.width > 0) r.width / c.size.width else 1f
        registry.update(id, r, cornerPx * scale, variant, lift, alpha)
    }

    // Heartbeat: onGloballyPositioned only fires on layout changes, but a graphicsLayer scale
    // (the focus spring) changes the on-screen bounds without a layout pass, and the registry
    // prunes panels not reported recently. So re-report every frame while composed.
    LaunchedEffect(id, lift, alpha) {
        while (true) {
            withFrameNanos { }
            coords?.let { report(it) }
        }
    }
    this
        .onGloballyPositioned { c -> coords = c; report(c) }
        .clip(shape)
}

/** Root size in px, set once by the home screen. */
object ScreenSize {
    var width = 0f
    var height = 0f
}
