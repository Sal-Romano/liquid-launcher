package dev.sal.glass.ui.glass

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import dev.sal.glass.wallpaper.SceneLight
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws every registered glass panel of one layer with [GlassShader] into [GlassTextureView]'s
 * surface. Two texture pairs (outgoing/incoming photo, each sharp + deep blur) let the glass
 * crossfade with the wallpaper. Textures are uploaded when a frame lands; frames are rendered on
 * demand when a panel moves, fades, or the backdrop changes.
 */
class GlassRenderer(private val registry: GlassPanels, private val requestRender: () -> Unit) {
    private var program = 0
    private var quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vbo = 0
    private val tex = IntArray(4)   // sharpA, deepA, sharpB, deepB
    private var width = 1
    private var height = 1
    private val u = HashMap<String, Int>()

    private class Pending(val sharp: Bitmap?, val deep: Bitmap?, val light: SceneLight?)
    @Volatile private var pendingA: Pending? = null
    @Volatile private var pendingB: Pending? = null
    private var lastA: Pending? = null
    private var lastB: Pending? = null
    @Volatile var mix = 0f
    @Volatile var zoom = 1f
    @Volatile var dark = 0f
    @Volatile var refraction = true

    fun setPhotoA(sharp: Bitmap?, deep: Bitmap?, light: SceneLight?) { pendingA = Pending(sharp, deep, light); requestRender() }
    fun setPhotoB(sharp: Bitmap?, deep: Bitmap?, light: SceneLight?) { pendingB = Pending(sharp, deep, light); requestRender() }

