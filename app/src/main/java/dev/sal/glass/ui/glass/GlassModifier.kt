package dev.sal.glass.ui.glass

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * The camera that projects a panel's 3D rotation (graphicsLayer.cameraDistance). The platform
 * turns the unit into 72 px regardless of density (Compose's default 8 is 576 px); 4.2 puts the
 * camera about 300 px in front of the pivot, close enough that a turned 84 dp tile reads as a
 * slab in perspective from the couch. The GL slab is projected with the same camera so it stays
 * under the icon it carries (verified within 1 px at a held 20 degree turn).
 */
const val GlassCameraUnits = 4.2f
const val GlassCameraPx = GlassCameraUnits * 72f

/**
 * Marks a composable as a glass panel. The glass itself is painted by a [GlassLayer] (OpenGL ES,
 * real refraction); this modifier reports where the panel is, applies the focus lift and tilt,
 * and clips the content to the squircle. [layer] picks the GL pass (home chrome vs overlays,
 * which paint above the clock and dock). [alpha] lets a hiding row fade its glass too.
 *
 * [lift], [alpha], [tiltX] and [tiltY] are lambdas, read in the draw phase and in this panel's
 * own frame loop, never in composition: a tile that lifts and turns for half a second does not
 * recompose once for it. (Reading the animated values in composition recomposed every animating
 * tile on every frame and re-ran this modifier's factory; on the TV that was the jank.)
 *
 * Focus lift: the panel scales by (1 + liftScale * lift) about its centre and rises by
 * liftPx * lift. tiltX and tiltY rotate it in 3D about its centre (Compose rotationX/Y,
 * degrees); the GL slab is drawn with the same rotation and camera. This modifier owns those
 * transforms, and it sits AFTER the position report in the chain, so the reported rect is the
 * plain layout rect (ancestor transforms such as the dock's drop included, this panel's own lift
 * excluded). The glass rect is then derived from the same lift value the transform uses, in the
 * same frame. The callers must not add their own lift graphicsLayer before this modifier.
 */
fun Modifier.glass(
    shape: SquircleShape,
    variant: GlassVariant = GlassVariant.Tile,
    focused: Boolean = false,
    lift: () -> Float = if (focused) ONE else ZERO,
    alpha: () -> Float = ONE,
    layer: GlassLayerId = GlassLayerId.Home,
    liftScale: Float = 0f,
    liftPx: Float = 0f,
    tiltX: () -> Float = ZERO,
    tiltY: () -> Float = ZERO,
): Modifier = composed {
    val id = remember { Any() }
    val registry = GlassPanels.of(layer)
    val cornerPx = with(LocalDensity.current) { shape.radiusDp.toPx() }
    var layoutRect by remember { mutableStateOf<Rect?>(null) }
    // The latest lambdas and parameters, for the one frame loop below.
    val liftNow by rememberUpdatedState(lift)
    val alphaNow by rememberUpdatedState(alpha)
    val tiltXNow by rememberUpdatedState(tiltX)
    val tiltYNow by rememberUpdatedState(tiltY)
    val variantNow by rememberUpdatedState(variant)
    val liftScaleNow by rememberUpdatedState(liftScale)
    val liftPxNow by rememberUpdatedState(liftPx)
    DisposableEffect(id) { onDispose { registry.remove(id) } }

    fun report(base: Rect) {
        val l = liftNow()
        val s = 1f + liftScaleNow * l
        val cx = base.center.x
        val cy = base.center.y - liftPxNow * l
        val hw = base.width * s / 2f
        val hh = base.height * s / 2f
        registry.update(id, Rect(cx - hw, cy - hh, cx + hw, cy + hh), cornerPx * s, variantNow, l, alphaNow(), tiltXNow(), tiltYNow(), GlassCameraPx)
    }

    // One loop for the panel's life: every frame, re-report with the current lift, alpha and
    // tilt (the registry ignores unchanged reports) and keep the registry entry alive.
    LaunchedEffect(id) {
        while (true) {
            withFrameNanos { }
            layoutRect?.let { report(it) }
        }
    }
    val transforms = liftScale != 0f || liftPx != 0f || tiltX !== ZERO || tiltY !== ZERO
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
            if (transforms) Modifier.graphicsLayer {
                val l = lift()
                val s = 1f + liftScale * l
                scaleX = s; scaleY = s
                translationY = -liftPx * l
                rotationX = tiltX()
                rotationY = tiltY()
                cameraDistance = GlassCameraUnits
            } else Modifier,
        )
        .clip(shape)
}

private val ZERO: () -> Float = { 0f }
private val ONE: () -> Float = { 1f }

/** Root size in px, set once by the home screen. */
object ScreenSize {
    var width = 0f
    var height = 0f
}
