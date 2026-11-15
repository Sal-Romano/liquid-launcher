package dev.sal.glass.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What the TV is playing, read from the apps' media sessions: the song, its art, and the app that
 * plays it. A session belongs to the package that made it, and each copy of Spotify is its own
 * package (com.spotify.tv.android, and the clone com.spotify.tv.android.second), so this always
 * knows which copy is playing, whoever cast to it from their phone. [open] goes to that copy.
 *
 * A track shows while it plays and for [PausedShownMs] after it pauses, so Play/Pause can bring it
 * back. Android shares other apps' sessions only with an enabled notification listener
 * ([MediaListener], switched on over adb); without one [track] stays null and the log says so once.
 */
object NowPlaying {
    class Track(
        val packageName: String,
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val playing: Boolean,
        internal val controller: MediaController,
    )

    /** What the home screen shows, or null. Compose state, written on the main thread. */
    var track by mutableStateOf<Track?>(null)
        private set

    /** How long a paused track stays up. */
    const val PausedShownMs = 5 * 60_000L

    private class Followed(val controller: MediaController, val callback: MediaController.Callback)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var context: Context? = null
    private var manager: MediaSessionManager? = null
    private var warned = false
    /** The active sessions, most important first: the order Android gives them in. */
    private var followed = LinkedHashMap<MediaSession.Token, Followed>()
    private val wasPlaying = HashSet<MediaSession.Token>()
    /** When each session stopped playing, for the ones that were seen playing. */
    private val pausedAt = HashMap<MediaSession.Token, Long>()
    private val art = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) = size > 16
    }
    private val loading = HashSet<String>()
    private val expire = Runnable { refresh() }
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    /** Starts following the sessions. Called again on every wake, in case access was switched on since. */
    fun start(context: Context) {
        if (manager != null) return
        val app = context.applicationContext
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(app, MediaListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(changed, listener, main)
        } catch (e: SecurityException) {
            if (!warned) Log.w("Glass", "now playing: no notification access, so no media sessions (README)")
            warned = true
            return
        }
        this.context = app
        manager = m
        follow(runCatching { m.getActiveSessions(listener) }.getOrDefault(emptyList()))
        Log.i("Glass", "now playing: following ${followed.size} active session(s)")
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(changed)
        manager = null
        follow(emptyList())
    }

    /** Opens the app that plays [t] at what it plays (its session's own screen), else at its start. */
    fun open(context: Context, t: Track): Boolean {
        t.controller.sessionActivity?.let { screen ->
            if (runCatching { screen.send() }.isSuccess) {
                Log.i("Glass", "now playing: opened ${t.packageName} at its session")
                return true
            }
        }
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(t.packageName) ?: pm.getLaunchIntentForPackage(t.packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { Log.i("Glass", "now playing: opened ${t.packageName}") }.isSuccess
    }

    fun playPause(t: Track) {
        val controls = t.controller.transportControls
        if (t.playing) controls.pause() else controls.play()
    }

    private fun follow(sessions: List<MediaController>) {
        val next = LinkedHashMap<MediaSession.Token, Followed>()
        for (c in sessions) {
            next[c.sessionToken] = followed.remove(c.sessionToken)
                ?: Followed(c, callback()).also { c.registerCallback(it.callback, main) }
        }
        for (gone in followed.values) gone.controller.unregisterCallback(gone.callback)
        followed = next
        wasPlaying.retainAll(next.keys)
        pausedAt.keys.retainAll(next.keys)
        refresh()
    }

    private fun callback() = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    private fun refresh() {
        main.removeCallbacks(expire)
        val now = System.currentTimeMillis()
        var playing: MediaController? = null
        var paused: MediaController? = null
        var latestPause = 0L
        for ((token, f) in followed) {
            if (isPlaying(f.controller.playbackState)) {
                wasPlaying += token
                pausedAt -= token
                if (playing == null) playing = f.controller
            } else {
                if (wasPlaying.remove(token)) pausedAt[token] = now
                val at = pausedAt[token] ?: continue
                if (now - at < PausedShownMs && at > latestPause) { paused = f.controller; latestPause = at }
            }
        }
        val c = playing ?: paused
        val meta = c?.metadata
        val title = meta?.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        track = if (c == null || meta == null || title == null) null else Track(
            packageName = c.packageName,
            title = title,
            artist = meta.text(
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            ).orEmpty(),
            art = artOf(meta),
            playing = playing != null,
            controller = c,
        )
        if (playing == null && paused != null) main.postDelayed(expire, PausedShownMs - (now - latestPause) + 100)
    }

    private fun isPlaying(s: PlaybackState?) = when (s?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun MediaMetadata.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> getString(key)?.trim()?.takeIf { it.isNotEmpty() } }

    /** The art in the metadata, or behind its URI once that has loaded (then the card updates). */
    private fun artOf(meta: MediaMetadata): Bitmap? {
        for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
            meta.getBitmap(key)?.let { return it }
        }
        val uri = meta.text(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ) ?: return null
        if (art.containsKey(uri)) return art[uri]
        val ctx = context ?: return null
        if (loading.add(uri)) io.execute {
            val bitmap = runCatching { load(ctx, uri) }
                .onFailure { Log.w("Glass", "now playing: no art from $uri: $it") }.getOrNull()
            main.post { loading.remove(uri); art[uri] = bitmap; refresh() }
        }
        return null
    }

    /** The image behind a URI (web or content), at about 320 px across at most. */
    private fun load(context: Context, uri: String): Bitmap? {
        val bytes = if (uri.startsWith("http://") || uri.startsWith("https://")) {
            val c = URL(uri).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4_000
                c.readTimeout = 8_000
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        } else {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
