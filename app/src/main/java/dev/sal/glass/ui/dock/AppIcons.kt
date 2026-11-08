package dev.sal.glass.ui.dock

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import dev.sal.glass.R
import dev.sal.glass.config.ActionTile
import dev.sal.glass.config.AppTile
import dev.sal.glass.config.FolderTile
import dev.sal.glass.config.Tile

/**
 * App icons rasterised once at 112x112 px (56 dp on the TV) and cached. Asks for the xxhdpi
 * asset so icons drawn for phones stay crisp at this size. Also the bundled brand marks (for the
 * apps that matter here before they are installed), folder icons and letter monograms.
 */
object AppIcons {
    private const val SIZE = 112
    private val cache = LruCache<String, ImageBitmap>(64)

    /** The icon a tile shows: the override, the installed app's icon, a known brand, or a monogram. */
    fun tileIcon(context: Context, tile: Tile): ImageBitmap = when (tile) {
        is AppTile -> tile.icon?.let { brand(context, it) }
            ?: (if (isInstalled(context, tile.packageName)) forPackage(context, tile.packageName) else null)
            ?: BRAND_BY_PACKAGE[tile.packageName]?.let { brand(context, it) }
            ?: monogram(context, (tile.label ?: tile.id).firstOrNull() ?: '?')
        is ActionTile -> tile.icon?.let { brand(context, it) } ?: glyph(context, tile.action)
        is FolderTile -> tile.icon?.let { brand(context, it) ?: forPackage(context, it) }
            ?: folder(context, tile.id, tile.children.map { tileIcon(context, it) })
        else -> monogram(context, (tile.label ?: tile.id).firstOrNull() ?: '?')
    }

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

    private val installed = HashMap<String, Boolean>()
    /** Bumped whenever the cache is dropped, so a composition that asked [isInstalled] recomposes. */
    private var installedGeneration by mutableIntStateOf(0)

    /** Cached: a package-manager round trip per tile per recomposition is real time on the TV. */
    fun isInstalled(context: Context, packageName: String): Boolean {
        installedGeneration // read in composition: subscribes the caller to forgetInstalled()
        return synchronized(installed) {
            installed.getOrPut(packageName) {
                try { context.packageManager.getPackageInfo(packageName, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
            }
        }
    }

    /** Called when the launcher comes back to the front or a package changes: an app may have been installed meanwhile. */
    fun forgetInstalled() {
        synchronized(installed) { installed.clear() }
        installedGeneration++
    }

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

    /** [art]: a finished icon (a bitmap), drawn as it is with no plate and no tint. */
    private class Brand(val res: Int, val plate: Int = 0, val glyph: Int = Color.WHITE, val inset: Float = 0.22f, val art: Boolean = false)

    /** Bundled marks on their brand colour, drawn like app icons so they sit with the real ones. */
    private val BRANDS = mapOf(
        "plex" to Brand(R.drawable.brand_plex, plate = 0xFF1F2326.toInt(), glyph = 0xFFE5A00D.toInt()),
        "playstation" to Brand(R.drawable.brand_playstation, plate = 0xFF0070D1.toInt(), glyph = Color.WHITE),
        "xbox" to Brand(R.drawable.brand_xbox, plate = 0xFF107C10.toInt(), glyph = Color.WHITE, inset = 0.2f),
        "wii" to Brand(R.drawable.brand_wii, plate = 0xFFF3F3F3.toInt(), glyph = 0xFF737373.toInt(), inset = 0.18f),
        "spotify" to Brand(R.drawable.brand_spotify, plate = 0xFF1DB954.toInt(), glyph = Color.WHITE, inset = 0.1f),
        "youtube" to Brand(R.drawable.brand_youtube, plate = 0xFFFF0000.toInt(), glyph = Color.WHITE, inset = 0.33f),
        "claude" to Brand(R.drawable.brand_claude, art = true),
    )

    /** Apps whose icon we can draw before they are installed. */
    private val BRAND_BY_PACKAGE = mapOf(
        "com.plexapp.android" to "plex",
        "org.dolphinemu.dolphinemu" to "wii",
        "com.playstation.remoteplay" to "playstation",
        "com.microsoft.xboxone.smartglass" to "xbox",
        "com.spotify.tv.android" to "spotify",
        "com.spotify.tv.android.second" to "spotify",
        "com.amazon.firetv.youtube" to "youtube",
    )

    fun brand(context: Context, key: String): ImageBitmap? {
        val b = BRANDS[key] ?: return null
        val cacheKey = "brand:$key"
        cache.get(cacheKey)?.let { return it }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = context.getDrawable(b.res) ?: return null
        if (b.art) {
            d.setBounds(0, 0, SIZE, SIZE); d.draw(c)
            return bmp.asImageBitmap().also { cache.put(cacheKey, it) }
        }
        val r = SIZE * 0.22f
        c.drawRoundRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = b.plate })
        d.setTint(b.glyph)
        val inset = (SIZE * b.inset).toInt()
        d.setBounds(inset, inset, SIZE - inset, SIZE - inset)
        d.draw(c)
        return bmp.asImageBitmap().also { cache.put(cacheKey, it) }
    }

    /** A folder shows its first four children small, two by two, like an iOS folder. */
    fun folder(context: Context, id: String, children: List<ImageBitmap>): ImageBitmap {
        val key = "folder:$id:${children.size}"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cell = 46; val gap = 8; val total = cell * 2 + gap
        val x0 = (SIZE - total) / 2; val y0 = (SIZE - total) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        children.take(4).forEachIndexed { i, img ->
            val x = x0 + (i % 2) * (cell + gap); val y = y0 + (i / 2) * (cell + gap)
            c.drawBitmap(img.asAndroidBitmap(), null, Rect(x, y, x + cell, y + cell), paint)
        }
        if (children.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
            for (i in 0 until 4) { val x = x0 + (i % 2) * (cell + gap); val y = y0 + (i / 2) * (cell + gap); c.drawRoundRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), 10f, 10f, p) }
        }
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
        dev.sal.glass.config.Action.None -> Unit // a picture tile without a picture: blank
    }
    return bmp.asImageBitmap().also { AppIconsCache.put(key, it) }
}

/** Shared cache so glyphs and icons live in one LRU. */
internal object AppIconsCache {
    private val cache = LruCache<String, ImageBitmap>(16)
    fun get(k: String): ImageBitmap? = cache.get(k)
    fun put(k: String, v: ImageBitmap) { cache.put(k, v) }
}
