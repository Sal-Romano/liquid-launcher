package dev.sal.glass.ui.overlay

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import dev.sal.glass.data.Thumbs
import dev.sal.glass.data.Wallpapers
import dev.sal.glass.phone.Phone
import dev.sal.glass.ui.Sounds
import dev.sal.glass.ui.dock.tileClicks
import dev.sal.glass.ui.glass.GlassLayerId
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.PillShape
import dev.sal.glass.ui.glass.SheetShape
import dev.sal.glass.ui.glass.SquircleShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor

private val Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Title = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
private val Heading = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
private val Body = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp)
private val Label = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 16.sp)
private val Badge = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
private val PhotoShape = RoundedCornerShape(14.dp)
private val QrCardShape = SquircleShape(22.dp, 0.6f)

/**
 * The photos on the TV and the way to send one from a phone. Left, a glass card with the QR code
 * for the phone link and its state; right, every photo, newest first, the one on screen marked.
 * OK on a photo opens its options with Back first, so a stray press does nothing: Show now, Select,
 * Remove. Select (also top right) ticks photos with quick presses and deletes them together, after
 * a confirmation that also starts on Back. Back leaves Select, then closes the screen.
 */
@Composable
fun WallpapersScreen(
    visible: Boolean,
    photos: List<File>,
    current: File?,
    thumbs: Thumbs,
    interval: String,
    onShow: (File) -> Unit,
    onRemove: (List<File>) -> Unit,
    onNewLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val grid = rememberLazyGridState()
    val opening = remember { FocusRequester() }
    val newCode = remember { FocusRequester() }
    val selectButton = remember { FocusRequester() }
    val deleteButton = remember { FocusRequester() }
    val tileFocus = remember { mutableMapOf<File, FocusRequester>() }
    var focusedPhoto by remember { mutableStateOf<File?>(null) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<File>()) }
    var confirming by remember { mutableStateOf(false) }
    var deleteFocused by remember { mutableStateOf(false) }
    // Where focus goes once a menu closes or something leaves the screen.
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    // The photo focus starts on, fixed when the screen opens: the one on screen, else the newest.
    val start = remember(visible) { current?.takeIf { it in photos } ?: photos.firstOrNull() }

    fun endSelecting() {
        // The Delete button goes with Select; if focus was on it, it moves to Select's place.
        if (deleteFocused) refocus = selectButton
        selecting = false
        selected = emptySet()
    }

    /** Takes [files] off the TV; focus moves to the photo that takes the first one's place, or the one before. */
    fun removeAll(files: List<File>) {
        if (files.isEmpty()) return
        val first = photos.indexOfFirst { it in files }
        val neighbour = photos.drop(first).firstOrNull { it !in files } ?: photos.take(first).lastOrNull { it !in files }
        refocus = neighbour?.let { tileFocus[it] } ?: newCode
        files.forEach { tileFocus.remove(it) }
        onRemove(files)
    }

    // A ticked photo that left (removed elsewhere, a rescan) is not ticked any more.
    LaunchedEffect(photos) { if (selected.any { it !in photos }) selected = selected.filter { it in photos }.toSet() }
    LaunchedEffect(visible) {
        if (!visible) { menuFor = null; confirming = false; selecting = false; selected = emptySet(); return@LaunchedEffect }
        val i = photos.indexOf(start)
        if (i >= 0) grid.scrollToItem(i)
        delay(80)
        runCatching { if (start != null) opening.requestFocus() else newCode.requestFocus() }
    }
    LaunchedEffect(menuFor, confirming, refocus) {
        val target = refocus ?: return@LaunchedEffect
        if (menuFor != null || confirming) return@LaunchedEffect
        delay(60)
        runCatching { target.requestFocus() }
        refocus = null
    }

    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(260, easing = Easing)) + slideInVertically(tween(260, easing = Easing)) { it / 14 },
        exit = fadeOut(tween(170)) + slideOutVertically(tween(170)) { it / 14 },
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .dismissOnBack { if (selecting) endSelecting() else onDismiss() },
        ) {
            Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 30.dp, bottom = 28.dp)) {
                PhoneCard(photos.size, interval, onNewLink, Modifier.width(272.dp).fillMaxHeight(), newCode)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        val heading = when {
                            !selecting -> focusedPhoto?.let { caption(context, it, it == current) } ?: ""
                            selected.isEmpty() -> "OK ticks the photos to delete"
                            selected.size == 1 -> "1 photo ticked"
                            else -> "${selected.size} photos ticked"
                        }
                        Text(
                            heading, Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f), style = Label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (photos.isNotEmpty()) {
                            if (selecting) {
                                ScreenButton(
                                    if (selected.isEmpty()) "Delete" else "Delete ${selected.size}",
                                    // Explicit neighbours: left of Delete is nothing, right is Done.
                                    Modifier.focusRequester(deleteButton).focusProperties { right = selectButton },
                                    enabled = selected.isNotEmpty(),
                                    onFocus = { deleteFocused = it },
                                ) { confirming = true }
                                Spacer(Modifier.width(10.dp))
                            }
                            // One button in one place: Select, and Done while selecting.
                            ScreenButton(
                                if (selecting) "Done" else "Select",
                                // Left of Done is Delete, never the New code button across the screen.
                                Modifier.focusRequester(selectButton).focusProperties { if (selecting) left = deleteButton },
                            ) {
                                if (selecting) endSelecting() else selecting = true
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (photos.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No photos yet. Send one from your phone,\nor push them to /sdcard/Pictures/Wallpapers.",
                                color = Color.White.copy(alpha = 0.7f), style = Label, textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            GridCells.Fixed(3),
                            Modifier.fillMaxSize().focusGroup(),
                            state = grid,
                            // Room for the focused photo to grow without being clipped by the grid.
                            contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(photos, key = { it.path }) { file ->
                                val requester = tileFocus.getOrPut(file) { FocusRequester() }
                                PhotoTile(
                                    file, thumbs, current = file == current, selecting = selecting, selected = file in selected,
                                    modifier = Modifier.focusRequester(requester)
                                        .then(if (file == start) Modifier.focusRequester(opening) else Modifier),
                                    onFocus = { focusedPhoto = file },
                                    onClick = {
                                        if (selecting) {
                                            Sounds.tick()
                                            selected = if (file in selected) selected - file else selected + file
                                        } else {
                                            Sounds.tap()
                                            menuFor = file
                                        }
                                    },
                                    onMenu = { if (!selecting) menuFor = file },
                                )
                            }
                        }
                    }
                }
            }
            val menuFile = menuFor
            ActionMenu(
                visible = menuFile != null,
                title = menuFile?.let { caption(context, it, it == current) } ?: "",
                actions = listOf(
                    "Back" to { },
                    "Show now" to { menuFile?.let(onShow); Unit },
                    "Select" to { menuFile?.let { selecting = true; selected = setOf(it) }; Unit },
                    "Remove from the TV" to { menuFile?.let { removeAll(listOf(it)) }; Unit },
                ),
                onDismiss = {
                    if (refocus == null) refocus = menuFor?.let { tileFocus[it] }
                    menuFor = null
                },
            )
            ActionMenu(
                visible = confirming,
                title = if (selected.size == 1) "Delete 1 photo from the TV?" else "Delete ${selected.size} photos from the TV?",
                actions = listOf(
                    "Back" to { },
                    "Delete" to {
                        val files = photos.filter { it in selected }
                        selecting = false
                        selected = emptySet()
                        removeAll(files)
                    },
                ),
                onDismiss = {
                    if (refocus == null) refocus = deleteButton
                    confirming = false
                },
            )
        }
    }
}

