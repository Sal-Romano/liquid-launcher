package dev.sal.glass.volume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.res.ResourcesCompat
import dev.sal.glass.R

/**
 * The volume pill, drawn exactly over Fire OS's own volume box (an opaque 720x128 px slab at the top
 * centre on the 1080p Hisense) so the stock one is never seen: ours is opaque too, flush with the
 * top edge, rounded below. "Volume" on the left, one segment per level in the middle, the TV's
 * number on the right. Fades in on a change and out 3.5 s after the last one, longer than the stock
 * box stays, so nothing shows through when ours leaves. An accessibility overlay, so it sits over
 * every app, including protected video.
 */
class VolumeHud(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = PillView(context)
    private var shown = false
    private val hide = Runnable { view.animate().alpha(0f).setDuration(220).withEndAction { dismiss() }.start() }

    fun show(level: Int, levels: Int, value: Int) {
        view.level = level; view.levels = levels; view.value = value
        view.invalidate()
        view.removeCallbacks(hide)
        view.animate().cancel()
        if (!shown) {
            view.alpha = 0f
            runCatching { wm.addView(view, params()) }.onFailure { return }
            shown = true
        }
        view.animate().alpha(1f).setDuration(120).start()
        view.postDelayed(hide, 3500)
    }

    fun dismiss() {
        view.removeCallbacks(hide)
        if (shown) { runCatching { wm.removeView(view) }; shown = false }
    }

    private fun params(): WindowManager.LayoutParams {
        val d = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (WIDTH_DP * d).toInt(), (HEIGHT_DP * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
    }

    private class PillView(context: Context) : View(context) {
        var level = 0; var levels = 20; var value = 0
        private val d = resources.displayMetrics.density
        // Opaque: it has to hide the stock box underneath.
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 17, 26) }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(48, 255, 255, 255) }
        private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) }
        private val font: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.inter_medium) }.getOrNull()
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255); textSize = 15f * d; typeface = font }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255); textSize = 17f * d; typeface = font; textAlign = Paint.Align.RIGHT }
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = 16 * d
            // Flush with the top edge: the slab's top corners are off screen, only the bottom ones round.
            rect.set(0f, -r, w, h); c.drawRoundRect(rect, r, r, fill)
            rect.set(rim.strokeWidth / 2, -r, w - rim.strokeWidth / 2, h - rim.strokeWidth / 2); c.drawRoundRect(rect, r, r, rim)
            val mid = h / 2
            c.drawText("Volume", 28 * d, mid - (label.descent() + label.ascent()) / 2, label)
            c.drawText(value.toString(), w - 28 * d, mid - (number.descent() + number.ascent()) / 2, number)
            // Segments between the label and the number; a continuous bar when there are many.
            val x0 = 100 * d; val x1 = w - 76 * d
            if (levels <= 25) {
                val gap = 3 * d; val seg = (x1 - x0 - gap * (levels - 1)) / levels; val sh = 10 * d
                for (i in 0 until levels) {
                    val x = x0 + i * (seg + gap)
                    rect.set(x, mid - sh / 2, x + seg, mid + sh / 2)
                    c.drawRoundRect(rect, 2 * d, 2 * d, if (i < level) on else off)
                }
            } else {
                val bh = 6 * d
                rect.set(x0, mid - bh / 2, x1, mid + bh / 2); c.drawRoundRect(rect, bh / 2, bh / 2, off)
                rect.set(x0, mid - bh / 2, x0 + (x1 - x0) * level / levels, mid + bh / 2); c.drawRoundRect(rect, bh / 2, bh / 2, on)
            }
        }
    }

    private companion object {
        const val WIDTH_DP = 360f
        const val HEIGHT_DP = 64f
    }
}
