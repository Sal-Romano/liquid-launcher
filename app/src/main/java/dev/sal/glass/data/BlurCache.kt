package dev.sal.glass.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pre-rendered blurs, one pair per photo, on disk. The photos rarely change, and the blurs are
 * the expensive part of a wallpaper switch on the TV (~0.6 s of CPU per photo), so they are
 * computed once and kept: 480x270 + 240x135 JPEGs, about 30 KB per photo.
 *
 * Keyed by file name + size + mtime, so a re-pushed photo of the same name is re-rendered and a
 * removed photo's cache files are swept. [warmAll] runs the whole folder once in the background
 * after launch; a photo that is not cached yet is rendered on demand when it comes up.
 */
class BlurCache(context: Context) {
    private val dir = File(context.cacheDir, "blur").apply { mkdirs() }

    private fun key(file: File) = "${file.name}-${file.length()}-${file.lastModified()}"
    private fun deepFile(file: File) = File(dir, key(file) + ".deep.jpg")
    private fun frostFile(file: File) = File(dir, key(file) + ".frost.jpg")

    fun isCached(file: File) = deepFile(file).exists() && frostFile(file).exists()

    /** The blurs for [file], from disk when present, else rendered from [image] and stored. */
    suspend fun blursFor(file: File, image: Bitmap): Pair<Bitmap, Bitmap> = withContext(Dispatchers.IO) {
        val d = deepFile(file); val f = frostFile(file)
        if (d.exists() && f.exists()) {
            val deep = BitmapFactory.decodeFile(d.path); val frost = BitmapFactory.decodeFile(f.path)
            if (deep != null && frost != null) return@withContext deep to frost
        }
        val t0 = SystemClock.elapsedRealtime()
        val deep = Blur.deep(image)
        val frost = Blur.frosted(image)
        store(d, deep); store(f, frost)
        Log.i("Glass", "blurs rendered for ${file.name} in ${SystemClock.elapsedRealtime() - t0} ms")
        deep to frost
    }

    /** Renders and stores the blurs for every photo that lacks them; returns how many were done. */
    suspend fun warmAll(files: List<File>): Int = withContext(Dispatchers.IO) {
        sweep(files)
        var done = 0
        val t0 = SystemClock.elapsedRealtime()
        for (file in files) {
            if (isCached(file)) continue
            val image = Wallpapers.load(file) ?: continue
            blursFor(file, image)
            image.recycle()
            done++
        }
        if (done > 0) Log.i("Glass", "pre-rendered $done photo(s) in ${SystemClock.elapsedRealtime() - t0} ms")
        done
    }

    /** Renders [file]'s blurs now if they are missing: a photo that joined the rotation without going up. */
    suspend fun warm(file: File) = withContext(Dispatchers.IO) {
        if (isCached(file)) return@withContext
        val image = Wallpapers.load(file) ?: return@withContext
        blursFor(file, image)
        image.recycle()
    }

    /** Hands [from]'s blurs to [to], a copy of it under another name (a kept test), so they are not rendered again. */
    fun move(from: File, to: File) {
        deepFile(from).renameTo(deepFile(to))
        frostFile(from).renameTo(frostFile(to))
    }

    /** Drops [file]'s blurs. Call before the file itself goes: the key needs its size and time. */
    fun forget(file: File) {
        deepFile(file).delete()
        frostFile(file).delete()
    }

    private fun store(target: File, bmp: Bitmap) {
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (!tmp.renameTo(target)) tmp.delete()
    }

    /** Drops cache files whose photo is gone or changed. */
    private fun sweep(files: List<File>) {
        val live = files.flatMap { listOf(deepFile(it).name, frostFile(it).name) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in live) it.delete() }
    }
}