/** A pill button for the screen's own controls: quiet at rest, solid white when focused. */
@Composable
private fun ScreenButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(36.dp)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f), PillShape)
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.22f), PillShape)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused); if (it.isFocused) Sounds.tick() }
            .focusable()
            .tileClicks({ if (enabled) { Sounds.tap(); onClick() } }, {})
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> Color(0xFF14161C)
                enabled -> Color.White.copy(alpha = 0.92f)
                else -> Color.White.copy(alpha = 0.4f)
            },
            style = Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
        )
    }
}

/** The QR code for the phone link, what to do with it, the link's state, and a way to make a new code. */
@Composable
private fun PhoneCard(count: Int, interval: String, onNewLink: () -> Unit, modifier: Modifier, newCode: FocusRequester) {
    Column(modifier.glass(SheetShape, GlassVariant.Sheet, layer = GlassLayerId.Overlay).padding(22.dp, 20.dp)) {
        Text("Wallpapers", color = Color.White, style = Title)
        Text(
            if (count == 1) "1 photo  ·  $interval" else "$count photos  ·  $interval",
            Modifier.padding(top = 2.dp), color = Color.White.copy(alpha = 0.65f), style = Body,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        val url = Phone.url
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(168.dp)
                .background(if (url != null) Color.White else Color.White.copy(alpha = 0.08f), QrCardShape)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) QrImage(url, Modifier.fillMaxSize())
            else Text("No relay set", color = Color.White.copy(alpha = 0.6f), style = Body, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Text("From your phone", color = Color.White, style = Heading)
        Text(
            if (url != null) "Scan the code with your phone's camera. The photo shows up here a moment after you send it."
            else "Set phone.server in launcher.json to the relay's address to send photos from a phone.",
            Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.72f), style = Body,
        )
        Spacer(Modifier.weight(1f))
        LinkState()
        Spacer(Modifier.height(8.dp))
        if (url != null) NewCodeButton(onNewLink, newCode)
    }
}

