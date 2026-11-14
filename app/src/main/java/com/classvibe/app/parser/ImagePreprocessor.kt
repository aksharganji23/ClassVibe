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

    /** Grayscale, dark-mode inversion, shadow removal and contrast stretch (see [ImageEnhancer]). */
    fun enhance(src: Bitmap): Bitmap = ImageEnhancer.enhance(src)

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
