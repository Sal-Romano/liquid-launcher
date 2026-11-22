package dev.sal.glass.volume

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/** The running curtain, and where it gets the launcher's photo. */
object HomeCurtainHost {
    @Volatile var curtain: HomeCurtain? = null
    /** The photo on the home screen right now and its slow zoom. */
    @Volatile var photo: (() -> Pair<Bitmap, Float>?)? = null
}

/**
 * Home from an HDMI input or an app: Fire OS first brings up Amazon's launcher, Home on Fire
 * redirects to ours, and ours only resumes once Amazon's has timed out pausing, about 600 ms of
 * black (measured on the Hisense, 2026-11-21). So the moment Home is pressed this puts the
 * launcher's own photo up, in the same place and at the same zoom, and takes it away once the
 * launcher has drawn and the TV has closed the input. Only for Home from an HDMI input, and the
 * window exists only for that moment: an idle overlay would sit over games.
 */
class HomeCurtain(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var shownAt = 0L
    private val lift = Runnable {
        val v = view ?: return@Runnable
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (view === v) remove() }.start()
    }

    fun show(why: String) {
        main.post {
            main.removeCallbacks(lift)
            if (view == null) {
                val (image, zoom) = HomeCurtainHost.photo?.invoke()?.takeUnless { it.first.isRecycled }
                    ?: run { Log.w("Glass", "home curtain: no photo to show"); return@post }
                val v = PhotoView(context, image, zoom)
                runCatching { wm.addView(v, params()) }.onFailure { Log.w("Glass", "home curtain failed", it); return@post }
                view = v
                shownAt = SystemClock.uptimeMillis()
                Log.i("Glass", "home curtain up ($why)")
            }
            main.postDelayed(lift, MAX_MS)
        }
    }

    /** The launcher is back in front: give it a moment to draw, then uncover it. */
    fun reveal() {
        main.post {
            if (view == null) return@post
            main.removeCallbacks(lift)
            // The TV is still closing the HDMI picture for over a second after Home, and that blanks
            // the screen: stay up past it even when the launcher has already drawn.
            val wait = max(DRAW_MS, MIN_UP_MS - (SystemClock.uptimeMillis() - shownAt))
            main.postDelayed(lift, wait)
            Log.i("Glass", "home curtain lifting in $wait ms")
        }
    }

    fun remove() {
        main.removeCallbacks(lift)
        view?.let { v -> v.animate().cancel(); runCatching { wm.removeView(v) } }
        view = null
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { windowAnimations = 0 }

    /** The photo as the wallpaper layer draws it: centre-cropped, then scaled by the slow zoom. */
    private class PhotoView(context: Context, private val image: Bitmap, private val zoom: Float) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val place = Matrix()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            if (image.isRecycled) return
            val s = max(width / image.width.toFloat(), height / image.height.toFloat()) * zoom
            place.setScale(s, s)
            place.postTranslate((width - image.width * s) / 2, (height - image.height * s) / 2)
            canvas.drawBitmap(image, place, paint)
        }
    }

    private companion object {
        const val DRAW_MS = 120L
        const val MIN_UP_MS = 1_350L
        const val FADE_MS = 240L
        const val MAX_MS = 3_200L
    }
}
