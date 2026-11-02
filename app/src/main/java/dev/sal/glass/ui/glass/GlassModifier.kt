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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity

enum class GlassVariant { Tile, Pill, Sheet }

/**
 * Marks a composable as a glass panel. The glass itself is painted by a [GlassLayer] (OpenGL ES,
 * real refraction); this modifier only reports where the panel is and clips the content to the
 * squircle. [layer] picks the GL pass (home chrome vs overlays, which paint above the clock and
 * dock). [alpha] lets a hiding row fade its glass too.
 *
 * Focus lift: the tile's own graphicsLayer scales it by (1 + liftScale * lift) about its centre
 * and raises it by liftPx * lift. The glass rect is computed here FROM THE SAME lift value, in
 * the same frame, so the glass and the icon can never disagree by a frame (reading the
 * transformed bounds back from layout gives last frame's transform and shows as a one-frame
 * "padding" during a spring).
 */
fun Modifier.glass(
    shape: SquircleShape,
    variant: GlassVariant = GlassVariant.Tile,
    focused: Boolean = false,
    lift: Float = if (focused) 1f else 0f,
    alpha: Float = 1f,
    layer: GlassLayerId = GlassLayerId.Home,
    liftScale: Float = 0f,
    liftPx: Float = 0f,
): Modifier = composed {
    val id = remember { Any() }
    val registry = GlassPanels.of(layer)
    val cornerPx = with(LocalDensity.current) { shape.radiusDp.toPx() }
    var layoutRect by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(id) { onDispose { registry.remove(id) } }

    fun report(base: Rect) {
        val s = 1f + liftScale * lift
        val cx = base.center.x
        val cy = base.center.y - liftPx * lift
        val hw = base.width * s / 2f
        val hh = base.height * s / 2f
        registry.update(id, Rect(cx - hw, cy - hh, cx + hw, cy + hh), cornerPx * s, variant, lift, alpha)
    }

    // Re-report whenever lift or alpha change (every frame of the spring), plus a heartbeat so a
    // panel keeps its registry entry alive while composed (the registry prunes stale ones).
    LaunchedEffect(id, lift, alpha) {
        layoutRect?.let { report(it) }
        while (true) {
            withFrameNanos { }
            layoutRect?.let { report(it) }
        }
    }
    this
        .onGloballyPositioned { c: LayoutCoordinates ->
            // Layout position without this node's own graphicsLayer transform: that transform is
            // re-derived from lift above. (positionInRoot includes ancestor transforms, which is
            // what we want for a tile inside a fading, dropping dock.)
            val pos = c.positionInRoot()
            val size = c.size
            val r = Rect(pos.x, pos.y, pos.x + size.width, pos.y + size.height)
            layoutRect = r
            report(r)
        }
        .clip(shape)
}

/** Root size in px, set once by the home screen. */
object ScreenSize {
    var width = 0f
    var height = 0f
}
