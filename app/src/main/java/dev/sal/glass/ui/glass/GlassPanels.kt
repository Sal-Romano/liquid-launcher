package dev.sal.glass.ui.glass

import androidx.compose.ui.geometry.Rect

/**
 * Where every glass panel of one layer is on screen this frame, in px, transformed bounds
 * included. Composables post their rect here; the GL layer reads the list when it draws. Compose
 * owns the icons and text on top; GL owns the glass itself.
 */
class GlassPanel(
    val id: Any,
    @Volatile var rect: Rect,
    @Volatile var cornerPx: Float,
    @Volatile var kind: GlassVariant,
    /** 0..1: focus lift, brightens the rim and deepens the refraction. */
    @Volatile var lift: Float,
    /** 0..1: fade in/out, so a hiding dock does not pop. */
    @Volatile var alpha: Float = 1f,
    /** Last [GlassPanels.frame] this panel was reported in; stale panels are dropped. */
    @Volatile var seenAt: Long = 0L,
    /** Rotation about the slab's horizontal and vertical axes, Compose degrees (rotationX/Y). */
    @Volatile var tiltX: Float = 0f,
    @Volatile var tiltY: Float = 0f,
    /** The camera distance Compose projects that rotation with, in px. */
    @Volatile var cameraPx: Float = 0f,
)

/** Which GL pass a panel belongs to: under the home chrome, or above it (sheets, popovers, toasts). */
enum class GlassLayerId { Home, Overlay }

class GlassPanels {
    private val panels = LinkedHashMap<Any, GlassPanel>()
    /** Bumped on every change; the renderer compares it to decide whether to redraw. */
    @Volatile var version = 0L; private set
    @Volatile var onChanged: (() -> Unit)? = null
    /**
     * Monotonic "report generation". A composable that is leaving can still report a position
     * after its dispose ran, which would resurrect it; so instead of trusting dispose order, a
     * panel that has not been reported for [STALE_AFTER] generations is dropped at draw time.
     */
    @Volatile var frame = 0L; private set

    @Synchronized
    fun update(
        id: Any, rect: Rect, cornerPx: Float, kind: GlassVariant, lift: Float, alpha: Float,
        tiltX: Float = 0f, tiltY: Float = 0f, cameraPx: Float = 0f,
    ) {
        val p = panels[id]
        if (p == null) {
            panels[id] = GlassPanel(id, rect, cornerPx, kind, lift, alpha, frame, tiltX, tiltY, cameraPx)
        } else {
            p.seenAt = frame
            if (p.kind == kind && near(p.cornerPx, cornerPx) && near(p.rect, rect) &&
                near(p.lift, lift) && near(p.alpha, alpha) && near(p.tiltX, tiltX) && near(p.tiltY, tiltY)
            ) return
            p.rect = rect; p.cornerPx = cornerPx; p.kind = kind; p.lift = lift; p.alpha = alpha
            p.tiltX = tiltX; p.tiltY = tiltY; p.cameraPx = cameraPx
        }
        version++
        onChanged?.invoke()
    }

    private fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.01f
    private fun near(a: Rect, b: Rect) =
        kotlin.math.abs(a.left - b.left) < 0.5f && kotlin.math.abs(a.top - b.top) < 0.5f &&
            kotlin.math.abs(a.right - b.right) < 0.5f && kotlin.math.abs(a.bottom - b.bottom) < 0.5f

    @Synchronized
    fun remove(id: Any) {
        if (panels.remove(id) != null) { version++; onChanged?.invoke() }
    }

    /** Called once per Compose frame by the host; advances the generation and prunes stale panels. */
    @Synchronized
    fun tick() {
        frame++
        val it = panels.values.iterator()
        var dropped = false
        while (it.hasNext()) {
            if (frame - it.next().seenAt > STALE_AFTER) { it.remove(); dropped = true }
        }
        if (dropped) { version++; onChanged?.invoke() }
    }

    @Synchronized
    fun snapshot(): List<GlassPanel> = panels.values.filter { frame - it.seenAt <= STALE_AFTER }

    companion object {
        private const val STALE_AFTER = 3
        val home = GlassPanels()
        val overlay = GlassPanels()
        fun of(layer: GlassLayerId) = if (layer == GlassLayerId.Home) home else overlay
        fun tickAll() { home.tick(); overlay.tick() }
        /** Ask both GL layers to draw a frame, without touching any Compose state. */
        fun requestRenderAll() { home.onChanged?.invoke(); overlay.onChanged?.invoke() }
    }
}
