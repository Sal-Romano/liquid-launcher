package dev.sal.glass.ui.overlay

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.data.PhotoInfo
import dev.sal.glass.data.PhotoInfos
import dev.sal.glass.data.Wallpapers
import dev.sal.glass.ui.glass.GlassLayerId
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.SheetShape
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.delay
import java.io.File
import java.util.Calendar
import java.util.Locale

private val Title = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
private val Sub = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 15.sp)
private val Line = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
private val MapShape = RoundedCornerShape(13.dp)
private val Pin = Color(0xFFFF6B57)
private val Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * About the photo on screen, like the label beside a picture: a small dark map zoomed out to the
 * neighbouring countries with a pin on the spot, where it is, when it was taken, who sent it and with
 * what. Bottom left, over the photo; the clock and dock step aside while it is up. [info] null: still
 * looking it up. Back closes.
 */
@Composable
fun PhotoInfoCard(visible: Boolean, file: File?, info: PhotoInfo?, map: ImageBitmap?, mapLoading: Boolean, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) { if (visible) { delay(60); runCatching { focus.requestFocus() } } }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(240, easing = Easing)) + slideInVertically(tween(300, easing = Easing)) { it / 10 },
        exit = fadeOut(tween(160)),
    ) {
        val context = LocalContext.current
        Box(Modifier.fillMaxSize().dismissOnBack(onDismiss), contentAlignment = Alignment.BottomStart) {
            Row(
                Modifier.padding(start = 48.dp, bottom = 40.dp).width(500.dp)
                    .glass(SheetShape, GlassVariant.Sheet, layer = GlassLayerId.Overlay)
                    .focusRequester(focus).focusable()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniMap(map, spot = info?.where != null, loading = info == null || mapLoading, Modifier.width(208.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val place = info?.place
                    Text(
                        place?.name ?: file?.let { fallbackTitle(it, info) } ?: "",
                        color = Color.White, style = Title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val where = when {
                        info == null -> "Looking this photo up…"
                        place != null -> listOfNotNull(place.region, place.country).distinct().filter { it != place.name }
                            .joinToString(", ").let { text -> listOf(flag(place.countryCode), text).filter { it.isNotEmpty() }.joinToString("  ") }
                        info.where != null -> "Somewhere at ${coordinates(info.where!!)}"
                        else -> "No location in this photo"
                    }
                    if (where.isNotEmpty()) Text(where, Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.85f), style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    info?.takenAt?.let { taken(context, it) }?.let { Text(it, color = Color.White.copy(alpha = 0.78f), style = Line, maxLines = 1) }
                    info?.from?.let { from ->
                        Text("Sent by $from" + (info.sentAt?.let { " · ${sent(context, it)}" } ?: ""), color = Color.White.copy(alpha = 0.78f), style = Line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    info?.camera?.let { Text(it, color = Color.White.copy(alpha = 0.55f), style = Line, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** The map, a pin pulsing on the spot at its centre; a quiet tile while it loads or when there is no spot. */
@Composable
private fun MiniMap(map: ImageBitmap?, spot: Boolean, loading: Boolean, modifier: Modifier) {
    Box(
        modifier.clip(MapShape).background(Color(0xFF1C2331)).border(1.dp, Color.White.copy(alpha = 0.18f), MapShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            map != null -> {
                Image(map, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                PulsingPin(Modifier.size(30.dp))
            }
            loading -> {
                val t = rememberInfiniteTransition(label = "mapLoading")
                val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mapLoadingAlpha")
                Text("Finding the map…", color = Color.White.copy(alpha = a), style = Line)
            }
            else -> Text(if (spot) "No map right now" else "No location", color = Color.White.copy(alpha = 0.5f), style = Line)
        }
    }
}

@Composable
private fun PulsingPin(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "pin")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pinPulse")
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val max = size.minDimension / 2f
        // A ring that spreads and fades, a soft glow, then the dot with a white rim.
        drawCircle(Pin.copy(alpha = 0.55f * (1f - p)), radius = max * (0.25f + 0.75f * p), center = c, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Pin.copy(alpha = 0.55f), Color.Transparent), center = c, radius = max * 0.6f), radius = max * 0.6f, center = c)
        drawCircle(Color.White, radius = 6.dp.toPx(), center = c)
        drawCircle(Pin, radius = 4.5.dp.toPx(), center = c)
    }
}

/** A title for a photo without a place: who it is from, or its file name in words. */
private fun fallbackTitle(file: File, info: PhotoInfo?): String = when {
    Wallpapers.isFromPhone(file) -> info?.from?.let { "A photo from $it" } ?: "A photo from a phone"
    file.parentFile?.name == "preview" -> info?.from?.let { "A test from $it" } ?: "A test from a phone"
    else -> PhotoInfos.placeQuery(file)?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        ?: file.nameWithoutExtension
}

/** The country's flag from its two-letter code, as regional-indicator emoji. */
private fun flag(code: String?): String {
    val c = code?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } } ?: return ""
    return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

private fun coordinates(where: Pair<Double, Double>): String {
    val (lat, lon) = where
    return String.format(Locale.US, "%.2f° %s, %.2f° %s", kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")
}

/** "Taken 14 July 2024 · 6:22 PM", from EXIF local time. */
private fun taken(context: android.content.Context, takenAt: String): String? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(takenAt) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val cal = Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt()) }
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMMyyyy"), cal)
    return "Taken $date · ${DateFormat.getTimeFormat(context).format(cal.time)}"
}

/** "today, 11:42", "yesterday", or "Nov 14". */
private fun sent(context: android.content.Context, at: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val now = Calendar.getInstance()
    val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && days == 0 -> "today, ${DateFormat.getTimeFormat(context).format(then.time)}"
        sameYear && days == 1 -> "yesterday"
        else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), if (sameYear) "MMMd" else "MMMdyyyy"), then).toString()
    }
}
