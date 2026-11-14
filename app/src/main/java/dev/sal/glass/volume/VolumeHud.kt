package dev.sal.glass.volume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import dev.sal.glass.R

/**
 * The volume pill, drawn over Fire OS's own volume box (an opaque 720x128 px slab with square
 * corners at the top centre on the 1080p Hisense) so the stock one is never seen: ours is a little
 * larger all round, so its rounded corners still cover the stock box's square ones. Opaque, since
 * it has to hide what is under it (and Android 11 has no blur for an overlay anyway), but lit like
 * the launcher's glass: a specular wash down from the top, a rim brighter where the light is, and
 * a sheen that sweeps across once as it appears. Left: a speaker whose
 * waves grow with the level. Middle: a track with a bright, softly glowing fill that eases to each
 * new value. Right: the number, or "Muted". Fades in on a change and out 3.4 s after the last one,
 * a touch longer than the stock box stays, so nothing shows through when ours leaves. An
 * accessibility overlay, so it sits over every app, including protected video.
 */
class VolumeHud(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = PillView(context)
    private var shown = false
    private val hide = Runnable { view.animate().alpha(0f).translationY(-view.height * 0.15f).setDuration(240).withEndAction { dismiss() }.start() }

    /** [value] on a 0..[max] scale; [level]/[levels] draw segment ticks when levels are in use (0 = none). */
    fun show(value: Int, max: Int, muted: Boolean, levels: Int = 0) {
        view.setValue(value, max, muted, levels)
        view.removeCallbacks(hide)
        view.animate().cancel()
        if (!shown) {
            view.alpha = 0f; view.translationY = -view.height * 0.15f
            runCatching { wm.addView(view, params()) }.onFailure { return }
            shown = true
            view.sheen()
        }
        view.animate().alpha(1f).translationY(0f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        view.postDelayed(hide, 3400)
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
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 0 }
    }

    private class PillView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var value = 0; private var max = 100; private var muted = false; private var levels = 0
        /** The value the bar shows, easing toward [value]. */
        private var shownValue = 0f
        private var animator: ValueAnimator? = null

        // Opaque slab: it has to hide the stock box underneath. A little lighter at the top, like light on glass.
        private val slab = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.argb(46, 255, 255, 255) }
        private val specular = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var sheenT = -1f
        private var sheenAnim: ValueAnimator? = null
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 255, 255) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(7f * d, BlurMaskFilter.Blur.NORMAL) }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val speaker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255) }
        private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; color = Color.argb(225, 255, 255, 255) }
        private val display: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.inter_display_medium) }.getOrNull()
        private val medium: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.inter_medium) }.getOrNull()
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 255, 255); textSize = 24f * d; typeface = display; textAlign = Paint.Align.RIGHT }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = 14f * d; typeface = medium; textAlign = Paint.Align.RIGHT }
        private val rect = RectF()
        private val path = Path()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // BlurMaskFilter needs it

        fun setValue(v: Int, m: Int, mute: Boolean, lv: Int) {
            val first = max == 100 && value == 0 && shownValue == 0f && !isAttachedToWindow
            value = v; max = m.coerceAtLeast(1); muted = mute; levels = lv
            animator?.cancel()
            if (first) { shownValue = v.toFloat(); invalidate(); return }
            animator = ValueAnimator.ofFloat(shownValue, v.toFloat()).apply {
                duration = 200; interpolator = DecelerateInterpolator()
                addUpdateListener { shownValue = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            slab.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(36, 42, 58), Color.rgb(11, 14, 22), Shader.TileMode.CLAMP)
            // Light from above: a wash that fades out by mid-height, and a rim bright at the top, faint below.
            specular.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            rim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(110, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP)
        }

        /** A diagonal band of light crossing once, as on a dock tile when it takes focus. */
        fun sheen() {
            sheenAnim?.cancel()
            sheenAnim = ValueAnimator.ofFloat(-0.6f, 1.6f).apply {
                duration = 650; startDelay = 60; interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { sheenT = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = 18 * d
            // The slab, flush with the top edge: its top corners are off screen, only the bottom ones round.
            rect.set(0f, -r, w, h); c.drawRoundRect(rect, r, r, slab)
            c.drawRoundRect(rect, r, r, specular)
            if (sheenT > -0.6f && sheenT < 1.6f) {
                // The band is 40% of the width, leaning 20 degrees, clipped to the slab.
                c.save(); path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW); c.clipPath(path)
                val bw = w * 0.4f; val bx = w * sheenT
                sheenPaint.shader = LinearGradient(bx - bw / 2, 0f, bx + bw / 2, 0f, intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
                c.skew(-0.36f, 0f); c.drawRect(bx - bw, -r, bx + bw, h, sheenPaint); c.restore()
            }
            rect.set(rim.strokeWidth / 2, -r, w - rim.strokeWidth / 2, h - rim.strokeWidth / 2); c.drawRoundRect(rect, r, r, rim)

            val mid = h / 2
            val frac = (shownValue / max).coerceIn(0f, 1f)

            // Speaker, left: a box and a cone, then waves that come and go with the level.
            val sx = 30 * d; val s = 7 * d
            path.reset()
            path.moveTo(sx, mid - s * 0.5f); path.lineTo(sx + s * 0.55f, mid - s * 0.5f); path.lineTo(sx + s * 1.25f, mid - s * 1.05f)
            path.lineTo(sx + s * 1.25f, mid + s * 1.05f); path.lineTo(sx + s * 0.55f, mid + s * 0.5f); path.lineTo(sx, mid + s * 0.5f); path.close()
            speaker.alpha = if (muted) 110 else 225
            c.drawPath(path, speaker)
            if (muted) {
                wave.alpha = 225
                val cx = sx + s * 2.1f
                c.drawLine(cx - s * 0.45f, mid - s * 0.45f, cx + s * 0.45f, mid + s * 0.45f, wave)
                c.drawLine(cx + s * 0.45f, mid - s * 0.45f, cx - s * 0.45f, mid + s * 0.45f, wave)
            } else {
                for (i in 0 until 3) {
                    val on = frac > i / 3f + 0.02f
                    wave.alpha = if (on) 225 else 50
                    val rr = s * (0.95f + i * 0.5f)
                    rect.set(sx + s * 0.9f - rr, mid - rr, sx + s * 0.9f + rr, mid + rr)
                    c.drawArc(rect, -38f, 76f, false, wave)
                }
            }

            // Track and fill, middle.
            val x0 = 92 * d; val x1 = w - 84 * d; val th = 5 * d
            rect.set(x0, mid - th / 2, x1, mid + th / 2); c.drawRoundRect(rect, th / 2, th / 2, track)
            if (levels > 0) for (i in 1 until levels) { val x = x0 + (x1 - x0) * i / levels; c.drawCircle(x, mid + th * 2.2f, 1f * d, tick) }
            val fx = x0 + (x1 - x0) * frac
            if (frac > 0f && !muted) {
                fill.shader = LinearGradient(x0, 0f, x1, 0f, Color.rgb(196, 226, 255), Color.WHITE, Shader.TileMode.CLAMP)
                glow.shader = fill.shader; glow.alpha = 110
                rect.set(x0, mid - th / 2, fx.coerceAtLeast(x0 + th), mid + th / 2)
                c.drawRoundRect(rect, th / 2, th / 2, glow)
                c.drawRoundRect(rect, th / 2, th / 2, fill)
                // A bright bead at the head of the fill.
                glow.alpha = 160; c.drawCircle(fx, mid, th * 0.9f, glow)
                c.drawCircle(fx, mid, th * 0.55f, fill)
            }

            // The number, right. Fire OS counts 0..100 and so do we.
            val ny = mid - (number.descent() + number.ascent()) / 2
            if (muted) c.drawText("Muted", w - 28 * d, mid - (small.descent() + small.ascent()) / 2, small)
            else c.drawText(value.toString(), w - 28 * d, ny, number)
        }
    }

    private companion object {
        // The stock box is 360x64 dp; 6 dp more each side and below keep our rounded corners over its square ones.
        const val WIDTH_DP = 372f
        const val HEIGHT_DP = 70f
    }
}
