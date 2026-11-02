package dev.sal.glass.ui.glass

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws every registered glass panel with [GlassShader] into [GlassTextureView]'s surface.
 * Textures are uploaded once per wallpaper change; frames are rendered on demand when a panel
 * moves, fades, or the wallpaper swaps.
 */
class GlassRenderer(private val requestRender: () -> Unit) {
    private var program = 0
    private var quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vbo = 0
    private var texSharp = 0
    private var texFrost = 0
    private var width = 1
    private var height = 1

    // Pending uploads, handed over from the UI thread.
    @Volatile private var pendingSharp: Bitmap? = null
    @Volatile private var pendingFrost: Bitmap? = null
    @Volatile var dark = 0f
    @Volatile var refraction = true

    private val u = HashMap<String, Int>()

    fun setWallpaper(sharp: Bitmap?, frost: Bitmap?) {
        pendingSharp = sharp; pendingFrost = frost
        requestRender()
    }

    fun onSurfaceCreated() {
        program = link(GlassShader.VERTEX, GlassShader.FRAGMENT)
        for (name in listOf("uSharp", "uFrost", "uRes", "uRect", "uRadius", "uBevel", "uThick", "uLift", "uAlpha", "uFrostMix", "uDark")) {
            u[name] = GLES30.glGetUniformLocation(program, name)
        }
        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        texSharp = ids[0]; texFrost = ids[1]
        val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); vbo = b[0]
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Straight alpha out of the shader.
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Re-upload after a context loss.
        pendingSharp = pendingSharp ?: lastSharp; pendingFrost = pendingFrost ?: lastFrost
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
    }

    private var lastSharp: Bitmap? = null
    private var lastFrost: Bitmap? = null

    fun onDrawFrame() {
        pendingSharp?.let { upload(texSharp, it); lastSharp = it; pendingSharp = null }
        pendingFrost?.let { upload(texFrost, it); lastFrost = it; pendingFrost = null }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (lastSharp == null) return
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSharp); GLES30.glUniform1i(u["uSharp"]!!, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFrost); GLES30.glUniform1i(u["uFrost"]!!, 1)
        GLES30.glUniform2f(u["uRes"]!!, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(u["uDark"]!!, dark)

        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(aPos)
        for (p in GlassPanels.snapshot()) {
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
            // Tiles: a clear bevel ring around a well-frosted centre, so icons stay legible on busy
            // photos. Sheets: the lens lives at the edge only; the interior is near-opaque frost.
            val (bevel, thick, frost) = when (p.kind) {
                GlassVariant.Tile -> Triple(minOf(r.height * 0.20f, 20f), 8f, 0.90f)
                GlassVariant.Pill -> Triple(minOf(r.height * 0.3f, 12f), 4f, 0.92f)
                GlassVariant.Sheet -> Triple(18f, 4f, 0.97f)
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

    private fun upload(tex: Int, bmp: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
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
