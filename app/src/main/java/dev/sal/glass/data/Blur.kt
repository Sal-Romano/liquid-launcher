package dev.sal.glass.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * CPU blurs for the glass, pre-rendered once per wallpaper on a background thread. Android 11 has
 * no RenderEffect, and the GPU shader samples these as textures.
 *
 * Two grades:
 *  - [frosted]: 240x135, a wide soft blur. Cheap (57 ms on the TV). Used for luma sampling.
 *  - [deep]: 480x270 with a much wider kernel. This is the frosted-glass interior: a deep,
 *    smooth blur that keeps the photo's colour fields but none of its detail, which is what a
 *    thick slab of frosted glass actually does to what is behind it. ~400 ms on the TV, once
 *    per photo change, off the main thread.
 *
 * Box blur run 3 times approximates a Gaussian; downscaling first makes the kernel effectively
 * DOWNSCALE times wider in screen pixels for the same cost.
 */
object Blur {
    fun frosted(src: Bitmap): Bitmap = run(src, downscale = 8, radius = 6, passes = 3)

    fun deep(src: Bitmap): Bitmap = run(src, downscale = 4, radius = 14, passes = 3)

    private fun run(src: Bitmap, downscale: Int, radius: Int, passes: Int): Bitmap {
        val w = (src.width / downscale).coerceAtLeast(1)
        val h = (src.height / downscale).coerceAtLeast(1)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            boxBlurH(px, tmp, w, h, radius)
            boxBlurV(tmp, px, w, h, radius)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    // Sliding-window box blur, horizontal then vertical, per channel, edge-clamped.
    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) { val c = src[row + i.coerceIn(0, w - 1)]; a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24); rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF); b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) { val c = src[i.coerceIn(0, h - 1) * w + x]; a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24) - (out ushr 24); rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF); b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
