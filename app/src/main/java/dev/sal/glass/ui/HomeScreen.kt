package dev.sal.glass.ui

import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.sal.glass.config.FolderTile
import dev.sal.glass.config.GlassMode
import dev.sal.glass.config.HourFormat
import dev.sal.glass.config.IntentTile
import dev.sal.glass.config.Tile
import dev.sal.glass.data.Apps
import dev.sal.glass.drop.Drop
import dev.sal.glass.ui.clock.Clock
import dev.sal.glass.ui.overlay.DropViewer
import dev.sal.glass.ui.dock.AppIcons
import dev.sal.glass.ui.dock.Dock
import dev.sal.glass.ui.dock.FolderRow
import dev.sal.glass.ui.status.TopRight
import dev.sal.glass.ui.glass.GlassBackdrop
import dev.sal.glass.ui.glass.GlassLayer
import dev.sal.glass.ui.glass.GlassLayerId
import dev.sal.glass.ui.glass.GlassPanels
import androidx.compose.runtime.withFrameNanos
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

private enum class Overlay { None, AllApps, Settings, Context, Folder, Drop }

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
    var openFolder by remember { mutableStateOf<FolderTile?>(null) }
    var topFocused by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val dockFocus = remember { FocusRequester() }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    // A tile to put focus back on once the dock has returned (after a folder closes).
    var pendingDockFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingDockFocus) {
        val id = pendingDockFocus ?: return@LaunchedEffect
        delay(80)
        runCatching { tileFocus[id]?.requestFocus() ?: dockFocus.requestFocus() }
        pendingDockFocus = null
    }
    val apps = remember(config) { Apps.launchable(context).filter { it.packageName !in config.hidden } }
    // While an overlay or the dock animates, nudge the GL layers every frame so their panels
    // track, without going through Compose state (a state tick recomposed the whole screen).
    LaunchedEffect(overlay, dockVisible) {
        val until = System.currentTimeMillis() + 700
        while (System.currentTimeMillis() < until) { GlassPanels.tickAll(); GlassPanels.requestRenderAll(); delay(16) }
    }
    // Every composed frame re-reports its panels; this advances the generation each frame so a
    // panel whose composable left (overlay closed, row recycled) is pruned within ~3 frames.
    LaunchedEffect(Unit) {
        while (true) { withFrameNanos { }; GlassPanels.tickAll() }
    }

    LaunchedEffect(config.wallpaper.intervalSec) { engine.intervalMs = config.wallpaper.intervalSec * 1000L; engine.start() }
    LaunchedEffect(wakeSignal) { engine.rescan(); AppIcons.forgetInstalled(); dockVisible = true; lastKeyAt = System.currentTimeMillis() }
    // Idle timer: the dock fades after idleHideSec without a key press, overlays excepted.
    LaunchedEffect(lastKeyAt, overlay, config.dock.idleHideSec) {
        if (config.dock.idleHideSec <= 0 || overlay != Overlay.None) return@LaunchedEffect
        delay(config.dock.idleHideSec * 1000L)
        dockVisible = false
    }

    fun wake() { lastKeyAt = System.currentTimeMillis(); dockVisible = true }

    // Something was dropped on the TV: a link goes to the browser, anything else to the viewer.
    LaunchedEffect(Drop.serial) {
        val file = Drop.incoming ?: return@LaunchedEffect
        wake()
        if (file.name.lowercase().endsWith(".url")) {
            val url = runCatching { file.readText().trim() }.getOrNull().orEmpty()
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { toast = "No browser for that link" }
        } else {
            overlay = Overlay.Drop
        }
    }

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
            is FolderTile -> { openFolder = tile; overlay = Overlay.Folder }
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
                        // Down from the dock opens All apps; from the settings button it just comes back down.
                        Key.DirectionDown -> if (topFocused) false else { overlay = Overlay.AllApps; true }
                        Key.MediaPlayPause -> { engine.togglePause(); toast = if (engine.paused) "Slideshow paused" else "Slideshow resumed"; true }
                        Key.MediaFastForward, Key.MediaNext -> { engine.skip(); true }
                        else -> wasIdle // a wake press is consumed; the next one navigates
                    }
                },
        ) {
            WallpaperLayer(engine, config.wallpaper.kenBurns)
            val dark = engine.current?.let { dockLuma(it.thumb) } ?: 0f
            val refraction = config.glass.mode == GlassMode.Refraction
            GlassLayer(GlassLayerId.Home, engine.current, engine.next, engine.fade, engine.zoom, dark, refraction)
            // All apps is a screen of its own: the clock and the dock leave while it is up, so the
            // grid sits on the photo alone instead of over the home chrome.
            val chrome = overlay != Overlay.AllApps && overlay != Overlay.Drop
            val clockAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(220), label = "clockAlpha")
            // Clock block centred at 43% of the height.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = clockAlpha }, contentAlignment = Alignment.Center) {
                Clock(config.clock.size, config.clock.hourFormat, Modifier.offset(y = (-0.07f * 540).dp))
            }
            // Network state and the settings button, top right, inside the overscan margin.
            TopRight(
                visible = dockVisible && chrome && (overlay == Overlay.None || overlay == Overlay.Folder),
                onSettings = { overlay = Overlay.Settings },
                onFocus = { topFocused = it },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 40.dp),
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Dock(
                    tiles = config.dockTiles,
                    // The dock drops away while a folder's row stands in its place.
                    visible = dockVisible && chrome && openFolder == null,
                    onLaunch = ::launch,
                    onContextMenu = { contextTile = it; overlay = Overlay.Context },
                    focusRequester = dockFocus,
                    tileFocus = tileFocus,
                    // The tile's label now sits under it inside the column (26 dp), so the row
                    // itself moves down to keep the tiles where they were.
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
                FolderRow(
                    folder = openFolder,
                    onLaunch = ::launch,
                    onDismiss = { val id = openFolder?.id; openFolder = null; overlay = Overlay.None; pendingDockFocus = id },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
                )
            }
            // Overlay glass paints above the clock and dock.
            GlassLayer(GlassLayerId.Overlay, engine.current, engine.next, engine.fade, engine.zoom, dark, refraction)
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
            DropViewer(Drop.incoming, Drop.serial, overlay == Overlay.Drop) { overlay = Overlay.None }
            Toast(toast) { toast = null }
        }
    }
}

/** 1 when the strip behind the dock is bright (snow, daytime sky), else 0, with a soft knee. */
private fun dockLuma(thumb: android.graphics.Bitmap): Float {
    var sum = 0f; var n = 0
    val y0 = (thumb.height * 0.76f).toInt(); val y1 = (thumb.height * 0.95f).toInt().coerceAtMost(thumb.height - 1)
    var y = y0
    while (y <= y1) { var x = thumb.width / 5; while (x < thumb.width * 4 / 5) { val c = thumb.getPixel(x, y); sum += (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f; n++; x += 4 }; y += 3 }
    val l = if (n == 0) 0.3f else sum / n
    return ((l - 0.45f) / 0.15f).coerceIn(0f, 1f)
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
        SettingRow("Glass", listOf("Refraction", "Frosted only"),
            { store.config.glass.mode.ordinal },
            { i -> store.update { c -> c.copy(glass = c.glass.copy(mode = GlassMode.entries[i])) } }),
        SettingRow("Rescan wallpapers", listOf("Now"), { 0 }, { engine.rescan() }),
        SettingRow("Drop to the TV", listOf(Drop.address() ?: "no network"), { 0 }, { }),
    )
}
