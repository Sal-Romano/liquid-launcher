package dev.sal.glass.ui.dock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.TileShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts

/** Remembers which dock tile had focus last, so the next one knows which way focus came from. */
class FocusTrail { var last: Int = -1 }

/**
 * One 84 dp glass squircle. On focus it lifts (scale 1.12, -6 dp) with a critically damped
 * spring, nods: it turns [NodDegrees] about its vertical axis away from the side focus came from
 * and springs back flat with a little overshoot, so the glass visibly pivots and its reflection
 * sweeps across; and after the lift settles its name fades in underneath, plain text with a
 * shadow. The column is always exactly [TileColumnWidth] wide: the label is laid out unbounded
 * and centred under the column, overflowing it on both sides when the name is wider than the
 * tile, so the row never reflows when focus moves.
 */
@Composable
fun Tile(
    label: String,
    icon: ImageBitmap?,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    glassAlpha: Float = 1f,
    index: Int = 0,
    trail: FocusTrail? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var cameFrom by remember { mutableStateOf(0f) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "lift")
    val liftPx = with(LocalDensity.current) { 6.dp.toPx() }
    val nod = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused && cameFrom != 0f) {
            nod.snapTo(cameFrom * NodDegrees)
            nod.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 260f))
        } else {
            nod.snapTo(0f)
        }
    }

    Column(modifier.width(TileColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        // The lift, nod and clip live inside glass(): the glass slab and the icon come from the
        // same values and the same layout rect. Do not add a graphicsLayer before it.
        Box(
            Modifier
                .size(84.dp)
                .glass(
                    TileShape, GlassVariant.Tile, focused = focused, lift = lift, alpha = glassAlpha,
                    liftScale = 0.12f, liftPx = liftPx, tiltY = nod.value + (if (focused) TiltHold else 0f),
                )
                .onFocusChanged {
                    if (it.isFocused && trail != null) {
                        cameFrom = if (trail.last < 0 || trail.last == index) 0f else if (index > trail.last) 1f else -1f
                        trail.last = index
                    }
                    focused = it.isFocused
                }
                .focusable()
                .tileClicks(onClick, onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            if (DebugFill) Box(Modifier.size(84.dp).background(Color.Red.copy(alpha = 0.35f)))
            if (icon != null) {
                Image(icon, label, Modifier.size(56.dp).graphicsLayer { alpha = if (dimmed) 0.45f else 1f })
            }
            if (dimmed) {
                Text(
                    "↓", Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = Color.White.copy(alpha = 0.85f), style = TextStyle(fontSize = 14.sp),
                )
            }
        }
        // The name, under the tile, once the lift has settled.
        Box(Modifier.width(TileColumnWidth).height(26.dp).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            androidx.compose.animation.AnimatedVisibility(visible = focused, enter = fadeIn(tween(220, delayMillis = 140)), exit = fadeOut(tween(100))) {
                Text(
                    label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true).widthIn(max = 220.dp),
                    style = TextStyle(
                        fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f),
                    ),
                )
            }
        }
    }
}

/** Keeps the dock's rhythm: a tile column is always 84 wide and 84 + 26 tall. */
val TileColumnWidth = 84.dp

/** How far a tile turns when focus lands on it, degrees about its vertical axis. */
const val NodDegrees = 14f

/** Calibration only: a steady extra rotationY on the focused tile, and a fill to show its clip. */
private const val TiltHold = 0f
private const val DebugFill = false
