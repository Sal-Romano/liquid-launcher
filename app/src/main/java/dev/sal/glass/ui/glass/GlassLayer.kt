package dev.sal.glass.ui.glass

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.sal.glass.wallpaper.Frame

/**
 * One GL glass pass. The Home layer sits between the wallpaper and the chrome; the Overlay layer
 * sits above the clock and dock so sheets and popovers occlude them. [current]/[next] are the
 * engine's frames, [mix] the crossfade, [zoom] the Ken Burns scale; [dark] flips the tint for
 * bright photos. Extra frames while something over the glass animates are requested through
 * [GlassPanels.requestRenderAll], not through Compose state.
 */
@Composable
fun GlassLayer(
    layer: GlassLayerId,
    current: Frame?,
    next: Frame?,
    mix: Float,
    zoom: Float,
    dark: Float,
    refraction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val registry = GlassPanels.of(layer)
    val view = remember { GlassTextureView(context, registry) }
    // The photos, the zoom and the crossfade reach the renderer together, as one change it never
    // draws half of: at the end of a fade the new photo moves from B to A in the same instant as
    // the mix drops to 0 and the zoom to 1. Photos are compared as frames, not files: a kept
    // test's file changes under a frame that stays up.
    val sent = remember { SentPhotos() }
    SideEffect {
        view.scene {
            if (!sent.any || sent.a !== current) view.setPhotoA(current?.image, current?.deep, current?.light)
            if (!sent.any || sent.b !== next) view.setPhotoB(next?.image, next?.deep, next?.light)
            view.setZoom(zoom)
            view.setMix(mix)
        }
        sent.a = current
        sent.b = next
        sent.any = true
    }
    LaunchedEffect(dark) { view.setDark(dark) }
    LaunchedEffect(refraction) { view.setRefraction(refraction) }
    DisposableEffect(registry) {
        registry.onChanged = { view.requestRender() }
        onDispose { registry.onChanged = null }
    }
    AndroidView({ view }, modifier.fillMaxSize())
}

/** The frames last handed to the renderer, so a photo is uploaded only when it changes. */
private class SentPhotos {
    var a: Frame? = null
    var b: Frame? = null
    var any = false
}
