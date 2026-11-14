package dev.sal.glass.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sal.glass.data.BlurCache
import dev.sal.glass.data.PhotoInfos
import dev.sal.glass.data.Thumbs
import dev.sal.glass.data.Wallpapers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One decoded wallpaper, its blurred copies for the glass, and where its light comes from. [file]
 * changes once in a frame's life at most: a test from a phone that is kept becomes the saved copy
 * while it stays on screen. The layers key on the frame itself, not the file, so that is invisible.
 */
class Frame(var file: File, val image: Bitmap, val thumb: Bitmap, val deep: Bitmap, val light: SceneLight = SceneLight.DEFAULT) {
    val imageBitmap: ImageBitmap = image.asImageBitmap()
}

/**
 * Rotates the photos in Pictures/Wallpapers. Memory budget on the TV's 192 MB heap: at most two
 * decoded 1920x1080 frames (8.3 MB each) plus their 240x135 thumbs (130 KB each). The next frame
 * is decoded right after a transition so the following one starts instantly.
 *
 * Everything here runs on the main thread (viewModelScope); decoding and blurring hop to IO.
 */
class WallpaperEngine(private val appContext: Context) : ViewModel() {
    private companion object {
        /** How long a test from a phone holds the screen without Use this. */
        const val PREVIEW_HOLD_MS = 10 * 60_000L
    }

    private val blurs = BlurCache(appContext)
    /** Small copies for the Wallpapers screen. */
    val thumbs = Thumbs(appContext)
    var current by mutableStateOf<Frame?>(null); private set
    var next by mutableStateOf<Frame?>(null); private set
    /** 0 = showing [current]; 1 = [next] fully in. The layer animates this and calls [onTransitionDone]. */
    var transitioning by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var intervalMs by mutableStateOf(60_000L)
    var empty by mutableStateOf(false); private set
    /** Every photo in the rotation, newest first: the Wallpapers screen's grid. */
    var library by mutableStateOf<List<File>>(emptyList()); private set
    /** Crossfade progress 0..1 and Ken Burns zoom, published by the layer so the glass can follow. */
    var fade by mutableStateOf(0f)
    var zoom by mutableStateOf(1f)