@Composable
private fun LinkState() {
    val (dot, text) = when (Phone.status) {
        Phone.Status.Online -> Color(0xFF5BE38A) to "Ready for photos"
        Phone.Status.Connecting -> Color(0xFFFFC857) to (Phone.problem?.let { "Connecting… ($it)" } ?: "Connecting…")
        Phone.Status.Offline -> Color(0xFFFF6B6B) to "Offline: ${Phone.problem ?: "retrying"}"
        Phone.Status.Off -> Color.White.copy(alpha = 0.35f) to "Phone link off"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White.copy(alpha = 0.8f), style = Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Two presses within four seconds: phones that scanned the old code can no longer send. */
@Composable
private fun NewCodeButton(onNewLink: () -> Unit, requester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    Box(
        Modifier
            .fillMaxWidth().height(36.dp)
            .focusRequester(requester)
            .then(if (focused) Modifier.glass(PillShape, GlassVariant.Pill, focused = true, layer = GlassLayerId.Overlay) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) Sounds.tick() else armed = false }
            .focusable()
            .tileClicks({
                Sounds.tap()
                if (armed) { armed = false; onNewLink() } else armed = true
            }, {})
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            if (armed) "Press OK again to confirm" else "New code",
            color = Color.White.copy(alpha = if (focused) 1f else 0.7f), style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PhotoTile(
    file: File,
    thumbs: Thumbs,
    current: Boolean,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 500f), label = "photoLift")
    val thumb by produceState(thumbs.cached(file), file) { value = thumbs.get(file) }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                val s = 1f + 0.08f * lift
                scaleX = s; scaleY = s
                shadowElevation = 18f * lift
                shape = PhotoShape
                clip = true
            }
            .background(Color.White.copy(alpha = 0.07f))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) { Sounds.tick(); onFocus() } }
            .focusable()
            // A press is the action (options, or a tick while selecting); holding is the same
            // press, so a remote that does not repeat while held behaves the same.
            .tileClicks(onClick, onClick)
            // The remote's Menu button opens the options too.
            .onKeyEvent { e ->
                if (e.key != Key.Menu) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) onMenu()
                true
            },
    ) {
        thumb?.let {
            Image(
                it, null, Modifier.fillMaxSize().graphicsLayer { alpha = if (selecting && !selected) 0.7f else 1f },
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.matchParentSize().border(
                when { selected -> 3.dp; focused -> 2.dp; else -> 1.dp },
                Color.White.copy(alpha = if (selected || focused) 0.95f else 0.16f),
                PhotoShape,
            ),
        )
        if (current) {
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), PillShape).padding(horizontal = 8.dp, vertical = 3.dp),
            ) { Text("Now", color = Color.White, style = Badge) }
        }
        if (selecting) Tick(selected, Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp))
    }
}

/** A round tick: filled white with a dark check when ticked, an empty ring when not. */
@Composable
private fun Tick(on: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        if (on) {
            drawCircle(Color.White, r)
            val check = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.73f, size.height * 0.36f)
            }
            drawPath(check, Color(0xFF14161C), style = Stroke(width = size.width * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(Color.Black.copy(alpha = 0.35f), r)
            drawCircle(Color.White.copy(alpha = 0.9f), r - 1.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** "From a phone · Nov 14, 9:41 AM" for a photo sent from a phone, else the file's name. */
private fun caption(context: android.content.Context, file: File, current: Boolean): String {
    val base = if (Wallpapers.isFromPhone(file)) {
        val stamp = file.nameWithoutExtension.removePrefix(Wallpapers.PHONE_PREFIX).take(15)
        val date = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(stamp) }.getOrNull()
        if (date != null) {
            val day = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), date)
            "From a phone  ·  $day, ${DateFormat.getTimeFormat(context).format(date)}"
        } else "From a phone"
    } else file.nameWithoutExtension
    return if (current) "$base  ·  on screen" else base
}

/** A QR code in near-black modules on whatever is behind it, snapped to whole pixels so it scans clean. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { runCatching { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }.getOrNull() }
    Canvas(modifier) {
        if (qr == null) return@Canvas
        val n = qr.size
        val cell = floor(minOf(size.width, size.height) / n)
        val ox = floor((size.width - cell * n) / 2f)
        val oy = floor((size.height - cell * n) / 2f)
        val path = Path()
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!qr.getModule(x, y)) { x++; continue }
                var end = x
                while (end < n && qr.getModule(end, y)) end++
                path.addRect(Rect(Offset(ox + x * cell, oy + y * cell), Offset(ox + end * cell, oy + (y + 1) * cell)))
                x = end
            }
        }
        drawPath(path, Color(0xFF0B0D12))
    }
}
