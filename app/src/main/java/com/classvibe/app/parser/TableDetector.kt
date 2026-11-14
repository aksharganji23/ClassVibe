package com.classvibe.app.parser

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the ruling lines of a bordered table on an enhanced (grayscale) bitmap,
 * so text can be assigned to exact cells instead of guessed from positions.
 */
class TableDetector(private val bitmap: Bitmap) {

    data class Grid(val xs: List<Int>, val ys: List<Int>)

    private val scale: Float = min(1f, 1400f / max(bitmap.width, bitmap.height))
    private val w = max(1, (bitmap.width * scale).roundToInt())
    private val h = max(1, (bitmap.height * scale).roundToInt())
    private val dark: BooleanArray

    init {
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        dark = BooleanArray(px.size) { i -> (px[i] and 0xFF) < 150 }
    }

    private fun isDark(x: Int, y: Int) = dark[y * w + x]

    /** Returns null for borderless tables (caller falls back to text-gap analysis). */
    fun detect(): Grid? {
        val rowRuns = IntArray(h) { y -> longestRun(y, horizontal = true) }
        val colRuns = IntArray(w) { x -> longestRun(x, horizontal = false) }
        val ys = groupLines(rowRuns, (w * 0.30).toInt())
        val xs = groupLines(colRuns, (h * 0.30).toInt())
        if (ys.size < 3 || xs.size < 3) return null
        return Grid(xs.map { (it / scale).roundToInt() }, ys.map { (it / scale).roundToInt() })
    }

    /** Longest continuous dark run along a row/column (tolerating 2-px gaps from anti-aliasing). */
    private fun longestRun(index: Int, horizontal: Boolean): Int {
        val len = if (horizontal) w else h
        var best = 0
        var cur = 0
        var gap = 0
        for (i in 0 until len) {
            val d = if (horizontal) isDark(i, index) else isDark(index, i)
            if (d) {
                cur += 1 + gap; gap = 0
                if (cur > best) best = cur
            } else if (cur > 0 && gap < 2) {
                gap++
            } else {
                cur = 0; gap = 0
            }
        }
        return best
    }

    /** Collapses neighbouring line pixels into one coordinate; thick filled bands give both edges. */
    private fun groupLines(runs: IntArray, threshold: Int): List<Int> {
        val result = mutableListOf<Int>()
        var start = -1
        for (i in 0..runs.size) {
            val on = i < runs.size && runs[i] >= threshold
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                val end = i - 1
                if (end - start > 12) { result += start; result += end } else result += (start + end) / 2
                start = -1
            }
        }
        // Drop lines that are too close together (double borders).
        val merged = mutableListOf<Int>()
        for (v in result.sorted()) {
            if (merged.isEmpty() || v - merged.last() > 6) merged += v
        }
        return merged
    }

    /** True if a horizontal ruling line exists at y between x0..x1 (used to detect vertically merged cells). */
    fun hasHorizontalLine(yFull: Int, x0Full: Int, x1Full: Int): Boolean {
        val y = (yFull * scale).roundToInt()
        val x0 = (x0Full * scale).roundToInt().coerceIn(0, w - 1)
        val x1 = (x1Full * scale).roundToInt().coerceIn(0, w - 1)
        if (x1 - x0 < 4) return true
        var bestFraction = 0.0
        for (dy in -3..3) {
            val yy = (y + dy).coerceIn(0, h - 1)
            var count = 0
            for (x in x0..x1) if (isDark(x, yy)) count++
            bestFraction = max(bestFraction, count.toDouble() / (x1 - x0 + 1))
        }
        return bestFraction > 0.6
    }

    /** Outer border of the detected table (for auto-crop), or null. */
    fun tableBounds(): android.graphics.Rect? {
        val g = detect() ?: return null
        return android.graphics.Rect(g.xs.first(), g.ys.first(), g.xs.last(), g.ys.last())
    }

    /** True if a vertical ruling line exists at x between y0..y1 (used to detect merged cells). */
    fun hasVerticalLine(xFull: Int, y0Full: Int, y1Full: Int): Boolean {
        val x = (xFull * scale).roundToInt()
        val y0 = (y0Full * scale).roundToInt().coerceIn(0, h - 1)
        val y1 = (y1Full * scale).roundToInt().coerceIn(0, h - 1)
        if (y1 - y0 < 4) return true
        var bestFraction = 0.0
        for (dx in -3..3) {
            val xx = (x + dx).coerceIn(0, w - 1)
            var count = 0
            for (y in y0..y1) if (isDark(xx, y)) count++
            bestFraction = max(bestFraction, count.toDouble() / (y1 - y0 + 1))
        }
        return bestFraction > 0.6
    }
}