    private var queue = ArrayDeque<File>()
    private var known = emptySet<File>()
    private var started = false
    private var ticker: Job? = null
    private var prefetching = false
    private var lastShown: File? = null
    /** Photos that would not decode, left out of the shuffle until the launcher restarts. */
    private val broken = mutableSetOf<File>()
    /** Completed when a crossfade ends, for a photo waiting to go up next. */
    private var transitionEnded: CompletableDeferred<Unit>? = null
    /** A test from a phone on screen (a file in the cache, not one of the photos); the rotation holds while it is up. */
    private var preview: File? = null
    private var previewEnd: Job? = null

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            rescan()
            val first = decodeNext()
            // A photo from a phone may have gone up while this one decoded.
            if (current == null) current = first else first?.let { queue.addFirst(it.file) }
            prefetch()
            restartTicker()
            // Pre-render every photo's blurs once, after the first frame is up, so later
            // switches are a 30 KB read instead of ~0.6 s of CPU on the TV.
            launch {
                blurs.warmAll(known.toList())
                withContext(Dispatchers.IO) { thumbs.sweep(known) }
            }
        }
    }

    /** Skip to the next photo now (FF button). */
    fun skip() { viewModelScope.launch { advance() } }

    fun togglePause() { paused = !paused }

    /** Called on every Home press: new files pushed over adb go to the front of the queue. */
    fun rescan() {
        val files = Wallpapers.list()
        empty = files.isEmpty()
        val fresh = files.filter { it !in known }
        if (known.isNotEmpty() && fresh.isNotEmpty()) {
            fresh.shuffled().reversed().forEach { queue.addFirst(it) }
            Log.i("Glass", "${fresh.size} new wallpaper(s) queued first")
        }
        known = files.toSet()
        queue.retainAll(known)
        library = newestFirst(known)
    }

    /**
     * Puts [file] up now: decoded and its glass computed off the main thread, then the usual
     * crossfade, and the interval starts again so it gets its full time. A crossfade already
     * running finishes first. False when the photo cannot be decoded.
     */
    suspend fun showNow(file: File): Boolean {
        include(file)
        queue.remove(file)
        val frame = decode(file) ?: run { Log.w("Glass", "cannot decode ${file.name}"); return false }
        awaitTransition()
        lastShown = file
        put(frame)
        // A photo put up for good replaces a test on screen; the test is not one of the photos.
        preview?.let { dropPreview(it) }
        restartTicker()
        return true
    }

    /**
     * A test from a phone, up for a look: the usual crossfade, but it is not one of the photos (not
     * in the rotation, not on the Wallpapers screen) and the rotation holds while it is up. A newer
     * test replaces it; after [PREVIEW_HOLD_MS] without one, or Use this, the photos come back.
     */
    suspend fun showPreview(file: File): Boolean {
        val frame = decode(file) ?: run { Log.w("Glass", "cannot decode test ${file.name}"); return false }
        awaitTransition()
        val earlier = preview
        put(frame)
        preview = file
        if (earlier != null && earlier != file) discard(earlier)
        previewEnd?.cancel()
        previewEnd = viewModelScope.launch {
            delay(PREVIEW_HOLD_MS)
            // Nobody said Use this: back to the photos, with the usual crossfade.
            if (current?.file == preview) skip() else preview?.let { dropPreview(it) }
        }
        return true
    }

    /**
     * Use this: [saved], a copy of the test [previewFile], takes its place. If the test is on
     * screen nothing visibly changes; it is simply one of the photos now. [replaces] (earlier
     * framings of the same photo) leave the TV.
     */
    fun keepPreview(previewFile: File, saved: File, replaces: List<File>) {
        include(saved)
        blurs.move(previewFile, saved)
        current?.takeIf { it.file == previewFile }?.let { it.file = saved; lastShown = saved }
        next?.takeIf { it.file == previewFile }?.let { it.file = saved }
        if (preview == previewFile) { preview = null; previewEnd?.cancel() }
        previewFile.delete()
        replaces.forEach { remove(it) }
        restartTicker()
    }

    /** Puts [frame] up next: at once if nothing is on screen, else with the crossfade. */
    private fun put(frame: Frame) {
        if (current == null) { current = frame; return }
        // A prefetched photo steps aside and comes after this one.
        next?.let { n -> if (n.file != frame.file && n.file in known) queue.addFirst(n.file) }
        next = frame
        transitioning = true
    }

    private suspend fun awaitTransition() {
        while (transitioning) {
            (transitionEnded ?: CompletableDeferred<Unit>().also { transitionEnded = it }).await()
        }
    }

    /** The test is over (replaced, kept elsewhere, or moved on from): forget it and its file. */
    private fun dropPreview(file: File) {
        if (preview == file) { preview = null; previewEnd?.cancel() }
        discard(file)
    }

    private fun discard(file: File) {
        blurs.forget(file)
        PhotoInfos.remove(file.name)
        file.delete()
    }

    /**
     * A photo that joins the rotation without going up now (sent with "later", or one of several
     * that waited while the TV was off): it comes next, and its glass is rendered in the background.
     */
    fun add(file: File) {
        include(file)
        if (file !in queue) queue.addFirst(file)
        viewModelScope.launch { blurs.warm(file) }
    }

    /**
     * Takes [file] off the TV: deleted where the launcher may, hidden otherwise (see [Wallpapers]).
     * If it is on screen the next photo crossfades in. True when the file was deleted.
     */
    fun remove(file: File): Boolean {
        // The caches are keyed by the file's size and time, so they go before the file does.
        blurs.forget(file)
        thumbs.forget(file)
        val deleted = Wallpapers.remove(file)
        if (deleted) PhotoInfos.remove(file.name)
        Log.i("Glass", "removed ${file.name}${if (deleted) "" else " (hidden: the launcher may not delete it)"}")
        known = known - file
        queue.remove(file)
        library = library - file
        empty = known.isEmpty()
        if (next?.file == file && !transitioning) next = null
        when {
            current?.file != file -> prefetch()
            known.isEmpty() -> current = null
            else -> skip()
        }
        return deleted
    }

    private fun include(file: File) {
        if (file in known) return
        known = known + file
        library = newestFirst(known)
        empty = false
    }

    private fun newestFirst(files: Collection<File>) = files.sortedByDescending { it.lastModified() }

    private suspend fun advance() {
        if (transitioning) return
        if (next == null) {
            val frame = decodeNext() ?: return
            // Something else put a photo up while this one decoded: this one waits its turn.
            if (next != null || transitioning) { queue.addFirst(frame.file); return }
            next = frame
        }
        transitioning = true
    }

    /** The crossfade finished: [next] becomes [current]; the old frame is dropped for GC. */
    fun onTransitionDone() {
        val n = next ?: return
        val outgoing = current?.file
        current = n
        next = null
        transitioning = false
        transitionEnded?.complete(Unit)
        transitionEnded = null
        // Moved off a test (Fast-forward, or it timed out): it is gone, and the rotation goes on.
        if (outgoing != null && outgoing == preview && n.file != preview) dropPreview(outgoing)
        prefetch()
    }

    private fun prefetch() {
        if (next != null || prefetching) return
        prefetching = true
        viewModelScope.launch {
            val frame = try { decodeNext() } finally { prefetching = false }
            if (frame == null) return@launch
            if (next == null && !transitioning && frame.file != current?.file) next = frame else queue.addFirst(frame.file)
        }
    }

    /** Starts the interval again from now. */
    private fun restartTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(intervalMs)
                if (!paused && preview == null) advance()
            }
        }
    }

    private suspend fun decodeNext(): Frame? {
        while (true) {
            if (queue.isEmpty()) {
                // New cycle: a fresh shuffle that never starts with the photo just shown.
                if (known.isEmpty()) known = Wallpapers.list().toSet()
                if (known.isEmpty()) { empty = true; return null }
                val files = (known - broken).shuffled().toMutableList()
                if (files.isEmpty()) return null
                if (files.size > 1 && files.first() == lastShown) files.add(files.removeAt(0))
                queue = ArrayDeque(files)
            }
            val file = queue.removeFirst()
            val frame = decode(file)
            if (frame == null) {
                // Skipped for the rest of this run, so a folder of bad files cannot spin.
                Log.w("Glass", "cannot decode ${file.name}; skipped")
                broken += file
                continue
            }
            lastShown = file
            return frame
        }
    }

    /** The photo at about 1920 wide, its two blurs (from the cache, or rendered and stored) and its light. */
    private suspend fun decode(file: File): Frame? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val image = Wallpapers.load(file) ?: return@withContext null
        val (deep, frost) = blurs.blursFor(file, image)
        Log.i("Glass", "wallpaper ${file.name} decoded in ${SystemClock.elapsedRealtime() - t0} ms")
        Frame(file, image, frost, deep, SceneLight.of(frost))
    }
}
