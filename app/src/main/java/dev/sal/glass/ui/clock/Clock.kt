package dev.sal.glass.ui.clock

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.sal.glass.config.ClockSize
import dev.sal.glass.config.HourFormat
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The time with the short date under it, for the top-left corner. Recomposes once a minute on the
 * minute. [glow] (0..1) is how bright the photo is behind the clock: a bright corner (sky, snow)
 * gets a darker halo. [alpha] fades the clock with the home chrome, both lines together.
 */
@Composable
fun Clock(
    size: ClockSize,
    hourFormat: HourFormat,
    glow: () -> Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            now = t
            delay(60_000 - t % 60_000)
        }
    }
    val is24 = when (hourFormat) {
        HourFormat.System -> DateFormat.is24HourFormat(context)
        HourFormat.H12 -> false
        HourFormat.H24 -> true
    }
    val time = remember(now, is24) { SimpleDateFormat(if (is24) "H:mm" else "h:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)) }
    val digitSp = when (size) { ClockSize.S -> 58; ClockSize.M -> 72; ClockSize.L -> 92 }
    val dateSp = when (size) { ClockSize.S -> 17; ClockSize.M -> 20; ClockSize.L -> 24 }
    val timeStyle = remember(digitSp) {
        TextStyle(
            fontFamily = GlassFonts.display,
            fontWeight = FontWeight.Medium,
            fontSize = digitSp.sp,
            lineHeight = digitSp.sp,
            letterSpacing = (-0.02).em,
            fontFeatureSettings = "tnum",
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        )
    }
    val dateStyle = remember(dateSp) { TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = dateSp.sp) }

    Column(modifier, horizontalAlignment = Alignment.Start) {
        GlowText(time, timeStyle, Color.White.copy(alpha = 0.97f), blur = 15.dp, strength = { 0.42f + 0.40f * glow() }, alpha = alpha)
        Spacer(Modifier.height(4.dp))
        GlowText(date, dateStyle, Color.White.copy(alpha = 0.95f), blur = 8.dp, strength = { 0.50f + 0.40f * glow() }, alpha = alpha)
    }
}

/**
 * One line of the clock: the text with a soft dark halo, both drawn here. A Text's own shadow is
 * cut off at the edge of the text's box on this TV, in straight lines that read as a dark box
 * round the clock, with or without a layer around it. So the halo is the text's shape blurred
 * into a bitmap once a minute (a few ms on the CPU) and drawn under the text, reaching as far as
 * the blur does. [strength] is the halo's opacity; [alpha] fades the line.
 */
@Composable
private fun GlowText(
    text: String,
    style: TextStyle,
    color: Color,
    blur: Dp,
    strength: () -> Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val density = LocalDensity.current
    val layout = remember(text, style, density) { measurer.measure(text, style, softWrap = false, maxLines = 1) }
    val blurPx = with(density) { blur.toPx() }
    val drop = with(density) { 1.5.dp.toPx() }
    val halo = remember(layout, blurPx) { haloOf(layout, blurPx) }
    Spacer(
        modifier
            .size(with(density) { layout.size.width.toDp() }, with(density) { layout.size.height.toDp() })
            .drawBehind {
                val a = alpha().coerceIn(0f, 1f)
                if (a <= 0f) return@drawBehind
                drawImage(halo.image, topLeft = Offset(-halo.margin, -halo.margin + drop), alpha = (strength() * a).coerceIn(0f, 1f))
                drawText(layout, color = color, alpha = a)
            },
    )
}

private class Halo(val image: ImageBitmap, val margin: Float)

/**
 * [layout]'s text as a soft black shape: drawn black into a bitmap with room round it, then three
 * box blurs each way, which comes close to a Gaussian with the spread a text shadow of [blurPx]
 * would have (Android takes a shadow radius r as sigma 0.577 r + 0.5).
 */
private fun haloOf(layout: TextLayoutResult, blurPx: Float): Halo {
    val sigma = 0.57735f * blurPx + 0.5f
    val margin = ceil(3f * sigma).toInt()
    val w = layout.size.width + 2 * margin
    val h = layout.size.height + 2 * margin
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()), Size(w.toFloat(), h.toFloat())) {
        drawText(layout, color = Color.Black, topLeft = Offset(margin.toFloat(), margin.toFloat()))
    }
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val a = IntArray(w * h) { px[it] ushr 24 }
    val tmp = IntArray(w * h)
    // Box radius for three passes approximating sigma.
    val r = ((sqrt(12f * sigma * sigma / 3f + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)
    repeat(3) {
        boxBlur(a, tmp, w, h, r, horizontal = true)
        boxBlur(a, tmp, w, h, r, horizontal = false)
    }
    for (i in px.indices) px[i] = a[i].coerceIn(0, 255) shl 24
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
    return Halo(bitmap.asImageBitmap(), margin.toFloat())
}

/** One box blur pass of radius [r] over [a] (w x h), along rows or columns; outside is empty. */
private fun boxBlur(a: IntArray, tmp: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val div = 2 * r + 1
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val step = if (horizontal) 1 else w
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        var sum = 0
        for (i in 0..minOf(r, len - 1)) sum += a[base + i * step]
        for (i in 0 until len) {
            tmp[base + i * step] = sum / div
            val add = i + r + 1
            val sub = i - r
            if (add < len) sum += a[base + add * step]
            if (sub >= 0) sum -= a[base + sub * step]
        }
    }
    System.arraycopy(tmp, 0, a, 0, a.size)
}
