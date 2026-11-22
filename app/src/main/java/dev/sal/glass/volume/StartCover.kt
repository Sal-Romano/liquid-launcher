package dev.sal.glass.volume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.res.ResourcesCompat
import dev.sal.glass.R
import dev.sal.glass.wallpaper.SceneLight
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The cover of the running [VolumeService]; null when that service is not enabled. */
object StartCoverHost {
    @Volatile var cover: StartCover? = null
}

/**
 * A full-screen "Starting PS5…" over everything, the TV's own "no signal" dialog included, while a
 * hook tile switches the TV to an input whose device is still waking. An accessibility overlay,
 * like the volume pill, because the launcher is no longer in front once the input is selected.
 * It never takes the remote, and it takes itself down after [MAX_MS] whatever happens.
 *
 * The slab is the launcher's glass done on a Canvas, since an overlay has no GL layer: the photo
 * magnified through the middle, mirrored around the bevel, and a rim lit from the photo's own
 * bright patch ([SceneLight]). All of it is shaders on round rects, so nothing needs a blur.
 */
class StartCover(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: CoverView? = null
    private val expire = Runnable { hide() }

    fun show(photo: Bitmap?, text: String, icon: String? = null, light: SceneLight? = null) {
        // A copy: the wallpaper engine may recycle its frame while the cover is up.
        val own = runCatching { photo?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        main.post {
            remove()
            val v = CoverView(context, own, text, icon, light ?: SceneLight.DEFAULT).apply { alpha = 0f }
            runCatching { wm.addView(v, params()) }.onFailure { Log.w("Glass", "start cover failed", it); return@post }
            view = v
            v.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            v.enter()
            main.postDelayed(expire, MAX_MS)
        }
    }

    /** The cover turns into the error, on the same glass, and leaves by itself. */
    fun fail(title: String, hint: String) {
        main.post {
            val v = view ?: return@post
            main.removeCallbacks(expire)
            v.fail(title, hint)
            main.postDelayed(expire, ERROR_MS)
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(expire)
            val v = view ?: return@post
            v.animate().cancel()
            v.leave(FADE_OUT_MS)
            v.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction { if (view === v) remove() }.start()
        }
    }

    fun remove() {
        main.removeCallbacks(expire)
        view?.let { v -> v.stop(); runCatching { wm.removeView(v) } }
        view = null
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private class CoverView(
        context: Context,
        private val photo: Bitmap?,
        private var text: String,
        icon: String?,
        private val light: SceneLight,
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val panel = RectF()
        private val rimRect = RectF()
        private val radius = 62f * d
        private var cx = 0f
        private var cy = 0f

        private val back = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 0, 0); setShadowLayer(30f * d, 0f, 12f * d, Color.argb(130, 0, 0, 0))
        }
        private val lens = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) })
        }
        private val bevel = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 9f * d
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
        }
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenMatrix = Matrix()
        private val sheenBand = 170f * d
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
        private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val iconShade = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.argb(80, 0, 0, 0), PorterDuff.Mode.SRC_IN)
        }
        private val font: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.inter_display_medium) }.getOrNull()
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 21f * d; typeface = font
            setShadowLayer(6f * d, 0f, 1.5f * d, Color.argb(120, 0, 0, 0))
        }
        private val mark: Bitmap? = brandRes(icon)?.let { res ->
            runCatching {
                val size = (ICON_DP * d).toInt()
                val art = context.getDrawable(res)!!.mutate().apply { setTint(Color.WHITE); setBounds(0, 0, size, size) }
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { art.draw(Canvas(it)) }
            }.getOrNull()
        }

        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 14.5f * d; typeface = font
            setShadowLayer(5f * d, 0f, 1f * d, Color.argb(120, 0, 0, 0))
        }
        private val errorRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * d }
        private val errorWash = Paint(Paint.ANTI_ALIAS_FLAG)
        private var hint: String? = null
        private var failing = 0f
        private var failAnim: ValueAnimator? = null

        private var clock = 0f
        private var entered = 0f
        private var left = 0f
        private var loop: ValueAnimator? = null
        private var entering: ValueAnimator? = null
        private var leaving: ValueAnimator? = null

        fun enter() {
            entering = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 460; interpolator = OvershootInterpolator(1.4f)
                addUpdateListener { entered = it.animatedValue as Float; invalidate() }
                start()
            }
            loop = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CYCLE_MS; repeatCount = ValueAnimator.INFINITE; interpolator = null
                addUpdateListener { clock = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun leave(ms: Long) {
            leaving = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms; interpolator = DecelerateInterpolator()
                addUpdateListener { left = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun fail(title: String, why: String) {
            text = title; hint = why
            fit(label, title, 21f); fit(hintPaint, why, 14.5f)
            failAnim?.cancel()
            failAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 620; interpolator = null
                addUpdateListener { failing = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        /** Sized down only if the line would not fit inside the glass. */
        private fun fit(p: Paint, s: String, dp: Float) {
            p.textSize = dp * d
            val room = (PANEL_DP - 44f) * d
            val w = p.measureText(s)
            if (w > room) p.textSize *= room / w
        }

        fun stop() {
            loop?.cancel(); entering?.cancel(); leaving?.cancel(); failAnim?.cancel()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            cx = w / 2f; cy = h / 2f
            val half = PANEL_DP * d / 2
            panel.set(cx - half, cy - half, cx + half, cy + half)
            val inset = bevel.strokeWidth / 2
            rimRect.set(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

            photo?.takeUnless { it.isRecycled }?.let { p ->
                val s = max(w / p.width.toFloat(), h / p.height.toFloat())
                val base = Matrix().apply { setScale(s, s); postTranslate((w - p.width * s) / 2, (h - p.height * s) / 2) }
                back.shader = BitmapShader(p, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(base) }
                lens.shader = BitmapShader(p, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix(base).apply { postScale(1.28f, 1.28f, cx, cy) })
                }
                // The bevel sees the photo from outside the slab, flipped, as the edge of a thick lens does.
                bevel.shader = BitmapShader(p, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
                    setLocalMatrix(Matrix(base).apply { postScale(-0.82f, -0.82f, cx, cy) })
                }
            }

            // Lit from where the photo's light is: bright on that side, a fainter catch opposite.
            var dx = light.x * w - cx
            var dy = light.y * h - cy
            val len = hypot(dx, dy).takeIf { it > 1f } ?: 1f
            dx /= len; dy /= len
            val reach = half * 1.25f
            val lamp = Color.rgb(mix(light.r), mix(light.g), mix(light.b))
            rim.shader = LinearGradient(
                cx + dx * reach, cy + dy * reach, cx - dx * reach, cy - dy * reach,
                intArrayOf(withAlpha(lamp, 245), withAlpha(lamp, 46), withAlpha(lamp, 26), withAlpha(lamp, 150)),
                floatArrayOf(0f, 0.42f, 0.66f, 1f), Shader.TileMode.CLAMP,
            )
            wash.shader = LinearGradient(
                cx + dx * reach, cy + dy * reach, cx - dx * reach, cy - dy * reach,
                intArrayOf(withAlpha(lamp, 72), withAlpha(lamp, 10), Color.TRANSPARENT),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP,
            )
            tint.color = Color.argb(58, 10, 12, 18)
            sheen.shader = LinearGradient(
                0f, 0f, sheenBand, sheenBand * 0.55f,
                intArrayOf(Color.TRANSPARENT, Color.argb(58, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            if (back.shader != null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), back)
                canvas.drawColor(Color.argb(112, 0, 0, 0))
            }

            val scale = 0.9f + 0.1f * entered + 0.07f * left
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            // A short, damped shake, like a pane that was knocked.
            if (failing in 0.001f..0.999f) canvas.translate(sin(failing * 5 * 2 * Math.PI.toFloat()) * (1 - failing) * 11f * d, 0f)
            canvas.drawRoundRect(panel, radius, radius, shadow)
            if (lens.shader != null) canvas.drawRoundRect(panel, radius, radius, lens)
            canvas.drawRoundRect(panel, radius, radius, tint)
            canvas.drawRoundRect(panel, radius, radius, wash)
            if (bevel.shader != null) {
                val r = radius - bevel.strokeWidth / 2
                canvas.drawRoundRect(rimRect, r, r, bevel)
            }
            // One sweep of light early in each cycle, then the glass rests.
            val sweep = (clock / 0.42f).coerceAtMost(1f)
            if (sweep < 1f) {
                val travel = panel.width() + sheenBand * 2
                sheenMatrix.setTranslate(panel.left - sheenBand * 1.4f + travel * sweep, panel.top)
                sheen.shader.setLocalMatrix(sheenMatrix)
                canvas.drawRoundRect(panel, radius, radius, sheen)
            }
            canvas.drawRoundRect(panel, radius, radius, rim)
            val red = (failing * 3).coerceAtMost(1f)
            if (red > 0f) {
                errorWash.color = Color.argb((46 * red).toInt(), 255, 52, 44)
                canvas.drawRoundRect(panel, radius, radius, errorWash)
                errorRim.color = Color.argb((230 * red).toInt(), 255, 104, 92)
                canvas.drawRoundRect(panel, radius, radius, errorRim)
            }

            val breathe = if (hint == null) sin(clock * 2 * Math.PI.toFloat()) else 0f
            mark?.let { m ->
                val top = cy - 30f * d - m.height / 2f
                val left = cx - m.width / 2f
                canvas.save()
                val s = 1f + 0.018f * breathe
                canvas.scale(s, s, cx, top + m.height / 2f)
                canvas.drawBitmap(m, left, top + 3f * d, iconShade)
                iconPaint.alpha = (255 * (1 - 0.3f * red)).toInt()
                canvas.drawBitmap(m, left, top, iconPaint)
                canvas.restore()
            }
            val baseline = if (mark != null) cy + 84f * d else cy + 8f * d
            canvas.drawText(text, cx, baseline, label)
            val why = hint
            if (why != null) canvas.drawText(why, cx, baseline + 27f * d, hintPaint)
            else drawProgress(canvas, baseline + 24f * d)
            canvas.restore()
        }

        /** A bead of light running along a thin track. */
        private fun drawProgress(canvas: Canvas, y: Float) {
            val w = 124f * d
            val h = 3.5f * d
            val x0 = cx - w / 2
            canvas.drawRoundRect(x0, y, x0 + w, y + h, h / 2, h / 2, track)
            val bead = 40f * d
            val phase = (clock * 2) % 1f
            val at = -bead + (w + bead) * phase
            val from = max(0f, at)
            val to = min(w, at + bead)
            if (to > from) canvas.drawRoundRect(x0 + from, y, x0 + to, y + h, h / 2, h / 2, glint)
        }

        private fun mix(c: Float) = (255 * (0.6f + 0.4f * c.coerceIn(0f, 1f))).toInt()
        private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        private fun brandRes(key: String?): Int? = when (key) {
            "playstation" -> R.drawable.brand_playstation
            "xbox" -> R.drawable.brand_xbox
            "wii" -> R.drawable.brand_wii
            else -> null
        }
    }

    private companion object {
        const val FADE_IN_MS = 200L
        const val FADE_OUT_MS = 340L
        const val MAX_MS = 34_000L
        const val ERROR_MS = 5_000L
        const val CYCLE_MS = 2600L
        const val PANEL_DP = 300f
        const val ICON_DP = 118f
    }
}
