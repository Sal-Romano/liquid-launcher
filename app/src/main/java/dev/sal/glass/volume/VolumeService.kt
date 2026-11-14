package dev.sal.glass.volume

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.sal.glass.config.ConfigStore
import kotlin.math.ceil

/**
 * The volume pill, and optionally the launcher's own volume levels.
 *
 * Fire OS handles the remote's volume keys below every app and every accessibility key filter (the
 * filter sees D-pad keys but never Volume Up/Down; checked on the Hisense, 2026-11-08), so the keys
 * cannot be taken. What can be done: the service is told the instant Fire OS's own volume box
 * appears and hears every volume change, so it draws [VolumeHud] over that box with the real level,
 * and the stock box is never seen. That is the default (Settings: Volume pill).
 *
 * With levels set (Settings: Volume levels), each press is carried on to the next of 10, 20, 25 or
 * 50 levels. On the Hisense this is off: the audio goes to a Roku soundbar over eARC, which can only
 * be walked a step at a time, by CEC or by network, and it chirps and stalls on the way (2026-11-13).
 * The code stays for a soundbar that takes an absolute volume. Mute is never touched.
 *
 * Enabled over adb, next to any other service already listed:
 *   adb shell settings put secure enabled_accessibility_services \
 *     <existing>:dev.sal.glass/dev.sal.glass.volume.VolumeService
 */
