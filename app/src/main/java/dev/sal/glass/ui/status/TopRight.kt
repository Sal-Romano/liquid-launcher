package dev.sal.glass.ui.status

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.sal.glass.ui.dock.tileClicks
import dev.sal.glass.ui.glass.GlassVariant
import dev.sal.glass.ui.glass.PillShape
import dev.sal.glass.ui.glass.SquircleShape
import dev.sal.glass.ui.glass.glass

enum class NetKind { None, Wifi, Ethernet, Other }

/** What the TV is connected through, and for Wi-Fi how well (0..4 bars). */
class NetState(val kind: NetKind, val level: Int)

/** Follows the default network: Wi-Fi with its signal level, Ethernet, something else, or nothing. */
@Composable
fun rememberNetworkStatus(): NetState {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf(NetState(NetKind.None, 0)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        fun read(caps: NetworkCapabilities?): NetState = when {
            caps == null -> NetState(NetKind.None, 0)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState(NetKind.Ethernet, 4)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetState(NetKind.Wifi, wifiLevel(context))
            else -> NetState(NetKind.Other, 4)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { state = read(caps) } }
            override fun onLost(network: Network) { main.post { state = NetState(NetKind.None, 0) } }
        }
        state = read(cm?.getNetworkCapabilities(cm.activeNetwork))
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return state
}

@Suppress("DEPRECATION")
private fun wifiLevel(context: Context): Int {
    val wifi = context.getSystemService(WifiManager::class.java) ?: return 2
    val rssi = runCatching { wifi.connectionInfo?.rssi }.getOrNull() ?: return 2
    return WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
}

private val ButtonShape = SquircleShape(13.dp, 0.6f)

/**
 * Top-right corner of the home screen: the network state in a glass pill and a glass settings
 * button. The button is the only focusable thing above the dock, so Up from any tile lands on it
 * and Down returns. Fades with the dock when the screen goes idle.
 */
@Composable
fun TopRight(visible: Boolean, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "topRightAlpha")
    val net = rememberNetworkStatus()
    val liftPx = with(LocalDensity.current) { 2.dp.toPx() }
    Row(modifier.graphicsLayer { this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.height(38.dp).glass(PillShape, GlassVariant.Pill, alpha = alpha).padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) { NetworkGlyph(net, Modifier.size(24.dp)) }
        var focused by remember { mutableStateOf(false) }
        val lift by animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "gearLift")
        Box(
            Modifier
                .size(42.dp)
                .glass(ButtonShape, GlassVariant.Tile, focused = focused, lift = lift, alpha = alpha, liftScale = 0.12f, liftPx = liftPx)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .tileClicks(onSettings, {}),
            contentAlignment = Alignment.Center,
        ) { GearGlyph(Modifier.size(24.dp), spin = lift) }
    }
}

/** Wi-Fi fan with the dim bars for the missing signal, an Ethernet plug, or the fan struck through. */
@Composable
private fun NetworkGlyph(net: NetState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        when (net.kind) {
            NetKind.Ethernet -> ethernet(w)
            NetKind.Other -> wifi(w, 4)
            NetKind.Wifi -> wifi(w, net.level)
            NetKind.None -> {
                wifi(w, 0)
                drawLine(Color.White, Offset(w * 0.18f, w * 0.14f), Offset(w * 0.82f, w * 0.86f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.wifi(w: Float, level: Int) {
    val centre = Offset(w / 2f, w * 0.86f)
    val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round)
    // Three arcs, 84 degrees wide, opening upward; the dot is the first "bar".
    for (i in 1..3) {
        val radius = w * (0.2f + 0.2f * i)
        val lit = level >= i + 1
        drawArc(
            Color.White.copy(alpha = if (lit) 1f else 0.28f), startAngle = 228f, sweepAngle = 84f, useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius), size = Size(radius * 2, radius * 2), style = stroke,
        )
    }
    drawCircle(Color.White.copy(alpha = if (level >= 1) 1f else 0.28f), radius = w * 0.075f, center = Offset(centre.x, centre.y - w * 0.05f))
}

private fun DrawScope.ethernet(w: Float) {
    // An RJ45 plug seen from the front: body, clip tab, pins.
    val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
    val body = Path().apply {
        moveTo(w * 0.2f, w * 0.3f); lineTo(w * 0.8f, w * 0.3f); lineTo(w * 0.8f, w * 0.62f); lineTo(w * 0.68f, w * 0.62f)
        lineTo(w * 0.68f, w * 0.74f); lineTo(w * 0.32f, w * 0.74f); lineTo(w * 0.32f, w * 0.62f); lineTo(w * 0.2f, w * 0.62f); close()
    }
    drawPath(body, Color.White, style = stroke)
    for (i in 0 until 4) {
        val x = w * (0.32f + 0.12f * i)
        drawLine(Color.White, Offset(x, w * 0.36f), Offset(x, w * 0.5f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

/** An eight-tooth gear that turns a little as the button takes focus. */
@Composable
private fun GearGlyph(modifier: Modifier = Modifier, spin: Float = 0f) {
    Canvas(modifier) {
        val w = size.width
        val c = Offset(w / 2f, w / 2f)
        rotate(spin * 22.5f, c) {
            drawCircle(Color.White, radius = w * 0.3f, center = c, style = Stroke(width = w * 0.17f))
            for (i in 0 until 8) {
                rotate(i * 45f, c) {
                    drawRoundRect(
                        Color.White, topLeft = Offset(c.x - w * 0.085f, w * 0.04f), size = Size(w * 0.17f, w * 0.2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
                    )
                }
            }
        }
    }
}
