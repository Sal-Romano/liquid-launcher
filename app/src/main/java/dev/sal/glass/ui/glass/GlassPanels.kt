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
)

object GlassPanels {
    private val panels = LinkedHashMap<Any, GlassPanel>()
    /** Bumped on every change; the renderer compares it to decide whether to redraw. */
    @Volatile var version = 0L; private set
    @Volatile var onChanged: (() -> Unit)? = null

    @Synchronized
    fun update(id: Any, rect: Rect, cornerPx: Float, kind: GlassVariant, lift: Float, alpha: Float) {
        val p = panels[id]
        if (p == null) {
            panels[id] = GlassPanel(id, rect, cornerPx, kind, lift, alpha)
        } else {
            if (p.rect == rect && p.cornerPx == cornerPx && p.kind == kind && p.lift == lift && p.alpha == alpha) return
            p.rect = rect; p.cornerPx = cornerPx; p.kind = kind; p.lift = lift; p.alpha = alpha
        }
        version++
        onChanged?.invoke()
    }

    @Synchronized
    fun remove(id: Any) {
        if (panels.remove(id) != null) { version++; onChanged?.invoke() }
    }

    @Synchronized
    fun snapshot(): List<GlassPanel> = panels.values.toList()
}
