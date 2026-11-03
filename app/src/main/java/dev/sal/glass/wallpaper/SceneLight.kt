package dev.sal.glass.wallpaper

import android.graphics.Bitmap

/**
 * Where a photo's light comes from, for the glass: the brightest patch of the frosted thumb (the
 * sun, a bright sky, a lit window, street lamps) in photo coordinates 0..1, and its colour. The
 * glass hangs its lamp there, so a sunset on the left lights the tiles from the left with a warm
 * glint, and a night city lights them from wherever its lamps are. About a millisecond per photo
 * on the 240x135 thumb; nothing is pre-rendered or stored.
 */
class SceneLight(val x: Float, val y: Float, val r: Float, val g: Float, val b: Float) {
    companion object {
        /** No photo, or a flat one: a white lamp above the top edge, a little left of centre. */
        val DEFAULT = SceneLight(0.40f, -0.35f, 1f, 1f, 1f)

        fun of(thumb: Bitmap): SceneLight {
            val w = thumb.width; val h = thumb.height
            if (w < 16 || h < 16) return DEFAULT
            val px = IntArray(w * h); thumb.getPixels(px, 0, w, 0, 0, w, h)
            val luma = FloatArray(w * h) { val c = px[it]; (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f }
            // Box-smoothed over 11x11 through an integral image, so a single hot pixel cannot win.
            val stride = w + 1
            val sat = DoubleArray(stride * (h + 1))
            for (y in 1..h) {
                var row = 0.0
                for (x in 1..w) { row += luma[(y - 1) * w + (x - 1)]; sat[y * stride + x] = sat[(y - 1) * stride + x] + row }
            }
            fun sum(x0: Int, y0: Int, x1: Int, y1: Int) = sat[y1 * stride + x1] - sat[y0 * stride + x1] - sat[y1 * stride + x0] + sat[y0 * stride + x0]
            val r = 5
            var best = -1.0; var bx = 0; var by = 0
            for (y in 0 until h) for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val y0 = maxOf(0, y - r); val x1 = minOf(w, x + r + 1); val y1 = minOf(h, y + r + 1)
                val v = sum(x0, y0, x1, y1) / ((x1 - x0) * (y1 - y0))
                if (v > best) { best = v; bx = x; by = y }
            }
            val mean = sum(0, 0, w, h) / (w * h)
            if (best - mean < 0.08) return DEFAULT   // flat haze or an even night: no light to speak of
            // The patch's colour, normalised so its brightest channel is 1.
            var sr = 0L; var sg = 0L; var sb = 0L
            for (y in maxOf(0, by - r)..minOf(h - 1, by + r)) for (x in maxOf(0, bx - r)..minOf(w - 1, bx + r)) {
                val c = px[y * w + x]; sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF
            }
            val m = maxOf(sr, sg, sb).coerceAtLeast(1L).toFloat()
            return SceneLight((bx + 0.5f) / w, (by + 0.5f) / h, sr / m, sg / m, sb / m)
        }
    }
}
