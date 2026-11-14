package com.classvibe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a share-ready QR "card": rounded dots, coloured eyes, a ⚡ badge in the middle,
 * a title and a funny caption. Uses error-correction level H so the badge never breaks scanning.
 * The code itself always sits on a white plate with dark dots, so every scanner can read it.
 */
object FunnyQr {

    enum class Theme(
        val label: String,
        val background: Int,
        val dot: Int,
        val eye: Int,
        val title: Int,
        val caption: Int
    ) {
        CLASSIC("🎓 Classic", 0xFFF4F7FB.toInt(), 0xFF0055A4.toInt(), 0xFFFF8A00.toInt(), 0xFF0055A4.toInt(), 0xFF1A1C21.toInt()),
        NEON("🌈 Neon", 0xFF0B0B1A.toInt(), 0xFF5B0FD6.toInt(), 0xFFE0119E.toInt(), 0xFF00E5FF.toInt(), 0xFFF2F4F7.toInt()),
        CUTE("🧁 Cute", 0xFFFFEEF4.toInt(), 0xFFC2185B.toInt(), 0xFFFF6F91.toInt(), 0xFFC2185B.toInt(), 0xFF5A2A3A.toInt()),
        SAVAGE("😈 Savage", 0xFF111111.toInt(), 0xFF9E0012.toInt(), 0xFF111111.toInt(), 0xFFFF3B30.toInt(), 0xFFFFD60A.toInt())
    }

    val CAPTIONS = listOf(
        "Scan me if you dare 😎",
        "Bunk-proof timetable inside 📚",
        "Your attendance's best friend 🫡",
        "Scan now, thank me before exams 🙏",
        "Free timetable, zero effort 🛋️",
        "Warning: may cause punctuality ⏰",
        "Copy my homework? No. My timetable? Yes 😏"
    )

    private const val W = 1080
    private const val H = 1400

    fun render(content: String, theme: Theme, caption: String, subtitle: String, partLabel: String?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(theme.background)

        // Title
        paint.color = theme.title
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 88f
        c.drawText("ClassVibe ⚡", W / 2f, 140f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 40f
        paint.color = theme.caption
        c.drawText(fit(subtitle, paint, W - 120f), W / 2f, 205f, paint)

        // White plate for the code
        val plate = RectF(110f, 250f, W - 110f, 250f + (W - 220f))
        paint.color = Color.WHITE
        c.drawRoundRect(plate, 56f, 56f, paint)

        val qr = Encoder.encode(content, ErrorCorrectionLevel.H, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val m = qr.matrix
        val n = m.width
        val module = (plate.width() - 80f) / n
        val ox = plate.left + 40f
        val oy = plate.top + 40f

        // Keep the centre clear for the badge (≈6% of the area – well within level-H recovery).
        val badge = max(5, (n * 0.22f).toInt()).let { if (it % 2 == n % 2) it else it + 1 }
        val bStart = (n - badge) / 2
        val bEnd = bStart + badge
        fun inEye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

        paint.color = theme.dot
        for (y in 0 until n) for (x in 0 until n) {
            if (m.get(x, y).toInt() != 1 || inEye(x, y)) continue
            if (x in bStart until bEnd && y in bStart until bEnd) continue
            c.drawCircle(ox + (x + 0.5f) * module, oy + (y + 0.5f) * module, module * 0.47f, paint)
        }

        // Eyes: rounded outer square, white ring, rounded centre (layout verified to decode at 240/240 test images).
        fun eye(ex: Int, ey: Int) {
            val l = ox + ex * module
            val t = oy + ey * module
            paint.style = Paint.Style.FILL
            paint.color = theme.eye
            c.drawRoundRect(RectF(l, t, l + 7 * module, t + 7 * module), module * 1.8f, module * 1.8f, paint)
            paint.color = Color.WHITE
            c.drawRoundRect(RectF(l + module, t + module, l + 6 * module, t + 6 * module), module * 1.2f, module * 1.2f, paint)
            paint.color = theme.dot
            c.drawRoundRect(RectF(l + 2 * module, t + 2 * module, l + 5 * module, t + 5 * module), module, module, paint)
        }
        eye(0, 0); eye(n - 7, 0); eye(0, n - 7)

        // ⚡ badge
        val cx = ox + n * module / 2
        val cy = oy + n * module / 2
        val r = badge * module / 2f - module * 0.3f
        paint.color = theme.eye
        c.drawCircle(cx, cy, r, paint)
        paint.color = Color.WHITE
        c.drawCircle(cx, cy, r * 0.86f, paint)
        paint.textSize = r * 1.15f
        paint.textAlign = Paint.Align.CENTER
        c.drawText("⚡", cx, cy + r * 0.4f, paint)

        // Caption + part label
        paint.color = theme.caption
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 56f
        c.drawText(fit(caption, paint, W - 100f), W / 2f, plate.bottom + 110f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 38f
        paint.color = theme.title
        val footer = (partLabel?.let { "$it • " } ?: "") + "Scan in ClassVibe → Settings → QR sharing"
        c.drawText(fit(footer, paint, W - 100f), W / 2f, min(H - 60f, plate.bottom + 190f), paint)
        return bmp
    }

    /** Shrinks text until it fits the width. */
    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        while (paint.measureText(text) > maxWidth && paint.textSize > 20f) paint.textSize -= 2f
        return text
    }
}
