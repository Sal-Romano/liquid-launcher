package dev.sal.glass.ui.dock

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import dev.sal.glass.R

/**
 * App icons rasterised once at 112x112 px (56 dp on the TV) and cached. Asks for the xxhdpi
 * asset so icons drawn for phones stay crisp at this size.
 */
object AppIcons {
    private const val SIZE = 112
    private val cache = LruCache<String, ImageBitmap>(48)

    fun forPackage(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        val pm = context.packageManager
        val drawable = runCatching {
            val ai = pm.getApplicationInfo(packageName, 0)
            val res = pm.getResourcesForApplication(ai)
            if (ai.icon != 0) res.getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXHIGH, null) else null
                ?: ai.loadIcon(pm)
        }.getOrNull() ?: return null
        return rasterise(drawable).also { cache.put(packageName, it) }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        try { context.packageManager.getPackageInfo(packageName, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    /** First letter on a soft dark disc, for apps with no usable icon and for placeholders. */
    fun monogram(context: Context, letter: Char): ImageBitmap {
        val key = "monogram:$letter"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 20, 24, 36) })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            textSize = SIZE * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        }
        val y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(letter.uppercaseChar().toString(), SIZE / 2f, y, paint)
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    private fun rasterise(d: Drawable): ImageBitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(Canvas(bmp))
        return bmp.asImageBitmap()
    }
}

/** Simple vector glyphs for launcher actions, drawn once at icon size. */
fun AppIcons.glyph(context: Context, action: dev.sal.glass.config.Action): ImageBitmap {
    val key = "glyph:$action"
    AppIconsCache.get(key)?.let { return it }
    val size = 112
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255); style = Paint.Style.FILL }
    when (action) {
        dev.sal.glass.config.Action.AllApps -> {
            // 3x3 grid of rounded dots: the universal "apps" mark.
            val cell = size / 4f; val r = size * 0.075f
            for (i in 1..3) for (j in 1..3) c.drawCircle(cell * i, cell * j, r, paint)
        }
        dev.sal.glass.config.Action.Settings -> {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = size * 0.09f
            c.drawCircle(size / 2f, size / 2f, size * 0.26f, paint)
            c.drawCircle(size / 2f, size / 2f, size * 0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color })
        }
        dev.sal.glass.config.Action.NextWallpaper -> {
            val path = android.graphics.Path().apply {
                moveTo(size * 0.3f, size * 0.25f); lineTo(size * 0.72f, size * 0.5f); lineTo(size * 0.3f, size * 0.75f); close()
            }
            c.drawPath(path, paint)
        }
        dev.sal.glass.config.Action.PauseSlideshow -> {
            val w = size * 0.14f
            c.drawRoundRect(size * 0.3f, size * 0.26f, size * 0.3f + w, size * 0.74f, w / 3, w / 3, paint)
            c.drawRoundRect(size * 0.56f, size * 0.26f, size * 0.56f + w, size * 0.74f, w / 3, w / 3, paint)
        }
    }
    return bmp.asImageBitmap().also { AppIconsCache.put(key, it) }
}

/** Shared cache so glyphs and icons live in one LRU. */
internal object AppIconsCache {
    private val cache = LruCache<String, ImageBitmap>(16)
    fun get(k: String): ImageBitmap? = cache.get(k)
    fun put(k: String, v: ImageBitmap) { cache.put(k, v) }
}
