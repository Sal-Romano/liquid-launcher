package dev.sal.glass.ui.glass

import android.graphics.Bitmap
import androidx.compose.runtime.compositionLocalOf

/**
 * What every glass panel samples behind itself: the frosted (pre-blurred, 240x135) copies of the
 * wallpaper that is showing and the one fading in, plus how far the crossfade is. The wallpaper
 * engine publishes a new one per transition; the panels never blur anything themselves.
 */
data class GlassBackdrop(
    val current: Bitmap? = null,
    val next: Bitmap? = null,
    /** 0 = only [current] is visible, 1 = only [next]. */
    val progress: Float = 0f,
)

val LocalGlassBackdrop = compositionLocalOf { GlassBackdrop() }