class VolumeService : AccessibilityService() {
    private lateinit var audio: AudioManager
    private var hud: VolumeHud? = null
    /** The index we are walking the soundbar to, -1 when settled; the direction; when it was set. */
    private var target = -1
    private var targetUp = false
    private var targetAt = 0L
    /** When the last target was reached: a report or two can still trail in after that. */
    private var settledAt = 0L
    /** The level the last press asked for, and whether we already corrected a miss once. */
    private var lastGoal = -1
    private var corrected = false

    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            if (intent.action == "android.media.STREAM_MUTE_CHANGED_ACTION") { pill(); return }
            val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val before = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", now)
            if (now < 0 || now == before) return
            val cfg = ConfigStore.peek(this@VolumeService)?.volume
            if ((cfg?.levels ?: 0) > 0) onVolume(before, now) else pill()
        }
    }

    /** Pill-only: show the real level, as Fire OS has it. */
    private fun pill() {
        val cfg = ConfigStore.peek(this)?.volume
        if (cfg != null && !cfg.pill) return
        hud?.show(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    override fun onServiceConnected() {
        System.setProperty("http.keepAlive", "false") // see tapSoundbar
        audio = getSystemService(AudioManager::class.java)
        hud = VolumeHud(this)
        registerReceiver(volumeChanged, IntentFilter().apply { addAction("android.media.VOLUME_CHANGED_ACTION"); addAction("android.media.STREAM_MUTE_CHANGED_ACTION") })
        Log.i("Glass", "volume service connected")
    }

    private fun onVolume(before: Int, now: Int) {
        val levels = ConfigStore.peek(this)?.volume?.levels ?: 0
        if (levels <= 0) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = (max / levels).coerceAtLeast(1)
        val t = SystemClock.uptimeMillis()
        if (target >= 0) {
            val pending = target
            val reached = if (targetUp) now >= pending else now <= pending
            val toward = if (targetUp) now > before else now < before
            if (t - targetAt < SETTLE_MS && (toward || reached)) {
                hud?.show(pending, max, false, levels)
                if (reached) landed(now, t) else nudge()
                return
            }
            target = -1 // stale or went the other way: this change is a press
        } else if (t - settledAt < TRAIL_MS && kotlin.math.abs(now - before) <= 2) {
            landed(now, t) // the soundbar's last report trailing in after we settled: a miss to correct, or noise
            return
        }
        // A press from the remote moved it by one of Fire OS's steps: carry it on to our next level.
        val up = now > before
        val level = (if (up) before / step + 1 else ceil(before / step.toFloat()).toInt() - 1).coerceIn(0, levels)
        val goal = (level * step).coerceIn(0, max)
        hud?.show(goal, max, false, levels)
        lastGoal = goal; corrected = false
        if (goal != now) {
            target = goal; targetUp = goal > now; targetAt = t; nudges = 0
            val host = ConfigStore.peek(this)?.volume?.soundbar
            if (host != null) tapSoundbar(host, goal - now) else nudge()
        } else {
            settle(t)
        }
    }

    /**
     * The reports have stopped near the goal. With the soundbar on the network, Fire OS's own step
     * and ours can overlap while CEC catches up, so the level may land a step or two off: tap the
     * difference once, then leave it, whatever it says.
     */
    private fun landed(now: Int, t: Long) {
        settle(t)
        val host = ConfigStore.peek(this)?.volume?.soundbar ?: return
        if (corrected || lastGoal < 0 || now == lastGoal || kotlin.math.abs(now - lastGoal) > 3) return
        corrected = true
        target = lastGoal; targetUp = lastGoal > now; targetAt = t
        tapSoundbar(host, lastGoal - now)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var nudges = 0
    private val nudgeAgain = Runnable {
        // No report came back: either the soundbar is slow or that tap did nothing. Look, then tap.
        if (target < 0) return@Runnable
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val reached = if (targetUp) now >= target else now <= target
        if (reached) settle(SystemClock.uptimeMillis()) else nudge()
    }

    /** One tap toward the target: a set moves the soundbar a step over CEC. */
    private fun nudge() {
        handler.removeCallbacks(nudgeAgain)
        val goal = target
        if (goal < 0) return
        if (nudges >= MAX_NUDGES || SystemClock.uptimeMillis() - targetAt > SETTLE_MS) { settle(SystemClock.uptimeMillis()); return }
        nudges++
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, goal, 0) }
            .onFailure { Log.w("Glass", "set volume failed", it); settle(SystemClock.uptimeMillis()); return }
        handler.postDelayed(nudgeAgain, NUDGE_WAIT_MS)
    }

    /**
     * The rest of the way over the network: one Roku ECP key press per step, all in flight at once,
     * 25 ms apart, each on its own connection, so one slow answer (the soundbar stalls now and then
     * while it handles a press) cannot hold the others back. From the TV the soundbar answers in
     * about 55 ms, so the steps land within a quarter of a second. The TV learns the result from
     * the soundbar's next CEC report, which the pending target absorbs; a press that times out has
     * usually landed anyway, and [landed] corrects whatever did not.
     */
    private fun tapSoundbar(host: String, delta: Int) {
        val key = if (delta > 0) "VolumeUp" else "VolumeDown"
        handler.postDelayed(nudgeAgain, NUDGE_WAIT_MS * 2)
        val t0 = SystemClock.uptimeMillis()
        repeat(kotlin.math.abs(delta)) { i ->
            Thread {
                Thread.sleep(25L * i)
                val result = runCatching {
                    // Direct, never through a system proxy: the soundbar is on the LAN.
                    val c = java.net.URL("http://$host:8060/keypress/$key").openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    try {
                        // One fresh connection per press, closed after: the soundbar's little web server
                        // does not cope with the keep-alive pooling HttpURLConnection does by default,
                        // and the next press would talk into a dead socket until it timed out.
                        c.requestMethod = "POST"; c.connectTimeout = 1000; c.readTimeout = 2500; c.doOutput = true
                        c.setRequestProperty("Connection", "close")
                        c.outputStream.close()
                        c.responseCode
                    } finally { c.disconnect() }
                }
                val code = result.getOrNull()
                val took = SystemClock.uptimeMillis() - t0
                if (code == null || code !in 200..299) Log.w("Glass", "soundbar tap ${i + 1} failed after $took ms: ${code ?: result.exceptionOrNull()?.toString()}")
                else Log.i("Glass", "soundbar tap ${i + 1}/${kotlin.math.abs(delta)} ok at $took ms")
            }.start()
        }
    }

    private fun settle(t: Long) {
        handler.removeCallbacks(nudgeAgain)
        target = -1; settledAt = t
    }

    /**
     * Fire OS puts its volume box up the instant the key is pressed, a second before the soundbar
     * reports the new volume back; the service is told the moment that window appears, so the pill
     * goes up right then, over it, with the level we are at or heading to.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != "com.android.systemui") return
        val what = "${event.className} ${event.text}"
        Log.i("Glass", "volume: systemui window $what")
        if (!what.contains("olume")) return
        val cfg = ConfigStore.peek(this)?.volume
        if (cfg != null && !cfg.pill) return
        val levels = cfg?.levels ?: 0
        if (levels <= 0) { pill(); return }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val shown = if (target >= 0) target else audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        hud?.show(shown, max, audio.isStreamMute(AudioManager.STREAM_MUSIC), levels)
    }
    override fun onInterrupt() = Unit

    private companion object {
        /** How long a target may take to land before changes count as presses again. */
        const val SETTLE_MS = 4000L
        /** How long after landing a small change is still taken for the soundbar's trailing report. */
        const val TRAIL_MS = 800L
        /** How long to wait for the soundbar's report before tapping again, and how many taps at most. */
        const val NUDGE_WAIT_MS = 1500L
        const val MAX_NUDGES = 12
    }

    override fun onDestroy() {
        handler.removeCallbacks(nudgeAgain)
        runCatching { unregisterReceiver(volumeChanged) }
        hud?.dismiss()
        super.onDestroy()
    }
}
