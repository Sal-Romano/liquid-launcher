package dev.sal.glass.ui.dock

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.PillShape
import dev.sal.glass.ui.glass.TileShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts

/**
 * One 84 dp glass squircle. On focus it lifts (scale 1.12, -6 dp) with a spring and shows its
 * label in a pill above. The pill occupies the column's top 36 dp whether visible or not, so the
 * row never reflows when focus moves.
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
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "lift")
    val liftPx = with(LocalDensity.current) { 6.dp.toPx() }

    // The label slot is taller than the pill and the pill sits at its bottom, so the lifted tile
    // (-6 dp) and the pill's upward shadow both stay inside the column instead of being clipped.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(48.dp).padding(bottom = 10.dp), contentAlignment = Alignment.BottomCenter) {
            androidx.compose.animation.AnimatedVisibility(visible = focused, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.height(28.dp).glass(PillShape, GlassVariant.Pill, alpha = glassAlpha).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color.White, style = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 16.sp), maxLines = 1)
                }
            }
        }
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer {
                    val s = 1f + 0.12f * lift
                    scaleX = s; scaleY = s
                    translationY = -liftPx * lift
                }
                .glass(TileShape, GlassVariant.Tile, focused = focused, lift = lift, alpha = glassAlpha, liftScale = 0.12f, liftPx = liftPx)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .tileClicks(onClick, onLongClick),
            contentAlignment = Alignment.Center,
        ) {
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
    }
}

/** Keeps the dock's rhythm: a tile column is always 84 wide and 36 + 84 tall. */
val TileColumnWidth = 84.dp
@Suppress("unused")
private val keepWidth = Modifier.width(TileColumnWidth)
