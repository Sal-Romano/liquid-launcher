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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Shadow
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
import dev.sal.glass.ui.glass.glass
import dev.sal.glass.ui.glass.SquircleShape
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.delay
import java.io.File
import java.util.Calendar
import java.util.Locale

// The card is clear glass now, so its text carries a soft shadow to stay legible over any photo.
private val Lift = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1.5f), 6f)
private val Title = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, shadow = Lift)
private val Sub = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, shadow = Lift)
private val Line = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 12.sp, shadow = Lift)
private val Hint = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 18.sp, shadow = Lift)
private val MapShape = RoundedCornerShape(10.dp)
private val BarShape = SquircleShape(18.dp, 0.6f)
private val Pin = Color(0xFFFF6B57)
private val Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * About the photo on screen, as a caption bar along the bottom: a thumbnail map zoomed out to the
 * neighbouring countries with a pin on the spot; where it is, with the flag; when it was taken, who
 * sent it, the camera. Left and Right go through the photos with it (the hint on the right), and the
 * clock and dock step aside while it is up. [info] null: still looking it up.
 */
@Composable
fun PhotoInfoCard(visible: Boolean, file: File?, info: PhotoInfo?, map: ImageBitmap?, mapLoading: Boolean, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) { if (visible) { delay(60); runCatching { focus.requestFocus() } } }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(240, easing = Easing)) + slideInVertically(tween(300, easing = Easing)) { it / 6 },
        exit = fadeOut(tween(160)),
    ) {
        val context = LocalContext.current
        Box(Modifier.fillMaxSize().dismissOnBack(onDismiss), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(bottom = 30.dp).widthIn(min = 400.dp, max = 680.dp)
                    .glass(BarShape, GlassVariant.Card, layer = GlassLayerId.Overlay)
                    .focusRequester(focus).focusable()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniMap(map, spot = info?.where != null, loading = info == null || mapLoading, Modifier.width(112.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    val place = info?.place
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            place?.name ?: file?.let { fallbackTitle(it, info) } ?: "",
                            Modifier.weight(1f, fill = false), color = Color.White, style = Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val where = when {
                            info == null -> "Looking it up…"
                            place != null -> listOfNotNull(place.region, place.country).distinct().filter { it != place.name }
                                .joinToString(", ").let { text -> listOf(flag(place.countryCode), text).filter { it.isNotEmpty() }.joinToString(" ") }
                            info.where != null -> coordinates(info.where!!)
                            else -> "No location"
                        }
                        if (where.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(where, color = Color.White.copy(alpha = 0.82f), style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val from = info?.from
                    val own = info != null && from == null && file != null && file.parentFile?.name != "preview" && !Wallpapers.isFromPhone(file)
                    val details = listOfNotNull(
                        info?.takenAt?.let { taken(context, it) },
                        from?.let { "Sent by $it" + (info.sentAt?.let { at -> ", ${sent(context, at)}" } ?: "") },
                        if (own) "One of the TV's own photos" else null,
                        info?.camera,
                    )
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString("  ·  "), Modifier.padding(top = 3.dp),
                            color = Color.White.copy(alpha = 0.72f), style = Line, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                // Left and Right go through the photos.
                Text("‹ ›", Modifier.padding(end = 6.dp), color = Color.White.copy(alpha = 0.5f), style = Hint)
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
                PulsingPin(Modifier.size(24.dp))
            }
            loading -> {
                val t = rememberInfiniteTransition(label = "mapLoading")
                val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "mapLoadingAlpha")
                Text("…", color = Color.White.copy(alpha = a), style = Title)
            }
            else -> Text(if (spot) "No map" else "—", color = Color.White.copy(alpha = 0.5f), style = Line)
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

/** "Jul 14, 2024, 6:22 PM", from EXIF local time. */
private fun taken(context: android.content.Context, takenAt: String): String? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(takenAt) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val cal = Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt()) }
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMyyyy"), cal)
    return "$date, ${DateFormat.getTimeFormat(context).format(cal.time)}"
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
