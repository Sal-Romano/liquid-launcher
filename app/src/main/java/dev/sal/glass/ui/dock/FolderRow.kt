package dev.sal.glass.ui.dock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.config.AppTile
import dev.sal.glass.config.FolderTile
import dev.sal.glass.config.Tile
import dev.sal.glass.ui.overlay.dismissOnBack
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.delay

private val RowEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * A folder opened from the dock: its tiles as a row of the same glass tiles, in the dock's place,
 * with the folder's name above. The dock drops away as this rises, and Back brings it back. The
 * tiles lift, turn and label themselves exactly like dock tiles.
 */
@Composable
fun FolderRow(folder: FolderTile?, onLaunch: (Tile) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val visible = folder != null
    // Keep the last folder while the row animates out.
    var shown by remember { mutableStateOf(folder) }
    if (folder != null) shown = folder
    val first = remember { FocusRequester() }
    val trail = remember(shown?.id) { FocusTrail() }
    LaunchedEffect(visible) { if (visible) { delay(80); runCatching { first.requestFocus() } } }
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = fadeIn(tween(260, easing = RowEasing)) + slideInVertically(tween(260, easing = RowEasing)) { it / 3 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 3 },
    ) {
        val f = shown
        if (f != null) {
            Column(Modifier.fillMaxWidth().dismissOnBack(onDismiss), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    f.label ?: "Folder", Modifier.padding(bottom = 10.dp), color = Color.White.copy(alpha = 0.92f),
                    style = TextStyle(
                        fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f),
                    ),
                )
                Row(Modifier.focusGroup().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    f.children.forEachIndexed { i, child ->
                        val installed = (child as? AppTile)?.let { AppIcons.isInstalled(context, it.packageName) } ?: true
                        Tile(
                            label = child.label ?: child.id,
                            icon = AppIcons.tileIcon(context, child),
                            dimmed = !installed,
                            onClick = { onLaunch(child) },
                            onLongClick = {},
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            index = i,
                            count = f.children.size,
                            trail = trail,
                        )
                    }
                }
            }
        }
    }
}
