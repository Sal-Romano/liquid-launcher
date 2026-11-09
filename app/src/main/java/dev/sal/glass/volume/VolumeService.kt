package dev.sal.glass.volume

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
 * Enabled over adb, next to any other service already listed:
 *   adb shell settings put secure enabled_accessibility_services \
 *     <existing>:dev.sal.glass/dev.sal.glass.volume.VolumeService
 */
class VolumeService : AccessibilityService() {
    private lateinit var audio: AudioManager
    private var hud: VolumeHud? = null
    /** The index we last set ourselves; its own change broadcast is not a key press. */
    private var ours = -1

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
        if (now == ours) { ours = -1; hud?.show(now / step, levels, now); return }
        // A press from the remote moved it by one of Fire OS's steps: carry it on to our next level.
        val up = now > before
        val level = (if (up) before / step + 1 else ceil(before / step.toFloat()).toInt() - 1).coerceIn(0, levels)
        val target = (level * step).coerceIn(0, max)
        hud?.show(level, levels, target)
        if (target != now) {
            ours = target
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                .onFailure { Log.w("Glass", "set volume failed", it); ours = -1 }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(volumeChanged) }
        hud?.dismiss()
        super.onDestroy()
    }
}
