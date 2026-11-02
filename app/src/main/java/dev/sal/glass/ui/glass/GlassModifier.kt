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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity

enum class GlassVariant { Tile, Pill, Sheet }

/**
 * Marks a composable as a glass panel. The glass itself is painted by a [GlassLayer] (OpenGL ES,
 * real refraction); this modifier reports where the panel is, applies the focus lift, and clips
 * the content to the squircle. [layer] picks the GL pass (home chrome vs overlays, which paint
 * above the clock and dock). [alpha] lets a hiding row fade its glass too.
 *
 * Focus lift: the panel scales by (1 + liftScale * lift) about its centre and rises by
 * liftPx * lift. This modifier owns that transform, and it sits AFTER the position report in the
 * chain, so the reported rect is the plain layout rect (ancestor transforms such as the dock's
 * drop included, this panel's own lift excluded). The glass rect is then derived from the same
 * lift value the transform uses, in the same frame. Reading the position from inside the lift
 * layer would hand back the scaled corner with the unscaled size and put the slab up-left of
 * the icon by 6% of its width at full lift; the callers must therefore not add their own lift
 * graphicsLayer before this modifier.
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
    val lifts = liftScale != 0f || liftPx != 0f
    this
        .onGloballyPositioned { c: LayoutCoordinates ->
            // Outside the lift layer below: plain layout coordinates. Compose re-dispatches this
            // when an ancestor's layer moves (the dock dropping), which is wanted.
            val pos = c.positionInRoot()
            val size = c.size
            val r = Rect(pos.x, pos.y, pos.x + size.width, pos.y + size.height)
            layoutRect = r
            report(r)
        }
        .then(
            if (lifts) Modifier.graphicsLayer {
                val s = 1f + liftScale * lift
                scaleX = s; scaleY = s
                translationY = -liftPx * lift
            } else Modifier,
        )
        .clip(shape)
}

/** Root size in px, set once by the home screen. */
object ScreenSize {
    var width = 0f
    var height = 0f
}
