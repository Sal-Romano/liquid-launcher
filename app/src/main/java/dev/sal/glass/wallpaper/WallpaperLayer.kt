package dev.sal.glass.wallpaper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Full-bleed photo with a slow Ken Burns push-in and a 1.2 s crossfade to the next one. Only
 * graphicsLayer transforms change per frame; nothing is re-decoded or allocated. The photo fading
 * in is drawn unzoomed, and the engine resets the zoom in the same step as it makes that photo
 * the current one, so the end of a crossfade is seamless.
 */
@Composable
fun WallpaperLayer(engine: WallpaperEngine, kenBurns: Boolean, modifier: Modifier = Modifier) {
    val fade = remember { Animatable(0f) }
    val current = engine.current
    val next = engine.next

    LaunchedEffect(engine.transitioning) {
        if (engine.transitioning) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(1200, easing = EaseInOut)) { engine.fade = value }
            // Swaps the photos and puts the fade and the zoom back, all in one step.
            engine.onTransitionDone()
            fade.snapTo(0f)
        }
    }
    // Ken Burns at 4 fps, not 60: a 6 % zoom over a minute moves about a pixel every half second,
    // so stepping every 250 ms looks identical and leaves the GPU idle between steps (the TV would
    // otherwise redraw a full 1080p frame 60 times a second for the whole minute).
    // Keyed on the frame, not its file: a kept test's file changes under a frame that stays up,
    // and the slow zoom must not jump back for it. The zoom lives in the engine, which sets it to 1
    // the moment a new photo takes the screen; this carries on from wherever it is (a changed
    // interval does not jump it back), and a loop still running for the photo that left stops
    // before it can write that photo's zoom over the new one's.
    LaunchedEffect(current, kenBurns, engine.intervalMs) {
        if (current == null) return@LaunchedEffect
        if (!kenBurns) { engine.zoom = 1f; return@LaunchedEffect }
        val total = engine.intervalMs.toFloat()
        val start = System.currentTimeMillis() - ((engine.zoom - 1f) / 0.06f * total).toLong()
        while (engine.current === current) {
            val t = ((System.currentTimeMillis() - start) / total).coerceIn(0f, 1f)
            engine.zoom = 1f + 0.06f * t
            if (t >= 1f) break
            kotlinx.coroutines.delay(250)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (current == null && engine.empty) {
            EmptyWallpaper()
        }
        current?.let {
            Image(
                it.imageBitmap, null,
                // Read while drawing, so the frame that swaps the photos already has the new zoom.
                Modifier.fillMaxSize().graphicsLayer { scaleX = engine.zoom; scaleY = engine.zoom },
                contentScale = ContentScale.Crop,
            )
        }
        if (engine.transitioning && next != null) {
            Image(
                next.imageBitmap, null,
                Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** What shows before any photo is pushed, and the fallback while the first one decodes. */
@Composable
private fun EmptyWallpaper() {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F1B33), Color.Black))),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            "Push photos to /sdcard/Pictures/Wallpapers",
            Modifier.padding(48.dp, 0.dp, 0.dp, 36.dp),
            color = Color.White.copy(alpha = 0.6f),
            style = TextStyle(fontSize = 16.sp),
        )
    }
}
