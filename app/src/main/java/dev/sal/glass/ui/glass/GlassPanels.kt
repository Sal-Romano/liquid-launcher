package dev.sal.glass.ui.glass

import androidx.compose.ui.geometry.Rect

/**
 * Where every glass panel is on screen this frame, in px. Composables post their rect here from
 * onGloballyPositioned; the GL layer reads the list when it draws. Compose owns the icons and text
 * on top; GL owns the glass itself.
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
)

object GlassPanels {
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
    private const val STALE_AFTER = 3

    @Synchronized
    fun update(id: Any, rect: Rect, cornerPx: Float, kind: GlassVariant, lift: Float, alpha: Float) {
        val p = panels[id]
        if (p == null) {
            panels[id] = GlassPanel(id, rect, cornerPx, kind, lift, alpha, frame)
        } else {
            p.seenAt = frame
            if (p.kind == kind && p.cornerPx == cornerPx && near(p.rect, rect) &&
                kotlin.math.abs(p.lift - lift) < 0.01f && kotlin.math.abs(p.alpha - alpha) < 0.01f
            ) return
            p.rect = rect; p.cornerPx = cornerPx; p.kind = kind; p.lift = lift; p.alpha = alpha
        }
        version++
        onChanged?.invoke()
    }

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
}
