package dev.sal.glass.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
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
 * Photos sent from a phone are written to the same folder, so it stays the one library, visible
 * over adb. If the launcher may not write there, they go to its own folder on the shared storage
 * ([own], /sdcard/Android/data/dev.sal.glass/files/Pictures/Wallpapers), which is listed too.
 * Removing a photo deletes it where the launcher may (its own photos always; adb's with All files
 * access, granted over adb, see the README) and otherwise hides it by name and size.
 *
 * Budget: the TV's app heap is 192 MB and its UI runs at 1920x1080, so every photo is decoded to
 * about 1920 wide (8 MB ARGB) and at most two are alive at once (current + next, for the crossfade).
 */
object Wallpapers {
    private const val TARGET_WIDTH = 1920
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    private var own: File? = null
    private var hiddenFile: File? = null
    private var hidden = emptySet<String>()

    val folder: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Wallpapers")

    /** Once, before anything is listed: where the launcher's own folder and its hidden list are. */
    fun init(context: Context) {
        own = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "Wallpapers") }
        hiddenFile = File(context.filesDir, "wallpapers-hidden.txt").also { f ->
            hidden = runCatching { f.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        }
    }

    /** Every image file in the folders, by name, less the hidden ones. Empty if nothing is readable. */
    fun list(): List<File> =
        listOfNotNull(folder, own)
            .flatMap { dir ->
                // Dot files are photos still being written (see [save]).
                dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in EXTENSIONS }?.toList() ?: emptyList()
            }
            .filter { hiddenKey(it) !in hidden }
            .sortedBy { it.name.lowercase() }

    /** A photo that came from a phone, by the upload id in its name (so a re-sent offer is not fetched twice). */
    fun fromPhone(id: String): File? = list().firstOrNull { it.nameWithoutExtension.endsWith("-$id") }

    /** The photos saved from one photo chosen on a phone (one draft): earlier framings of it. */
    fun ofDraft(draft: String): List<File> = list().filter { isFromPhone(it) && it.nameWithoutExtension.contains("-$draft-") }

    /** True for a photo the launcher saved from a phone. */
    fun isFromPhone(file: File): Boolean = file.name.startsWith(PHONE_PREFIX)

    /**
     * Copies [source] (a downloaded photo in the cache) in as a new photo named [name] ("phone-
     * <time>-<id>.jpg"): into the shared folder, or the launcher's own when the shared one refuses.
     * The photo appears complete or not at all: written under a hidden temporary name, then renamed.
     * The temporary name keeps the image extension: Android 11 refuses (EPERM) any other kind of
     * file in Pictures/, which is what a ".part" got on the emulator.
     */
    fun save(name: String, source: File): File {
        var failure: Exception? = null
        for (dir in listOfNotNull(folder, own)) {
            val target = File(dir, name)
            val tmp = File(dir, ".writing-$name")
            try {
                if (!dir.isDirectory && !dir.mkdirs()) error("cannot create $dir")
                source.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                if (!tmp.renameTo(target)) error("cannot rename into $dir")
                return target
            } catch (e: Exception) {
                Log.w("Glass", "phone photo: cannot write to $dir (${e.message})")
                tmp.delete()
                failure = e
            }
        }
        throw failure ?: IllegalStateException("no folder to write to")
    }

    /** Takes [file] out of the rotation for good: deleted if the launcher may, else hidden. True if deleted. */
    fun remove(file: File): Boolean {
        if (file.delete() || !file.exists()) return true
        hidden = hidden + hiddenKey(file)
        runCatching { hiddenFile?.writeText(hidden.joinToString("\n")) }
        Log.i("Glass", "${file.name} cannot be deleted by the launcher; hidden instead")
        return false
    }

    private fun hiddenKey(file: File) = "${file.name}|${file.length()}"

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

    const val PHONE_PREFIX = "phone-"
}
