package dev.sal.glass.ui.dock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import dev.sal.glass.ui.Sounds
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.TileShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Remembers which dock tile had focus last, so the next one knows which way focus came from. */
class FocusTrail { var last: Int = -1 }

/**
 * One 84 dp glass squircle. On focus it lifts (scale 1.12, -6 dp) with a critically damped
 * spring and pivots about its vertical axis: it is kicked away from the side focus came from and
 * swings, with a little overshoot, to its resting angle, which turns it toward the middle of the
 * screen by up to [RestDegrees] at the ends of the row (a tile on the left shows you its right
 * edge receding, one on the right the opposite, the centre one faces you square). No two landings
 * are alike: the kick is a different share of [NodDegrees] each time, and the pane also tips up or
 * down by up to [TipDegrees] and swings level. The glass slab is drawn in the same perspective, so
 * the reflection sweeps across the pane as it turns. After the lift settles its name fades in underneath, plain text with a shadow. The
 * column is always exactly [TileColumnWidth] wide: the label is laid out unbounded and centred
 * under the column, overflowing it on both sides when the name is wider than the tile, so the row
 * never reflows when focus moves.
 */
@Composable
fun Tile(
    label: String,
    icon: ImageBitmap?,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    glassAlpha: () -> Float = { 1f },
    index: Int = 0,
    count: Int = 1,
    trail: FocusTrail? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var cameFrom by remember { mutableStateOf(0f) }
    // Read in the draw phase by glass(), never here: the tile does not recompose while it moves.
    val lift = animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "lift")
    val liftPx = with(LocalDensity.current) { 6.dp.toPx() }
    // Positive rotationY recedes the right edge: a tile left of the middle turns toward it.
    val middle = (count - 1) / 2f
    val restTilt = if (count > 1) (middle - index) / middle * RestDegrees else 0f
    val turn = remember { Animatable(0f) }
    // About the horizontal axis: the few degrees up or down that make each landing its own.
    val tip = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            // Never the same landing twice. The kick away from where focus came from is 60 to 125 %
            // of NodDegrees (a gentler one, either way, when focus did not come along the row), and
            // the pane tips up or down by a third to all of TipDegrees. Both swing home together: a
            // quick, slightly underdamped spring (about half a second) that overshoots once before
            // it settles. The TV draws it at full rate; the emulator cannot.
            val side = if (cameFrom != 0f) cameFrom else if (Random.nextBoolean()) 0.4f else -0.4f
            turn.snapTo(restTilt + side * NodDegrees * between(0.6f, 1.25f))
            tip.snapTo((if (Random.nextBoolean()) 1f else -1f) * TipDegrees * between(0.35f, 1f))
            val swing = spring<Float>(dampingRatio = 0.5f, stiffness = 300f)
            launch { tip.animateTo(0f, swing) }
            turn.animateTo(restTilt, swing)
        } else {
            val settle = spring<Float>(dampingRatio = 1f, stiffness = 420f)
            launch { tip.animateTo(0f, settle) }
            turn.animateTo(0f, settle)
        }
    }

    Column(modifier.width(TileColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        // The lift, turn and clip live inside glass(): the glass slab and the icon come from the
        // same values and the same layout rect. Do not add a graphicsLayer before it.
        Box(
            Modifier
                .size(84.dp)
                .glass(
                    TileShape, GlassVariant.Tile, focused = focused, lift = { lift.value }, alpha = glassAlpha,
                    liftScale = 0.12f, liftPx = liftPx, tiltX = { tip.value }, tiltY = { turn.value },
                )
                .onFocusChanged {
                    if (it.isFocused) Sounds.tick()
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
            if (icon != null) {
                Image(icon, label, Modifier.size(56.dp).graphicsLayer { alpha = if (dimmed) 0.62f else 1f })
            }
            if (dimmed) {
                Text(
                    "↓", Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = Color.White.copy(alpha = 0.85f), style = TextStyle(fontSize = 14.sp),
                )
            }
        }
        // The name, under the tile, once the lift and most of the swing have settled. The slot is
        // wider than the column (centred over it) and the fade is in the text colour, not a layer:
        // Fire OS clipped a fading layer to the column and cut "Amazon Music" to "mazon Mus".
        val labelAlpha by animateFloatAsState(
            if (focused) 1f else 0f,
            if (focused) tween(180, delayMillis = 120) else tween(90),
            label = "labelAlpha",
        )
        Box(Modifier.requiredWidth(LabelSlotWidth).height(26.dp).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            if (labelAlpha > 0.01f) {
                Text(
                    label, color = Color.White.copy(alpha = labelAlpha), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.6f * labelAlpha), Offset(0f, 1f), blurRadius = 8f),
                    ),
                )
            }
        }
    }
}

/** Keeps the dock's rhythm: a tile column is always 84 wide and 84 + 26 tall. */
val TileColumnWidth = 84.dp

/** The label may be this wide, centred under its 84 dp column, without reflowing the row. */
val LabelSlotWidth = 220.dp

/**
 * The kick when focus lands: degrees about the vertical axis, away from where focus came from.
 * Each landing takes a different share of it, 60 to 125 %.
 */
const val NodDegrees = 26f

/** The most a tile tips up or down as it lands (about its horizontal axis); at least a third of it. */
const val TipDegrees = 10f

/** A uniformly random value from [from] to [to]. */
private fun between(from: Float, to: Float) = from + (to - from) * Random.nextFloat()

/** The resting turn toward the middle of the screen for a focused tile at either end of the row. */
const val RestDegrees = 20f
