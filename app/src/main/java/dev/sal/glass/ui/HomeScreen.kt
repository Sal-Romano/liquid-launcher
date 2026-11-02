package dev.sal.glass.ui

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sal.glass.BuildConfig
import dev.sal.glass.config.Action
import dev.sal.glass.config.ActionTile
import dev.sal.glass.config.AppTile
import dev.sal.glass.config.ClockSize
import dev.sal.glass.config.ConfigStore
import dev.sal.glass.config.HourFormat
import dev.sal.glass.config.IntentTile
import dev.sal.glass.config.Tile
import dev.sal.glass.data.Apps
import dev.sal.glass.ui.clock.Clock
import dev.sal.glass.ui.dock.AppIcons
import dev.sal.glass.ui.dock.Dock
import dev.sal.glass.ui.glass.GlassBackdrop
import dev.sal.glass.ui.glass.LocalGlassBackdrop
import dev.sal.glass.ui.glass.ScreenSize
import dev.sal.glass.ui.overlay.AllApps
import dev.sal.glass.ui.overlay.ContextMenu
import dev.sal.glass.ui.overlay.SettingRow
import dev.sal.glass.ui.overlay.SettingsSheet
import dev.sal.glass.ui.overlay.Toast
import dev.sal.glass.wallpaper.WallpaperEngine
import dev.sal.glass.wallpaper.WallpaperLayer
import kotlinx.coroutines.delay

private enum class Overlay { None, AllApps, Settings, Context }

/**
 * Photo → clock → dock → overlays. One key router at the root: in the idle state any key only
 * wakes the dock; Back hides the dock and never leaves the launcher; Menu opens settings.
 */
@Composable
fun HomeScreen(engine: WallpaperEngine, store: ConfigStore, wakeSignal: Long) {
    val context = LocalContext.current
    val config = store.config
    var dockVisible by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var contextTile by remember { mutableStateOf<Tile?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val dockFocus = remember { FocusRequester() }
    val apps = remember(config) { Apps.launchable(context).filter { it.packageName !in config.hidden } }

    LaunchedEffect(config.wallpaper.intervalSec) { engine.intervalMs = config.wallpaper.intervalSec * 1000L; engine.start() }
    LaunchedEffect(wakeSignal) { engine.rescan(); dockVisible = true; lastKeyAt = System.currentTimeMillis() }
    // Idle timer: the dock fades after idleHideSec without a key press, overlays excepted.
    LaunchedEffect(lastKeyAt, overlay, config.dock.idleHideSec) {
        if (config.dock.idleHideSec <= 0 || overlay != Overlay.None) return@LaunchedEffect
        delay(config.dock.idleHideSec * 1000L)
        dockVisible = false
    }

    fun wake() { lastKeyAt = System.currentTimeMillis(); dockVisible = true }

    fun launch(tile: Tile) {
        when (tile) {
            is AppTile -> {
                if (!AppIcons.isInstalled(context, tile.packageName)) { toast = "Install ${tile.label ?: tile.packageName} from the Amazon Appstore"; return }
                val (pkg, cls) = tile.component.split('/', limit = 2)
                val intent = context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setClassName(pkg, cls)
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w("Glass", "launch failed: ${tile.component}", it); toast = "Could not open ${tile.label}" }
            }
            is IntentTile -> runCatching { context.startActivity(Intent.parseUri(tile.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { toast = "Could not open shortcut" }
            is ActionTile -> when (tile.action) {
                Action.AllApps -> overlay = Overlay.AllApps
                Action.Settings -> overlay = Overlay.Settings
                Action.NextWallpaper -> engine.skip()
                Action.PauseSlideshow -> { engine.togglePause(); toast = if (engine.paused) "Slideshow paused" else "Slideshow resumed" }
            }
            else -> toast = "Coming soon"
        }
    }

    val backdrop = GlassBackdrop(
        current = engine.current?.thumb,
        next = engine.next?.thumb,
        progress = 0f,
    )

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { ScreenSize.width = it.width.toFloat(); ScreenSize.height = it.height.toFloat() }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val wasIdle = !dockVisible
                    wake()
                    if (overlay != Overlay.None) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Back -> { if (!wasIdle) dockVisible = false; true }
                        Key.Menu -> { overlay = Overlay.Settings; true }
                        Key.DirectionDown -> { overlay = Overlay.AllApps; true }
                        Key.MediaPlayPause -> { engine.togglePause(); toast = if (engine.paused) "Slideshow paused" else "Slideshow resumed"; true }
                        Key.MediaFastForward, Key.MediaNext -> { engine.skip(); true }
                        else -> wasIdle // a wake press is consumed; the next one navigates
                    }
                },
        ) {
            WallpaperLayer(engine, config.wallpaper.kenBurns)
            // Clock block centred at 43% of the height.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Clock(config.clock.size, config.clock.hourFormat, Modifier.offset(y = (-0.07f * 540).dp))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Dock(
                    tiles = config.dockTiles,
                    visible = dockVisible,
                    onLaunch = ::launch,
                    onContextMenu = { contextTile = it; overlay = Overlay.Context },
                    focusRequester = dockFocus,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 36.dp),
                )
            }
            AllApps(overlay == Overlay.AllApps, apps, onLaunch = { app ->
                runCatching { context.startActivity(app.launchIntent) }; overlay = Overlay.None
            }, onDismiss = { overlay = Overlay.None })
            ContextMenu(
                overlay == Overlay.Context, contextTile?.label ?: "",
                onMoveLeft = { contextTile?.let { store.move(it.id, -1) } },
                onMoveRight = { contextTile?.let { store.move(it.id, +1) } },
                onHide = { contextTile?.let { store.hide(it.id) } },
                onFirst = { contextTile?.let { store.moveToFront(it.id) } },
                onDismiss = { overlay = Overlay.None },
            )
            SettingsSheet(overlay == Overlay.Settings, settingsRows(store, engine), BuildConfig.VERSION_NAME) { overlay = Overlay.None }
            Toast(toast) { toast = null }
        }
    }
}

