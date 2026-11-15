package dev.sal.glass.ui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.media.NowPlaying
import dev.sal.glass.ui.Sounds
import dev.sal.glass.ui.dock.AppIcons
import dev.sal.glass.ui.dock.tileClicks
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.SquircleShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts

/**
 * The song that is playing, at the top of the screen in the middle: its art, title and artist, and
 * the app that plays it ("Spotify · Kyle", from [source]). It comes down into place when something
 * starts playing, stays while it plays and for a few minutes after it pauses (marked Paused), and
 * stays up while the dock rests, like the clock. Its glass is the dock tiles' clear lens, refracting
 * the photo on every background (the Card glass mixes in a deep blur and read as frosted). OK opens
 * the app that plays it; Down goes back to the dock. [visible] false (an overlay is up) fades it with
 * the clock; [canFocus] false keeps the remote off it while a sheet is up.
 */
@Composable
fun NowPlayingCard(
    track: NowPlaying.Track?,
    visible: Boolean,
    source: (String) -> String,
    onOpen: (NowPlaying.Track) -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    canFocus: Boolean = true,
) {
    val context = LocalContext.current
    // On its way out the card keeps showing what it last showed.
    val last = remember { mutableStateOf<NowPlaying.Track?>(null) }
    SideEffect { if (track != null) last.value = track }
    val t = track ?: last.value
    val appear = animateFloatAsState(
        if (track != null && visible) 1f else 0f, tween(if (track != null && visible) 320 else 200), label = "nowPlaying",
    )
    val present by remember { derivedStateOf { appear.value > 0.005f } }
    if (!present || t == null) return

    var focused by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "nowPlayingLift")
    val liftPx = with(LocalDensity.current) { 3.dp.toPx() }
    val risePx = with(LocalDensity.current) { 10.dp.toPx() }
    // Fades in and comes down from the top edge; the glass follows through the alpha it is given (it
    // is painted by the GL layer, not inside this layer).
    Box(modifier.graphicsLayer { alpha = appear.value; translationY = -risePx * (1f - appear.value) }) {
        Row(
            Modifier
                .widthIn(min = 240.dp, max = 420.dp)
                .glass(
                    CardShape, GlassVariant.Tile, focused = focused, lift = { lift.value }, alpha = { appear.value },
                    liftScale = 0.06f, liftPx = liftPx,
                )
                .onFocusChanged {
                    if (it.isFocused && !focused) Sounds.tick()
                    focused = it.isFocused
                    onFocus(it.isFocused)
                }
                .focusProperties { this.canFocus = visible && canFocus }
                .focusable()
                .tileClicks({ onOpen(t) }, {})
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val art = remember(t.art) { t.art?.asImageBitmap() }
            val icon = remember(t.packageName, art == null) { if (art == null) AppIcons.forPackage(context, t.packageName) else null }
            Box(Modifier.size(64.dp).clip(ArtShape), contentAlignment = Alignment.Center) {
                when {
                    art != null -> Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    icon != null -> Image(icon, null, Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(t.title, color = Color.White, style = Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.artist.isNotEmpty()) {
                    Text(t.artist, color = Color.White.copy(alpha = 0.85f), style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayingGlyph(t.playing, Modifier.size(11.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (t.playing) source(t.packageName) else "Paused · ${source(t.packageName)}",
                        color = Color.White.copy(alpha = 0.72f), style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Three bars of a level meter while it plays (still, so the home screen never redraws for it); two for paused. */
@Composable
private fun PlayingGlyph(playing: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = Color.White.copy(alpha = 0.8f)
        if (playing) {
            val bar = w * 0.22f
            val gap = w * 0.17f
            listOf(0.55f, 1f, 0.75f).forEachIndexed { i, f ->
                drawRoundRect(c, Offset(i * (bar + gap), h * (1f - f)), Size(bar, h * f), CornerRadius(bar / 2f))
            }
        } else {
            val bar = w * 0.28f
            drawRoundRect(c, Offset(w * 0.12f, 0f), Size(bar, h), CornerRadius(bar / 3f))
            drawRoundRect(c, Offset(w * 0.6f, 0f), Size(bar, h), CornerRadius(bar / 3f))
        }
    }
}

// The dock labels' shadow: through clear glass a bright sky sits right behind the text.
private val Lift = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f)
private val Title = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, shadow = Lift)
private val Sub = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 14.sp, shadow = Lift)
private val Small = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 12.sp, shadow = Lift)
private val CardShape = SquircleShape(18.dp, 0.6f)
private val ArtShape = RoundedCornerShape(10.dp)
