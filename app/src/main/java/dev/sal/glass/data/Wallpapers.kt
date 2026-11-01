package dev.sal.glass.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photos in /sdcard/Pictures/Wallpapers, read straight from the filesystem.
 *
 * Not MediaStore: files pushed over adb sit in MediaStore as is_pending=1 forever on this TV (the
 * scanner never finalises them), and Android 11 hides pending rows from other apps. The directory
 * itself is readable with READ_EXTERNAL_STORAGE, which is granted over adb. Verified 2026-11-01.
 *
 * Budget: the TV's app heap is 192 MB and its UI runs at 1920x1080, so every photo is decoded to
 * about 1920 wide (8 MB ARGB) and at most two are alive at once (current + next, for the crossfade).
 */
object Wallpapers {
    private const val TARGET_WIDTH = 1920
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    val folder: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Wallpapers")

    /** Every image file in the folder, by name. Empty if the folder is missing or unreadable. */
    fun list(): List<File> =
        folder.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Decodes [file] subsampled to roughly [TARGET_WIDTH] px wide. */
    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, TARGET_WIDTH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.path, opts)
    }

    /** Largest power of two that keeps the decoded width at or above [target]. */
    private fun sampleSize(width: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target) sample *= 2
        return sample
    }
}
