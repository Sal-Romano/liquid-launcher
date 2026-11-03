package dev.sal.glass.ui.dock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.sal.glass.config.ActionTile
import dev.sal.glass.config.AppTile
import dev.sal.glass.config.FolderTile
import dev.sal.glass.config.HaEntityTile
import dev.sal.glass.config.IntentTile
import dev.sal.glass.config.Tile

/**
 * The one row of tiles. [visible] fades and lowers the whole row (220 ms) for the idle state; the
 * row keeps its focus while hidden so the next D-pad press both wakes it and moves within it.
 */
@Composable
fun Dock(
    tiles: List<Tile>,
    visible: Boolean,
    onLaunch: (Tile) -> Unit,
    onContextMenu: (Tile) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "dockAlpha")
    val dropPx = with(LocalDensity.current) { 24.dp.toPx() }
    val centred = tiles.size <= 8
    val firstRequester = remember { FocusRequester() }
    val trail = remember { FocusTrail() }

    LaunchedEffect(tiles.isNotEmpty()) { if (tiles.isNotEmpty()) runCatching { firstRequester.requestFocus() } }

    Box(modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; translationY = dropPx * (1f - alpha) }) {
        LazyRow(
            Modifier.fillMaxWidth().focusRequester(focusRequester).focusRestorer().focusGroup(),
            // Vertical room for the lifted, turning tile; LazyRow clips to its bounds.
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 12.dp),
            horizontalArrangement = if (centred) Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(tiles, key = { _, it -> it.id }) { index, tile ->
                val installed = (tile as? AppTile)?.let { AppIcons.isInstalled(context, it.packageName) } ?: true
                val icon = when (tile) {
                    is AppTile -> if (installed) AppIcons.forPackage(context, tile.packageName) else null
                    is ActionTile -> AppIcons.glyph(context, tile.action)
                    else -> null
                } ?: AppIcons.monogram(context, (tile.label ?: tile.id).firstOrNull() ?: '?')
                val label = tile.label ?: when (tile) {
                    is AppTile -> tile.packageName.substringAfterLast('.')
                    is ActionTile -> tile.action.name
                    is IntentTile -> "Shortcut"
                    is HaEntityTile -> tile.entityId
                    is FolderTile -> "Folder"
                }
                Tile(
                    label = label,
                    icon = icon,
                    dimmed = !installed,
                    onClick = { onLaunch(tile) },
                    onLongClick = { onContextMenu(tile) },
                    modifier = if (tile == tiles.first()) Modifier.focusRequester(firstRequester) else Modifier,
                    glassAlpha = alpha,
                    index = index,
                    count = tiles.size,
                    trail = trail,
                )
            }
        }
    }
}
