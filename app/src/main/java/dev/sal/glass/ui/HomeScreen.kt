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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.sal.glass.BuildConfig
import dev.sal.glass.config.Action
import dev.sal.glass.config.ActionTile
import dev.sal.glass.config.AppTile
import dev.sal.glass.config.ClockSize
import dev.sal.glass.config.ConfigStore
import dev.sal.glass.config.FolderTile
import dev.sal.glass.config.HookTile
import dev.sal.glass.config.GlassMode
import dev.sal.glass.config.HourFormat
import dev.sal.glass.config.IntentTile
import dev.sal.glass.config.SoundMode
import dev.sal.glass.config.AwakeConfig
import dev.sal.glass.config.VolumeConfig
import dev.sal.glass.config.Tile
import dev.sal.glass.data.Apps
import dev.sal.glass.drop.Drop
import dev.sal.glass.phone.Phone
import dev.sal.glass.phone.PhoneLink
import dev.sal.glass.phone.Insights
import dev.sal.glass.data.PhotoInfo
import dev.sal.glass.ui.overlay.PhotoInfoCard
import androidx.compose.ui.graphics.ImageBitmap
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
import dev.sal.glass.ui.overlay.WallpapersScreen
import dev.sal.glass.wallpaper.WallpaperEngine
import dev.sal.glass.wallpaper.WallpaperLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope

private enum class Overlay { None, AllApps, Settings, Context, Folder, Drop, Wallpapers, Info }

/**
 * Photo → clock → dock → overlays. One key router at the root: in the idle state any key only
 * wakes the dock; Back hides the dock and never leaves the launcher; Menu opens settings.
 */
