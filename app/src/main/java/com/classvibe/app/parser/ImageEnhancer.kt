package com.classvibe.app.parser

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a screenshot or photo into a clean, high-contrast grayscale image for OCR:
 *  1. grayscale, and invert dark-mode timetables
 *  2. shadow / uneven-light removal (flat-field: divide by the estimated paper brightness)
 *  3. automatic contrast stretch (2nd–98th percentile)
 */
object ImageEnhancer {

    fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(px.size)
        var sum = 0L
        for (i in px.indices) {
            val c = px[i]
            val l = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            lum[i] = l
            sum += l
        }
        if (sum / px.size < 110) for (i in lum.indices) lum[i] = 255 - lum[i] // dark mode → light background

        removeShadows(lum, w, h)

        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        fun percentile(p: Double): Int {
            val target = (lum.size * p).toLong()
            var acc = 0L
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
        val lo = percentile(0.02)
        val hi = max(lo + 1, percentile(0.98))
        val range = (hi - lo).toFloat()
        for (i in px.indices) {
            val v = ((lum[i] - lo) * 255f / range).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Estimates the paper brightness per block (block maximum, smoothed) and divides by it,
     * so shadows and gradients from phone photos disappear. Screenshots with an even
     * background are left untouched.
     */
    fun removeShadows(lum: IntArray, w: Int, h: Int) {
        val bs = max(16, min(w, h) / 30)
        val gw = (w + bs - 1) / bs
        val gh = (h + bs - 1) / bs
        val grid = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var m = 0
            val y1 = min(h, (gy + 1) * bs)
            val x1 = min(w, (gx + 1) * bs)
            var y = gy * bs
            while (y < y1) {
                var x = gx * bs
                while (x < x1) { val v = lum[y * w + x]; if (v > m) m = v; x += 2 }
                y += 2
            }
            grid[gy * gw + gx] = m.toFloat()
        }
        // Smooth the background estimate twice (3×3 box).
        repeat(2) {
            val copy = grid.copyOf()
            for (gy in 0 until gh) for (gx in 0 until gw) {
                var s = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = gy + dy; val xx = gx + dx
                    if (yy in 0 until gh && xx in 0 until gw) { s += copy[yy * gw + xx]; n++ }
                }
                grid[gy * gw + gx] = s / n
            }
        }
        val minBg = grid.minOrNull() ?: return
        val maxBg = grid.maxOrNull() ?: return
        if (maxBg - minBg < 25f || maxBg < 40f) return // even lighting (typical screenshot)

        for (y in 0 until h) {
            val fy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(gh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(gw - 1, x0 + 1); val tx = fx - x0
                val top = grid[y0 * gw + x0] * (1 - tx) + grid[y0 * gw + x1] * tx
                val bottom = grid[y1 * gw + x0] * (1 - tx) + grid[y1 * gw + x1] * tx
                val bg = max(20f, top * (1 - ty) + bottom * ty)
                lum[y * w + x] = (lum[y * w + x] * 255f / bg).roundToInt().coerceIn(0, 255)
            }
        }
    }
}
