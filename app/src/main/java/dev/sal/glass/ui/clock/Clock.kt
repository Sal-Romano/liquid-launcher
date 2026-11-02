package dev.sal.glass.ui.clock

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.tv.material3.Text
import dev.sal.glass.config.ClockSize
import dev.sal.glass.config.HourFormat
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The time, big, with the date above. Recomposes once a minute on the minute. Its own offscreen
 * layer means the blurred text shadow is rendered once per minute, not once per Ken Burns frame.
 */
@Composable
fun Clock(size: ClockSize, hourFormat: HourFormat, modifier: Modifier = Modifier) {
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
    val date = remember(now) { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)) }
    val digitSp = when (size) { ClockSize.S -> 120; ClockSize.M -> 152; ClockSize.L -> 184 }
    // Shadow radii are in px: 20 dp of blur reads as a soft halo that lifts white digits off a
    // bright sky without a visible drop shadow.
    val px = LocalDensity.current.density
    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f * px), blurRadius = 20f * px)

    Column(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date,
            color = Color.White.copy(alpha = 0.92f),
            style = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 22.sp, shadow = shadow),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            time,
            color = Color.White.copy(alpha = 0.96f),
            style = TextStyle(
                fontFamily = GlassFonts.display,
                fontWeight = FontWeight.Medium,
                fontSize = digitSp.sp,
                lineHeight = digitSp.sp,
                letterSpacing = (-0.02).em,
                fontFeatureSettings = "tnum",
                shadow = shadow,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}
