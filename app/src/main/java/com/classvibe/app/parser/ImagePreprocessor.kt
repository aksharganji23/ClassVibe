package com.classvibe.app.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Prepares screenshots / photos for OCR: decode safely, upscale small text,
 * convert to high-contrast grayscale, invert dark-mode timetables.
 */
object ImagePreprocessor {

    private const val MAX_DIM = 2600
    private const val MIN_WIDTH = 1500

    /** Decodes an image (respecting photo orientation on Android 9+) without running out of memory. */
    fun load(context: Context, uri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, MAX_DIM.toFloat() / max(w, h))
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalStateException("Couldn't open the image.")
        }
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Upscales small screenshots so tiny table text becomes readable. */
    fun upscaleIfSmall(src: Bitmap): Bitmap {
        if (src.width >= MIN_WIDTH) return src
        val factor = min(2.5f, MIN_WIDTH.toFloat() / src.width)
        val w = (src.width * factor).roundToInt()
        val h = (src.height * factor).roundToInt()
        if (max(w, h) > MAX_DIM * 1.4f) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * Grayscale + automatic contrast stretch (2nd–98th percentile) + invert when the
     * background is dark. Output is ARGB_8888 with equal R=G=B.
     */
    fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val l = (r * 299 + g * 587 + b * 114) / 1000
            lum[i] = l
            hist[l]++
        }
        val total = px.size
        fun percentile(p: Double): Int {
            val target = (total * p).toLong()
            var acc = 0L
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }
        val lo = percentile(0.02)
        val hi = max(lo + 1, percentile(0.98))
        val mean = lum.average()
        val invert = mean < 110 // dark-mode screenshot
        val range = (hi - lo).toFloat()
        for (i in px.indices) {
            var v = ((lum[i] - lo) * 255f / range).roundToInt().coerceIn(0, 255)
            if (invert) v = 255 - v
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun crop(src: Bitmap, rect: Rect): Bitmap {
        val r = Rect(
            rect.left.coerceIn(0, src.width - 1),
            rect.top.coerceIn(0, src.height - 1),
            rect.right.coerceIn(1, src.width),
            rect.bottom.coerceIn(1, src.height)
        )
        if (r.width() < 10 || r.height() < 10) return src
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }
}