@Composable
fun HomeScreen(engine: WallpaperEngine, store: ConfigStore, phone: PhoneLink, wakeSignal: Long, homeSignal: Long = 0L) {
    val context = LocalContext.current
    val config = store.config
    var dockVisible by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var contextTile by remember { mutableStateOf<Tile?>(null) }
    var openFolder by remember { mutableStateOf<FolderTile?>(null) }
    var topFocused by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Something still under way (a hook tile waiting for its answer): shown until it is done,
    // under any passing toast.
    var pending by remember { mutableStateOf<String?>(null) }
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
    val apps = remember(config) {
        Apps.launchable(context)
            .filter { it.packageName !in config.hidden }
            // Renamed apps keep their place: the two Spotifys stay together as "Kyle" and "Sal".
            .map { app -> config.labels[app.packageName]?.let { app.copy(label = it) } ?: app }
    }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Sounds.init(context) }
    // A photo frame is not inactivity: hold the screen on while we are in front, for as long as
    // configured since the last key. Fire TV's own timeout (21 min on the Hisense) resumes after.
    LaunchedEffect(config.awake.minutes, lastKeyAt) {
        val minutes = config.awake.minutes
        view.keepScreenOn = minutes >= 0
        if (minutes > 0) { delay(minutes * 60_000L); view.keepScreenOn = false }
    }
    LaunchedEffect(config.sound.mode) {
        Sounds.mode = config.sound.mode
        // The stock click comes through the view's sound effects; ours replace it or nothing does.
        val stock = config.sound.mode == SoundMode.System
        view.isSoundEffectsEnabled = stock
        view.rootView.isSoundEffectsEnabled = stock
    }
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
    // The phone link follows launcher.json's relay, or the build's own.
    LaunchedEffect(config.phone.server) { phone.start(config.phone.server ?: BuildConfig.PHONE_SERVER) }
    LaunchedEffect(wakeSignal) { engine.rescan(); AppIcons.forgetInstalled(); dockVisible = true; lastKeyAt = System.currentTimeMillis() }
    // Home closes whatever is open (a sheet, a screen, the info card, a folder): back to the photo and the dock.
    LaunchedEffect(homeSignal) {
        if (homeSignal == 0L) return@LaunchedEffect
        overlay = Overlay.None
        openFolder = null
    }
    // Idle timer: the dock fades after idleHideSec without a key press, overlays excepted.
    LaunchedEffect(lastKeyAt, overlay, config.dock.idleHideSec) {
        if (config.dock.idleHideSec <= 0 || overlay != Overlay.None) return@LaunchedEffect
        delay(config.dock.idleHideSec * 1000L)
        dockVisible = false
    }

    fun wake() { lastKeyAt = System.currentTimeMillis(); dockVisible = true }

    // About the photo on screen, while the info card is up: what is known at once, then the rest
    // (its EXIF, the place through the relay) and the map. Follows the photo if it changes meanwhile.
    val infoFile = engine.current?.file
    var info by remember { mutableStateOf<PhotoInfo?>(null) }
    var infoMap by remember { mutableStateOf<ImageBitmap?>(null) }
    var infoMapLoading by remember { mutableStateOf(false) }
    LaunchedEffect(overlay == Overlay.Info, infoFile) {
        if (overlay != Overlay.Info || infoFile == null) return@LaunchedEffect
        info = null
        infoMap = null
        infoMapLoading = true
        val known = Insights.about(infoFile, phone)
        info = known
        infoMap = Insights.map(context, known, phone)
        infoMapLoading = false
    }

    // News from the phone link ("Test from iPhone · not saved"). A photo on its way up also gets
    // Settings or the Wallpapers screen (where the QR code was scanned) out of the way, so the
    // crossfade is seen.
    LaunchedEffect(Phone.notices) {
        if (Phone.notices == 0L) return@LaunchedEffect
        toast = Phone.notice
        if (Phone.noticeClearsScreen && (overlay == Overlay.Settings || overlay == Overlay.Wallpapers)) overlay = Overlay.None
    }

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
        Sounds.tap()
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
                Action.None -> Unit
                Action.Wallpapers -> overlay = Overlay.Wallpapers
            }
            is FolderTile -> { openFolder = tile; overlay = Overlay.Folder }
            is HookTile -> {
                // The other end answers when it is done (Dolphin up, TV switched), so this can take
                // a while: say so until then, and ignore a second press meanwhile.
                if (pending != null) return
                val name = tile.label ?: "that"
                pending = "Starting $name…"
                scope.launch(Dispatchers.IO) {
                    val problem = callHook(tile.url, name)
                    Log.i("Glass", "hook ${tile.id}: ${problem ?: "ok"}")
                    withContext(Dispatchers.Main) { pending = null; toast = problem }
                }
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
                    Sounds.keyAt = android.os.SystemClock.uptimeMillis()
                    val wasIdle = !dockVisible
                    wake()
                    // The info card is a label on the photo and never takes the remote, so it does not
                    // depend on where focus is: Fast-forward moves on (the card follows the photo), the
                    // arrows do nothing (the dock is hidden behind it), anything else closes it.
                    if (overlay == Overlay.Info) {
                        when (e.key) {
                            Key.MediaFastForward, Key.MediaNext -> engine.skip()
                            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> Unit
                            else -> overlay = Overlay.None
                        }
                        return@onPreviewKeyEvent true
                    }
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
            val chrome = overlay != Overlay.AllApps && overlay != Overlay.Drop && overlay != Overlay.Wallpapers && overlay != Overlay.Info
            val clockAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(220), label = "clockAlpha")
            // Clock block centred at 43% of the height.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = clockAlpha }, contentAlignment = Alignment.Center) {
                Clock(config.clock.size, config.clock.hourFormat, Modifier.offset(y = (-0.07f * 540).dp))
            }
            // Network state and the settings button, top right, inside the overscan margin.
            TopRight(
                visible = dockVisible && chrome && (overlay == Overlay.None || overlay == Overlay.Folder),
                onSettings = { overlay = Overlay.Settings },
                onInfo = { Sounds.tap(); overlay = Overlay.Info },
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
                Sounds.tap(); runCatching { context.startActivity(app.launchIntent) }; overlay = Overlay.None
            }, onDismiss = { overlay = Overlay.None })
            WallpapersScreen(
                visible = overlay == Overlay.Wallpapers,
                photos = engine.library,
                current = engine.current?.file,
                thumbs = engine.thumbs,
                interval = if (engine.paused) "paused" else "every ${intervalLabel(config.wallpaper.intervalSec)}",
                onShow = { file -> overlay = Overlay.None; scope.launch { if (!engine.showNow(file)) toast = "Could not open that photo" } },
                onRemove = { files ->
                    val hidden = files.count { !engine.remove(it) }
                    toast = when {
                        hidden > 0 -> "Hidden: the TV would not let the launcher delete ${if (hidden == 1) "it" else "$hidden of them"}"
                        files.size > 1 -> "Deleted ${files.size} photos"
                        else -> null
                    }
                },
                onNewLink = { phone.newLink(); toast = "New code: phones need to scan it again" },
                onDismiss = { overlay = Overlay.None },
            )
            PhotoInfoCard(overlay == Overlay.Info, infoFile, info, infoMap, infoMapLoading) { overlay = Overlay.None }
            ContextMenu(
                overlay == Overlay.Context, contextTile?.label ?: "",
                onMoveLeft = { contextTile?.let { store.move(it.id, -1) } },
                onMoveRight = { contextTile?.let { store.move(it.id, +1) } },
                onHide = { contextTile?.let { store.hide(it.id) } },
                onFirst = { contextTile?.let { store.moveToFront(it.id) } },
                onDismiss = { overlay = Overlay.None },
            )
            SettingsSheet(overlay == Overlay.Settings, settingsRows(store, engine) { overlay = Overlay.Wallpapers }, BuildConfig.VERSION_NAME) { overlay = Overlay.None }
            DropViewer(Drop.incoming, Drop.serial, overlay == Overlay.Drop) { overlay = Overlay.None }
            Toast(toast ?: pending, sticky = toast == null && pending != null, solid = !chrome) { toast = null }
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

