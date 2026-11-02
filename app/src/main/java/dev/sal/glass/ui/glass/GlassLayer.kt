package dev.sal.glass.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The GL glass, placed between the wallpaper and the Compose chrome. [sharp] and [frost] are the
 * current wallpaper and its blurred copy; [dark] flips the tint for bright photos; [tick] is bumped
 * by the host while something over the glass animates, so the backdrop under overlays stays fresh.
 */
@Composable
fun GlassLayer(sharp: Bitmap?, frost: Bitmap?, dark: Float, tick: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { GlassTextureView(context) }
    LaunchedEffect(sharp, frost) { view.setWallpaper(sharp, frost) }
    LaunchedEffect(dark) { view.setDark(dark) }
    LaunchedEffect(tick) { view.requestRender() }
    DisposableEffect(Unit) {
        GlassPanels.onChanged = { view.requestRender() }
        onDispose { GlassPanels.onChanged = null }
    }
    AndroidView({ view }, modifier.fillMaxSize())
}
