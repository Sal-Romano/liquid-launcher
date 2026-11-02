package dev.sal.glass.ui.glass

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.sal.glass.wallpaper.Frame

/**
 * One GL glass pass. The Home layer sits between the wallpaper and the chrome; the Overlay layer
 * sits above the clock and dock so sheets and popovers occlude them. [current]/[next] are the
 * engine's frames, [mix] the crossfade, [zoom] the Ken Burns scale; [dark] flips the tint for
 * bright photos; [tick] is bumped by the host while something over the glass animates.
 */
@Composable
fun GlassLayer(
    layer: GlassLayerId,
    current: Frame?,
    next: Frame?,
    mix: Float,
    zoom: Float,
    dark: Float,
    tick: Long,
    refraction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val registry = GlassPanels.of(layer)
    val view = remember { GlassTextureView(context, registry) }
    LaunchedEffect(current?.file) { view.setPhotoA(current?.image, current?.deep) }
    LaunchedEffect(next?.file) { view.setPhotoB(next?.image, next?.deep) }
    LaunchedEffect(mix) { view.setMix(mix) }
    LaunchedEffect(zoom) { view.setZoom(zoom) }
    LaunchedEffect(dark) { view.setDark(dark) }
    LaunchedEffect(refraction) { view.setRefraction(refraction) }
    LaunchedEffect(tick) { view.requestRender() }
    DisposableEffect(registry) {
        registry.onChanged = { view.requestRender() }
        onDispose { registry.onChanged = null }
    }
    AndroidView({ view }, modifier.fillMaxSize())
}
