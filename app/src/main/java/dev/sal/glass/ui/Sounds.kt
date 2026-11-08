package dev.sal.glass.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sal.glass.R
import dev.sal.glass.config.SoundMode

/**
 * The launcher's own navigation sounds: a glass-bead tick when focus moves and a lower tap on OK,
 * in place of the stock Fire OS click. Played on the sonification stream like system sound
 * effects, so the TV's volume and mute apply. A tick only plays within a moment of a key press,
 * so focus that moves by itself (the first tile on launch, a sheet opening) stays silent.
 */
object Sounds {
    private var pool: SoundPool? = null
    private var tickId = 0
    private var tapId = 0
    private var lastTick = 0L
    /** Uptime of the last key the root saw; set by the key router. */
    @Volatile var keyAt = 0L
    var mode by mutableStateOf(SoundMode.Glass)

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build().also {
            tickId = it.load(context.applicationContext, R.raw.tick, 1)
            tapId = it.load(context.applicationContext, R.raw.tap, 1)
        }
    }

    /** Focus moved. Silent unless a key caused it, and never twice within 40 ms. */
    fun tick() {
        if (mode != SoundMode.Glass) return
        val now = SystemClock.uptimeMillis()
        if (now - keyAt > 600 || now - lastTick < 40) return
        lastTick = now
        pool?.play(tickId, 0.5f, 0.5f, 1, 0, 1f)
    }

    /** OK on something that opens or launches. */
    fun tap() {
        if (mode != SoundMode.Glass) return
        pool?.play(tapId, 0.6f, 0.6f, 1, 0, 1f)
    }
}