private val volumeLevels = listOf(0, 10, 20, 25, 50)
private val awakeMinutes = listOf(0, 60, 180, 360, -1)

private fun intervalLabel(sec: Int) = if (sec < 60) "$sec s" else "${sec / 60} min"

private fun settingsRows(store: ConfigStore, engine: WallpaperEngine, onWallpapers: () -> Unit): List<SettingRow> {
    val intervals = listOf(30, 60, 120, 300, 900)
    val idle = listOf(5, 10, 20, 0)
    return listOf(
        // The photos on the TV, and the QR code for sending one from a phone.
        SettingRow("Wallpapers", listOf("Photos & phone"), { 0 }, { onWallpapers() }),
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
        SettingRow("Sounds", listOf("Glass", "System", "Off"),
            { store.config.sound.mode.ordinal },
            { i -> store.update { c -> c.copy(sound = c.sound.copy(mode = SoundMode.entries[i])) } }),
        SettingRow("Stay awake", listOf("Always", "1 h", "3 h", "6 h", "Fire TV decides"),
            { awakeMinutes.indexOf(store.config.awake.minutes).coerceAtLeast(0) },
            { i -> store.update { c -> c.copy(awake = AwakeConfig(minutes = awakeMinutes[i])) } }),
        SettingRow("Volume pill", listOf("Glass", "Fire TV"),
            { if (store.config.volume.pill) 0 else 1 },
            { i -> store.update { c -> c.copy(volume = c.volume.copy(pill = i == 0)) } }),
        SettingRow("Volume levels", listOf("Fire TV", "10", "20", "25", "50"),
            { volumeLevels.indexOf(store.config.volume.levels).coerceAtLeast(0) },
            { i -> store.update { c -> c.copy(volume = c.volume.copy(levels = volumeLevels[i])) } }),
        SettingRow("Rescan wallpapers", listOf("Now"), { 0 }, { engine.rescan() }),
        SettingRow("Drop to the TV", listOf(Drop.address() ?: "no network"), { 0 }, { }),
    )
}
