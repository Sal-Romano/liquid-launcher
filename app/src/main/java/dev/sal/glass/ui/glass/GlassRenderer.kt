package dev.sal.glass.ui.glass

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
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

    private class Pending(val sharp: Bitmap?, val deep: Bitmap?)
    @Volatile private var pendingA: Pending? = null
    @Volatile private var pendingB: Pending? = null
    private var lastA: Pending? = null
    private var lastB: Pending? = null
    @Volatile var mix = 0f
    @Volatile var zoom = 1f
    @Volatile var dark = 0f
    @Volatile var refraction = true

    fun setPhotoA(sharp: Bitmap?, deep: Bitmap?) { pendingA = Pending(sharp, deep); requestRender() }
    fun setPhotoB(sharp: Bitmap?, deep: Bitmap?) { pendingB = Pending(sharp, deep); requestRender() }

    fun onSurfaceCreated() {
        program = link(GlassShader.VERTEX, GlassShader.FRAGMENT)
        for (name in listOf("uSharpA", "uDeepA", "uSharpB", "uDeepB", "uMix", "uZoom", "uRes", "uRect", "uRadius", "uBevel", "uThick", "uLift", "uAlpha", "uFrostMix", "uDark")) {
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

    fun onDrawFrame() {
        pendingA?.let { p -> p.sharp?.let { upload(tex[0], it) }; p.deep?.let { upload(tex[1], it) }; lastA = p; pendingA = null }
        pendingB?.let { p -> p.sharp?.let { upload(tex[2], it) }; p.deep?.let { upload(tex[3], it) }; lastB = p; pendingB = null }
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
            // Tiles: a clear lens ring around a deeply frosted centre, so icons stay legible on busy
            // photos. Sheets: the lens lives at the edge only; the interior is near-opaque frost.
            val (bevel, thick, frost) = when (p.kind) {
                GlassVariant.Tile -> Triple(minOf(r.height * 0.20f, 20f), 8f, 0.94f)
                GlassVariant.Pill -> Triple(minOf(r.height * 0.3f, 12f), 4f, 0.95f)
                GlassVariant.Sheet -> Triple(18f, 4f, 0.98f)
            }
            GLES30.glUniform1f(u["uBevel"]!!, if (refraction) bevel else 1f)
            GLES30.glUniform1f(u["uThick"]!!, if (refraction) thick else 0f)
            GLES30.glUniform1f(u["uLift"]!!, p.lift)
            GLES30.glUniform1f(u["uAlpha"]!!, p.alpha)
            GLES30.glUniform1f(u["uFrostMix"]!!, frost)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisableVertexAttribArray(aPos)
    }

    private fun upload(t: Int, bmp: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // The refracted sample runs past the edge; clamp so it never wraps.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
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
