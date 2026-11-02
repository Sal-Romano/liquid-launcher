package dev.sal.glass.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The glass surface as a TextureView: it composites inside the view hierarchy like any other view,
 * so Compose decides what is under it (the wallpaper) and over it (icons, text). A SurfaceView
 * would sit outside the hierarchy and, on Fire OS, above the window. Its own EGL thread renders
 * on demand.
 */
class GlassTextureView(context: Context, registry: GlassPanels) : TextureView(context), TextureView.SurfaceTextureListener {
    private val renderer = GlassRenderer(registry) { requestRender() }
    private var thread: RenderThread? = null
    private val dirty = AtomicBoolean(true)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setPhotoA(sharp: Bitmap?, deep: Bitmap?) { renderer.setPhotoA(sharp, deep) }
    fun setPhotoB(sharp: Bitmap?, deep: Bitmap?) { renderer.setPhotoB(sharp, deep) }
    fun setMix(m: Float) { if (renderer.mix != m) { renderer.mix = m; requestRender() } }
    fun setZoom(z: Float) { if (renderer.zoom != z) { renderer.zoom = z; requestRender() } }
    fun setDark(d: Float) { renderer.dark = d; requestRender() }
    /** Frosted-only mode: no bevel, no bend. For the TV if the refraction costs too much. */
    fun setRefraction(on: Boolean) { renderer.refraction = on; requestRender() }

    fun requestRender() {
        dirty.set(true)
        thread?.wake()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        thread = RenderThread(surface, width, height).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.quit(); thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private inner class RenderThread(private val texture: SurfaceTexture, @Volatile private var w: Int, @Volatile private var h: Int) : Thread("GlassGL") {
        private val lock = Object()
        @Volatile private var running = true
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun wake() = synchronized(lock) { lock.notifyAll() }
        fun resize(width: Int, height: Int) { w = width; h = height; requestRender() }
        fun quit() { running = false; wake(); join(1000) }

        override fun run() {
            if (!initEgl()) return
            renderer.onSurfaceCreated()
            var lastW = -1; var lastH = -1
            while (running) {
                if (!dirty.getAndSet(false)) {
                    synchronized(lock) { if (running && !dirty.get()) lock.wait() }
                    continue
                }
                if (w != lastW || h != lastH) { renderer.onSurfaceChanged(w, h); lastW = w; lastH = h }
                renderer.onDrawFrame()
                EGL14.eglSwapBuffers(display, eglSurface)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) { Log.e("Glass", "eglInitialize failed"); return false }
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) { Log.e("Glass", "no EGL config"); return false }
            val config = configs[0]!!
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { Log.e("Glass", "eglMakeCurrent failed"); return false }
            return true
        }
    }
}
