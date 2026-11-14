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
 * The volume, in the launcher's own levels and with its own pill.
 *
 * Fire OS handles the remote's volume keys below every app and every accessibility key filter (the
 * filter sees D-pad keys but never Volume Up/Down; checked on the Hisense, 2026-11-08), so the keys
 * cannot be taken. What can be done: watch the volume change, snap it on to the next of our levels
 * in the direction it moved, and draw our pill over the stock popup, which is an opaque box at the
 * top centre that an accessibility overlay sits above. A press moves one level; holding ramps.
 * With levels set to System nothing happens. Mute is never touched.
 *
 * The soundbar is on the other end of CEC system-audio mode, so a set does not land at once:
 * Android walks the soundbar to the index one CEC key at a time and the soundbar reports each step
 * back, which arrives here as a volume change about a second apart. Those are feedback, not
 * presses: while a target is pending, changes heading toward it (or landing on it) are ignored,
 * and only a change that goes elsewhere, or arrives after the target settled, counts as a press.
 * Without this, one tap became five, one a second (seen 2026-11-13).
 *
 * And a set is only a nudge: CEC has no "go to 35", so Android taps the soundbar's key once per
 * set and the soundbar moves a step. The service keeps nudging, one tap per report that comes
 * back (or after a pause if none does), until the level lands, with a cap on taps and time.
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

    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val before = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", now)
            if (now < 0 || now == before) return
            onVolume(before, now)
        }
    }

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        hud = VolumeHud(this)
        registerReceiver(volumeChanged, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        Log.i("Glass", "volume service connected")
    }

    private fun onVolume(before: Int, now: Int) {
        val levels = ConfigStore.peek(this)?.volume?.levels ?: 20
        if (levels <= 0) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = (max / levels).coerceAtLeast(1)
        val t = SystemClock.uptimeMillis()
        if (target >= 0) {
            val pending = target
            val reached = if (targetUp) now >= pending else now <= pending
            val toward = if (targetUp) now > before else now < before
            if (t - targetAt < SETTLE_MS && (toward || reached)) {
                hud?.show(pending / step, levels, pending)
                if (reached) settle(t) else nudge()
                return
            }
            target = -1 // stale or went the other way: this change is a press
        } else if (t - settledAt < TRAIL_MS && kotlin.math.abs(now - before) <= 2) {
            return // the soundbar's last report trailing in after we settled
        }
        // A press from the remote moved it by one of Fire OS's steps: carry it on to our next level.
        val up = now > before
        val level = (if (up) before / step + 1 else ceil(before / step.toFloat()).toInt() - 1).coerceIn(0, levels)
        val goal = (level * step).coerceIn(0, max)
        hud?.show(level, levels, goal)
        if (goal != now) {
            target = goal; targetUp = goal > now; targetAt = t; nudges = 0
            nudge()
        } else {
            settle(t)
        }
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

    private fun settle(t: Long) {
        handler.removeCallbacks(nudgeAgain)
        target = -1; settledAt = t
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private companion object {
        /** How long a target may take to land before changes count as presses again. */
        const val SETTLE_MS = 4000L
        /** How long after landing a small change is still taken for the soundbar's trailing report. */
        const val TRAIL_MS = 800L
        /** How long to wait for the soundbar's report before tapping again, and how many taps at most. */
        const val NUDGE_WAIT_MS = 700L
        const val MAX_NUDGES = 12
    }

    override fun onDestroy() {
        handler.removeCallbacks(nudgeAgain)
        runCatching { unregisterReceiver(volumeChanged) }
        hud?.dismiss()
        super.onDestroy()
    }
}
