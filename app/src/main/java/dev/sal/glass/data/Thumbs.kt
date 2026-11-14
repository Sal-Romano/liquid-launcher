package dev.sal.glass.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small sharp copies of the photos for the Wallpapers screen: 384x216, cropped to the screen's
 * shape the way the wallpaper itself is drawn. Rendered on first sight (a subsampled decode, tens
 * of milliseconds on the TV), kept on disk as ~15 KB JPEGs and in memory as RGB 565 (166 KB each,
 * 48 of them at most). Keyed like [BlurCache], by name, size and time.
 */
class Thumbs(context: Context) {
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = LruCache<String, ImageBitmap>(48)
    /** Two at a time: the grid asks for a screenful at once and the TV has four slow cores. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)

    private fun key(file: File) = "${file.name}-${file.length()}-${file.lastModified()}"

    /** The thumb if it is in memory, for the first frame of a grid cell. */
    fun cached(file: File): ImageBitmap? = memory.get(key(file))

    suspend fun get(file: File): ImageBitmap? {
        val k = key(file)
        memory.get(k)?.let { return it }
        return withContext(io) {
            val disk = File(dir, "$k.jpg")
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmp = (if (disk.exists()) BitmapFactory.decodeFile(disk.path, opts) else null)
                ?: render(file)?.also { store(disk, it) }
            bmp?.asImageBitmap()?.also { memory.put(k, it) }
        }
    }

    /** Drops [file]'s thumb. Call before the file itself goes: the key needs its size and time. */
    fun forget(file: File) {
        val k = key(file)
        memory.remove(k)
        File(dir, "$k.jpg").delete()
    }

    /** Deletes thumbs whose photo is gone or changed. */
    fun sweep(files: Collection<File>) {
        val live = files.map { key(it) + ".jpg" }.toSet()
        dir.listFiles()?.forEach { if (it.name !in live) it.delete() }
    }

    private fun render(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((W - src.width * scale) / 2f, (H - src.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        out
    }.getOrNull()

    private fun store(target: File, bmp: Bitmap) {
        val tmp = File(target.path + ".tmp")
        runCatching {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    private companion object {
        const val W = 384
        const val H = 216
    }
}
