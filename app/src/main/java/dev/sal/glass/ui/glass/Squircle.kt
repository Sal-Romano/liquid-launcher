package dev.sal.glass.ui.glass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * iOS-style continuous-corner rectangle. The radius is in dp and the corner smoothing is what
 * makes it read as a squircle rather than a rounded rect. The path is cached per size because
 * the dock rebuilds outlines while tiles scale on focus.
 */
class SquircleShape(private val radius: Dp = 20.dp, private val smoothing: Float = 0.6f) : Shape {
    val radiusDp: Dp get() = radius
    private var cachedSize: Size? = null
    private var cachedDensity = 0f
    private var cachedPath: Path? = null

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = pathFor(size, density)
        return Outline.Generic(path)
    }

    fun pathFor(size: Size, density: Density): Path {
        val cached = cachedPath
        if (cached != null && cachedSize == size && cachedDensity == density.density) return cached
        val r = with(density) { radius.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        // Four explicit corners: graphics-shapes 1.1.0 has no rectangle() helper.
        val w = size.width; val h = size.height
        val polygon = RoundedPolygon(
            vertices = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h),
            rounding = CornerRounding(radius = r, smoothing = smoothing),
            centerX = w / 2f, centerY = h / 2f,
        )
        val path = polygon.toPath().asComposePath()
        cachedSize = size; cachedDensity = density.density; cachedPath = path
        return path
    }
}

val TileShape = SquircleShape(20.dp, 0.6f)
val PillShape = SquircleShape(14.dp, 0.6f)
val SheetShape = SquircleShape(28.dp, 0.6f)
