package dev.sal.glass.wallpaper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
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
 * graphicsLayer transforms change per frame; nothing is re-decoded or allocated.
 */
@Composable
fun WallpaperLayer(engine: WallpaperEngine, kenBurns: Boolean, modifier: Modifier = Modifier) {
    val fade = remember { Animatable(0f) }
    val zoom = remember { Animatable(1f) }
    val current = engine.current
    val next = engine.next

    LaunchedEffect(engine.transitioning) {
        if (engine.transitioning) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(1200, easing = EaseInOut))
            engine.onTransitionDone()
            fade.snapTo(0f)
        }
    }
    LaunchedEffect(current?.file, kenBurns, engine.intervalMs) {
        zoom.snapTo(1f)
        if (kenBurns) zoom.animateTo(1.06f, tween(engine.intervalMs.toInt(), easing = LinearEasing))
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (current == null && engine.empty) {
            EmptyWallpaper()
        }
        current?.let {
            Image(
                it.imageBitmap, null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
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