    fun onSurfaceCreated() {
        program = link(GlassShader.VERTEX, GlassShader.FRAGMENT)
        for (name in listOf("uSharpA", "uDeepA", "uSharpB", "uDeepB", "uMix", "uZoom", "uRes", "uRect", "uRadius", "uBevel", "uThick", "uLift", "uAlpha", "uLod", "uDeepMix", "uTint", "uDark", "uMag", "uLamp", "uLampCol", "uTilt", "uCam")) {
            u[name] = GLES30.glGetUniformLocation(program, name)
        }
        GLES30.glGenTextures(4, tex, 0)
        val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); vbo = b[0]
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Re-upload after a context loss.
        pendingA = pendingA ?: lastA; pendingB = pendingB ?: lastB
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
    }

    // Frame-time statistics, logged once a second while frames are drawn (debug builds only).
    private var statStart = 0L
    private var statFrames = 0
    private var statNanos = 0L
    private var statWorst = 0L

    fun onDrawFrame() {
        val t0 = System.nanoTime()
        drawFrame()
        if (dev.sal.glass.BuildConfig.DEBUG) {
            val dt = System.nanoTime() - t0
            statFrames++; statNanos += dt; if (dt > statWorst) statWorst = dt
            val now = System.currentTimeMillis()
            if (statStart == 0L) statStart = now
            if (now - statStart >= 1000) {
                val panels = registry.snapshot().count { it.alpha > 0.005f }
                // Info level: Fire OS drops debug-level lines from third-party apps.
                Log.i("Glass", "glass frames: $statFrames in ${now - statStart} ms, avg ${statNanos / statFrames / 1000} us, worst ${statWorst / 1000} us, $panels panels")
                statStart = now; statFrames = 0; statNanos = 0L; statWorst = 0L
            }
        }
    }

    private fun drawFrame() {
        pendingA?.let { p -> p.sharp?.let { upload(tex[0], it, mip = true) }; p.deep?.let { upload(tex[1], it, mip = false) }; lastA = p; pendingA = null }
        pendingB?.let { p -> p.sharp?.let { upload(tex[2], it, mip = true) }; p.deep?.let { upload(tex[3], it, mip = false) }; lastB = p; pendingB = null }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (lastA?.sharp == null) return
        GLES30.glUseProgram(program)
        for ((i, name) in listOf("uSharpA", "uDeepA", "uSharpB", "uDeepB").withIndex()) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            // If B was never uploaded, bind A's textures so uMix > 0 cannot show black.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (i >= 2 && lastB?.sharp == null) tex[i - 2] else tex[i])
            GLES30.glUniform1i(u[name]!!, i)
        }
        GLES30.glUniform1f(u["uMix"]!!, if (lastB?.sharp == null) 0f else mix)
        GLES30.glUniform1f(u["uZoom"]!!, zoom)
        GLES30.glUniform2f(u["uRes"]!!, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(u["uDark"]!!, dark)
        // The lamp hangs where the photo's light is, and crosses over with the photos.
        val hasB = lastB?.sharp != null
        val la = lastA?.light ?: SceneLight.DEFAULT
        val lb = if (hasB) lastB?.light ?: SceneLight.DEFAULT else la
        val lm = if (hasB) mix else 0f
        fun lerp(a: Float, b: Float) = a + (b - a) * lm
        GLES30.glUniform2f(u["uLamp"]!!, lerp(la.x, lb.x), lerp(la.y, lb.y))
        GLES30.glUniform3f(u["uLampCol"]!!, lerp(la.r, lb.r), lerp(la.g, lb.g), lerp(la.b, lb.b))

        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(aPos)
        for (p in registry.snapshot()) {
            if (p.alpha <= 0.005f) continue
            val r = p.rect
            val margin = 40f + 20f * p.lift   // room for the shadow
            quad.clear()
            quad.put(floatArrayOf(r.left - margin, r.top - margin, r.right + margin, r.top - margin, r.left - margin, r.bottom + margin, r.right + margin, r.bottom + margin))
            quad.flip()
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, quad, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glUniform4f(u["uRect"]!!, r.center.x, r.center.y, r.width / 2f, r.height / 2f)
            GLES30.glUniform1f(u["uRadius"]!!, p.cornerPx)
            // Tiles: a wide, thick lens around a clear, lightly softened middle, so the photo reads
            // through the slab. Pills: a little more frost under the label. Sheets: the deep blur,
            // they carry text. Frosted-only mode drops the bevel and the bend and frosts the middle.
            val m = when (p.kind) {
                GlassVariant.Tile -> Material(bevel = minOf(r.height * 0.19f, 36f), thick = 14f, lod = 2.6f, deep = 0f, tint = 0.16f)
                GlassVariant.Pill -> Material(bevel = minOf(r.height * 0.35f, 16f), thick = 6f, lod = 3.2f, deep = 0f, tint = 0.24f)
                GlassVariant.Sheet -> Material(bevel = 18f, thick = 6f, lod = 4f, deep = 1f, tint = 0.34f)
                GlassVariant.Card -> Material(bevel = minOf(r.height * 0.16f, 40f), thick = 12f, lod = 3.4f, deep = 0.35f, tint = 0.22f)
            }
            GLES30.glUniform1f(u["uBevel"]!!, if (refraction) m.bevel else 1f)
            GLES30.glUniform1f(u["uThick"]!!, if (refraction) m.thick else 0f)
            GLES30.glUniform1f(u["uLift"]!!, p.lift)
            GLES30.glUniform1f(u["uAlpha"]!!, p.alpha)
            GLES30.glUniform1f(u["uLod"]!!, if (refraction) m.lod else 5f)
            GLES30.glUniform1f(u["uDeepMix"]!!, m.deep)
            GLES30.glUniform1f(u["uTint"]!!, if (refraction) m.tint else m.tint + 0.1f)
            // The lens thickens a little as the tile lifts: the slab magnifies more and bends more.
            GLES30.glUniform1f(u["uMag"]!!, if (refraction) 1.04f + 0.04f * p.lift else 1f)
            // The slab's 3D rotation, as Compose applies it to the icon inside: Compose degrees to
            // radians, with the shader's sign convention (TILT_SIGN, calibrated against a rotated
            // layer on the emulator), and the camera Compose projects with.
            GLES30.glUniform2f(u["uTilt"]!!, Math.toRadians(p.tiltX.toDouble()).toFloat() * TILT_SIGN_X, Math.toRadians(p.tiltY.toDouble()).toFloat() * TILT_SIGN_Y)
            GLES30.glUniform1f(u["uCam"]!!, if (p.cameraPx > 0f) p.cameraPx else 576f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisableVertexAttribArray(aPos)
    }

    /** Per-kind glass recipe; see [onDrawFrame]. */
    private class Material(val bevel: Float, val thick: Float, val lod: Float, val deep: Float, val tint: Float)

    private companion object {
        /** Compose rotationX/rotationY sign → the shader's (positive = top edge / right edge away). */
        const val TILT_SIGN_X = 1f
        const val TILT_SIGN_Y = 1f
    }

    /**
     * [mip]: the sharp photos get a mipmap chain, which is what the shader samples for the slab's
     * soft middle (a hardware blur that costs nothing per frame; the chain is built once per
     * photo change, about a third more texture memory).
     */
    private fun upload(t: Int, bmp: Bitmap, mip: Boolean) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (mip) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // The refracted sample runs past the edge; clamp so it never wraps.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e("Glass", "shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Glass", "program link failed: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }
}
