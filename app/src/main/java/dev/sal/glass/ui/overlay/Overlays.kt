package dev.sal.glass.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.data.AppEntry
import dev.sal.glass.ui.Sounds
import dev.sal.glass.ui.dock.AppIcons
import dev.sal.glass.ui.dock.tileClicks
import dev.sal.glass.ui.glass.GlassLayerId
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.PillShape
import dev.sal.glass.ui.glass.SheetShape
import dev.sal.glass.ui.glass.TileShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.delay

private val SheetEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Label = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 16.sp)
private val Title = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)

/** Every launchable app, 7 per row, over a dimming scrim. Rises from the bottom. */
@Composable
fun AllApps(visible: Boolean, apps: List<AppEntry>, onLaunch: (AppEntry) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val glassAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 280 else 160), label = "allAppsGlass")
    LaunchedEffect(visible) { if (visible) { delay(50); runCatching { first.requestFocus() } } }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(280, easing = SheetEasing)) + slideInVertically(tween(280, easing = SheetEasing)) { it / 8 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 8 },
    ) {
        val context = LocalContext.current
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).dismissOnBack(onDismiss)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
                Text("All apps", Modifier.padding(top = 44.dp, bottom = 24.dp), color = Color.White, style = Title)
                LazyVerticalGrid(
                    GridCells.Fixed(7),
                    Modifier.fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(horizontal = 78.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        var focused by remember { mutableStateOf(false) }
                        val lift = animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "gridLift")
                        Column(
                            Modifier.width(84.dp).then(if (app == apps.first()) Modifier.focusRequester(first) else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // glass() owns the focus scale; see GlassModifier.
                            Box(
                                Modifier
                                    .size(84.dp)
                                    .glass(TileShape, GlassVariant.Tile, focused = focused, lift = { lift.value }, alpha = { glassAlpha }, layer = GlassLayerId.Overlay, liftScale = 0.08f)
                                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) Sounds.tick() }
                                    .focusable()
                                    .tileClicks({ onLaunch(app) }, {}),
                                contentAlignment = Alignment.Center,
                            ) {
                                val icon = AppIcons.forPackage(context, app.packageName)
                                    ?: AppIcons.monogram(context, app.label.firstOrNull() ?: '?')
                                Image(icon, app.label, Modifier.size(56.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            // The cell is as wide as the tile, so a label may run into the gutters and onto a
                            // second line ("Amazon Music", "Spotify (Kyle)") instead of losing every word but its first.
                            Text(
                                app.label, Modifier.requiredWidth(96.dp),
                                color = Color.White.copy(alpha = if (focused) 1f else 0.8f), style = Label,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Move / hide a dock tile. */
@Composable
fun ContextMenu(
    visible: Boolean, title: String,
    onMoveLeft: () -> Unit, onMoveRight: () -> Unit, onHide: () -> Unit, onFirst: () -> Unit, onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(visible) { if (visible) { delay(50); runCatching { first.requestFocus() } } }
    AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        Box(Modifier.fillMaxSize().dismissOnBack(onDismiss), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.padding(bottom = 136.dp).width(220.dp).glass(SheetShape, GlassVariant.Sheet, layer = GlassLayerId.Overlay).padding(8.dp).focusGroup(),
            ) {
                Text(title, Modifier.padding(12.dp, 8.dp), color = Color.White.copy(alpha = 0.7f), style = Label)
                listOf("Move left" to onMoveLeft, "Move right" to onMoveRight, "Set as first" to onFirst, "Hide" to onHide)
                    .forEachIndexed { i, (name, action) ->
                        MenuRow(name, if (i == 0) Modifier.focusRequester(first) else Modifier) { action(); onDismiss() }
                    }
            }
        }
    }
}

@Composable
private fun MenuRow(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth().height(40.dp)
            .then(if (focused) Modifier.glass(PillShape, GlassVariant.Pill, focused = true, layer = GlassLayerId.Overlay) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) Sounds.tick() }
            .focusable()
            .tileClicks({ Sounds.tap(); onClick() }, {})
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(name, color = Color.White, style = Label) }
}

/** A setting the sheet can show and cycle with Left/Right. */
class SettingRow(val name: String, val values: List<String>, val index: () -> Int, val set: (Int) -> Unit)

/** Right-anchored sheet. Left/Right cycle the focused row's value. */
@Composable
fun SettingsSheet(visible: Boolean, rows: List<SettingRow>, version: String, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(visible) { if (visible) { delay(50); runCatching { first.requestFocus() } } }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(240, easing = SheetEasing)) + slideInVertically(tween(240, easing = SheetEasing)) { it / 12 },
        exit = fadeOut(tween(160)),
    ) {
        Box(Modifier.fillMaxSize().dismissOnBack(onDismiss), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.padding(end = 32.dp).width(380.dp).glass(SheetShape, GlassVariant.Sheet, layer = GlassLayerId.Overlay).padding(20.dp, 16.dp).focusGroup(),
            ) {
                Text("Settings", Modifier.padding(bottom = 12.dp), color = Color.White, style = Title)
                rows.forEachIndexed { i, row ->
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth().height(48.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .then(if (focused) Modifier.glass(PillShape, GlassVariant.Pill, focused = true, layer = GlassLayerId.Overlay) else Modifier)
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) Sounds.tick() }
                            .focusable()
                            .onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key) {
                                    Key.DirectionLeft -> { row.set((row.index() - 1 + row.values.size) % row.values.size); Sounds.tick(); true }
                                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> { row.set((row.index() + 1) % row.values.size); Sounds.tick(); true }
                                    else -> false
                                }
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.name, Modifier.weight(1f), color = Color.White, style = Label)
                        Text(row.values[row.index()], color = Color.White.copy(alpha = 0.85f), style = Label)
                    }
                }
                Text("Glass $version", Modifier.padding(14.dp, 12.dp, 0.dp, 0.dp), color = Color.White.copy(alpha = 0.5f), style = Label)
            }
        }
    }
}

/** Bottom-centre pill that fades after 2.5 s. */
@Composable
fun Toast(message: String?, onDone: () -> Unit) {
    LaunchedEffect(message) { if (message != null) { delay(2500); onDone() } }
    AnimatedVisibility(message != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.padding(top = 32.dp).height(36.dp).glass(PillShape, GlassVariant.Pill, layer = GlassLayerId.Overlay).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(message ?: "", color = Color.White, style = Label, maxLines = 1) }
        }
    }
}

/** Back closes an overlay without reaching the home screen's router. */
fun Modifier.dismissOnBack(onDismiss: () -> Unit): Modifier = onKeyEvent { e ->
    if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { onDismiss(); true } else e.key == Key.Back
}