private fun settingsRows(store: ConfigStore, engine: WallpaperEngine): List<SettingRow> {
    val intervals = listOf(30, 60, 120, 300, 900)
    val idle = listOf(5, 10, 20, 0)
    return listOf(
        SettingRow("Wallpaper interval", listOf("30 s", "1 min", "2 min", "5 min", "15 min"),
            { intervals.indexOf(store.config.wallpaper.intervalSec).coerceAtLeast(0) },
            { i -> store.update { c -> c.copy(wallpaper = c.wallpaper.copy(intervalSec = intervals[i])) } }),
        SettingRow("Ken Burns", listOf("Off", "On"),
            { if (store.config.wallpaper.kenBurns) 1 else 0 },
            { i -> store.update { c -> c.copy(wallpaper = c.wallpaper.copy(kenBurns = i == 1)) } }),
        SettingRow("Clock size", listOf("Small", "Medium", "Large"),
            { store.config.clock.size.ordinal },
            { i -> store.update { c -> c.copy(clock = c.clock.copy(size = ClockSize.entries[i])) } }),
        SettingRow("Hour format", listOf("System", "12 h", "24 h"),
            { store.config.clock.hourFormat.ordinal },
            { i -> store.update { c -> c.copy(clock = c.clock.copy(hourFormat = HourFormat.entries[i])) } }),
        SettingRow("Hide dock after", listOf("5 s", "10 s", "20 s", "Never"),
            { idle.indexOf(store.config.dock.idleHideSec).coerceAtLeast(0) },
            { i -> store.update { c -> c.copy(dock = c.dock.copy(idleHideSec = idle[i])) } }),
        SettingRow("Rescan wallpapers", listOf("Now"), { 0 }, { engine.rescan() }),
    )
}
