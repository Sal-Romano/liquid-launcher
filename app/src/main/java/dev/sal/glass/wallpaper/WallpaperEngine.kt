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
import dev.sal.glass.data.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One decoded wallpaper, its blurred copies for the glass, and where its light comes from. */
class Frame(val file: File, val image: Bitmap, val thumb: Bitmap, val deep: Bitmap, val light: SceneLight = SceneLight.DEFAULT) {
    val imageBitmap: ImageBitmap = image.asImageBitmap()
}

/**
 * Rotates the photos in Pictures/Wallpapers. Memory budget on the TV's 192 MB heap: at most two
 * decoded 1920x1080 frames (8.3 MB each) plus their 240x135 thumbs (130 KB each). The next frame
 * is decoded right after a transition so the following one starts instantly.
 */
class WallpaperEngine(private val appContext: Context) : ViewModel() {
    private val blurs = BlurCache(appContext)
    var current by mutableStateOf<Frame?>(null); private set
    var next by mutableStateOf<Frame?>(null); private set
    /** 0 = showing [current]; 1 = [next] fully in. The layer animates this and calls [onTransitionDone]. */
    var transitioning by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var intervalMs by mutableStateOf(60_000L)
    var empty by mutableStateOf(false); private set
    /** Crossfade progress 0..1 and Ken Burns zoom, published by the layer so the glass can follow. */
    var fade by mutableStateOf(0f)
    var zoom by mutableStateOf(1f)

    private var queue = ArrayDeque<File>()
    private var known = emptySet<File>()
    private var ticker: Job? = null
    private var lastShown: File? = null

    fun start() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            if (current == null) {
                rescan()
                current = decodeNext()
                prefetch()
                // Pre-render every photo's blurs once, after the first frame is up, so later
                // switches are a 30 KB read instead of ~0.6 s of CPU on the TV.
                launch { blurs.warmAll(known.toList()) }
            }
            while (isActive) {
                delay(intervalMs)
                if (!paused) advance()
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
    }

    private suspend fun advance() {
        if (transitioning) return
        if (next == null) next = decodeNext() ?: return
        transitioning = true
    }

    /** The crossfade finished: [next] becomes [current]; the old frame is dropped for GC. */
    fun onTransitionDone() {
        val n = next ?: return
        current = n
        next = null
        transitioning = false
        prefetch()
    }

    private fun prefetch() {
        viewModelScope.launch { if (next == null) next = decodeNext() }
    }

    private suspend fun decodeNext(): Frame? {
        if (queue.isEmpty()) {
            // New cycle: a fresh shuffle that never starts with the photo just shown.
            if (known.isEmpty()) known = Wallpapers.list().toSet()
            if (known.isEmpty()) { empty = true; return null }
            val files = known.shuffled().toMutableList()
            if (files.size > 1 && files.first() == lastShown) files.add(files.removeAt(0))
            queue = ArrayDeque(files)
        }
        val file = queue.removeFirst()
        val t0 = SystemClock.elapsedRealtime()
        val frame = withContext(Dispatchers.IO) {
            val image = Wallpapers.load(file) ?: return@withContext null
            val (deep, frost) = blurs.blursFor(file, image)
            Frame(file, image, frost, deep, SceneLight.of(frost))
        } ?: return decodeNext()
        lastShown = file
        Log.i("Glass", "wallpaper ${file.name} decoded in ${SystemClock.elapsedRealtime() - t0} ms")
        return frame
    }
}
