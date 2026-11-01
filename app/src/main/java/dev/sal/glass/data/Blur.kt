package dev.sal.glass.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * CPU blur for the glass panels. Android 11 has no RenderEffect, so each wallpaper gets one small
 * pre-blurred copy when it loads; the panels sample that copy behind themselves.
 *
 * Strategy: downscale by [DOWNSCALE] (1920 → 240 wide), then a few passes of a cheap box blur
 * (box ≈ Gaussian after 3 passes), then let the GPU upscale with bilinear filtering. The result is
 * a wide, soft blur, which is what frosted glass looks like; the detail lost to the downscale was
 * going to be blurred away anyway.
 */
object Blur {
    private const val DOWNSCALE = 8

    fun frosted(src: Bitmap, radius: Int = 6, passes: Int = 3): Bitmap {
        val w = (src.width / DOWNSCALE).coerceAtLeast(1)
        val h = (src.height / DOWNSCALE).coerceAtLeast(1)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
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
