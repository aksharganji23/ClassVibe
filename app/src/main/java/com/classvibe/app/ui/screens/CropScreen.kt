package com.classvibe.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.classvibe.app.parser.ImagePreprocessor
import com.classvibe.app.ui.theme.Cv
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop + rotate step before OCR: cropping to just the table is the biggest accuracy win. */
@Composable
fun CropScreen(
    bitmap: Bitmap,
    index: Int,
    total: Int,
    onReplace: (Bitmap) -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    // Crop rectangle in normalised image coordinates (0..1).
    var crop by remember(bitmap) { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var handle by remember { mutableIntStateOf(-1) }
    var autoCropped by remember(bitmap) { mutableStateOf(false) }

    // ✨ Auto-crop: find the table's outer border and pre-set the crop box.
    androidx.compose.runtime.LaunchedEffect(bitmap) {
        val bounds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val scale = min(1f, 1200f / maxOf(bitmap.width, bitmap.height))
                val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true) else bitmap
                com.classvibe.app.parser.TableDetector(com.classvibe.app.parser.ImageEnhancer.enhance(small)).tableBounds()?.let { r ->
                    Rect(r.left / (small.width.toFloat()), r.top / (small.height.toFloat()), r.right / (small.width.toFloat()), r.bottom / (small.height.toFloat()))
                }
            } catch (e: Exception) { null }
        }
        if (bounds != null && bounds.width > 0.2f && bounds.height > 0.2f && (bounds.width < 0.97f || bounds.height < 0.97f)) {
            val pad = 0.015f
            crop = Rect((bounds.left - pad).coerceAtLeast(0f), (bounds.top - pad).coerceAtLeast(0f), (bounds.right + pad).coerceAtMost(1f), (bounds.bottom + pad).coerceAtMost(1f))
            autoCropped = true
        }
    }
    val accent = Cv.colors.accent

    val scale = if (boxSize.width == 0) 1f else min(boxSize.width.toFloat() / bitmap.width, boxSize.height.toFloat() / bitmap.height)
    val dispW = bitmap.width * scale
    val dispH = bitmap.height * scale
    val offX = (boxSize.width - dispW) / 2f
    val offY = (boxSize.height - dispH) / 2f

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("✂️ Crop to the table (${index + 1}/$total)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Drag the corners so only the timetable is inside the box. Rotate if it's sideways.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (autoCropped) Text("✨ Table detected – auto-cropped. Adjust if needed.", style = MaterialTheme.typography.labelLarge, color = Cv.colors.success)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).onSizeChanged { boxSize = it }
        ) {
            Image(bitmap.asImageBitmap(), contentDescription = "Timetable image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(bitmap, boxSize) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val corners = listOf(
                                Offset(offX + crop.left * dispW, offY + crop.top * dispH),
                                Offset(offX + crop.right * dispW, offY + crop.top * dispH),
                                Offset(offX + crop.left * dispW, offY + crop.bottom * dispH),
                                Offset(offX + crop.right * dispW, offY + crop.bottom * dispH)
                            )
                            val nearest = corners.indices.minByOrNull { (corners[it] - pos).getDistance() } ?: 0
                            handle = if ((corners[nearest] - pos).getDistance() < 80f) nearest else 4
                        },
                        onDragEnd = { handle = -1 },
                        onDrag = { change, drag ->
                            change.consume()
                            if (dispW <= 0f || dispH <= 0f) return@detectDragGestures
                            val dx = drag.x / dispW
                            val dy = drag.y / dispH
                            val minSize = 0.08f
                            var l = crop.left; var t = crop.top; var r = crop.right; var b = crop.bottom
                            when (handle) {
                                0 -> { l += dx; t += dy }
                                1 -> { r += dx; t += dy }
                                2 -> { l += dx; b += dy }
                                3 -> { r += dx; b += dy }
                                4 -> {
                                    val mx = dx.coerceIn(-l, 1f - r)
                                    val my = dy.coerceIn(-t, 1f - b)
                                    l += mx; r += mx; t += my; b += my
                                }
                            }
                            l = l.coerceIn(0f, r - minSize); t = t.coerceIn(0f, b - minSize)
                            r = r.coerceIn(l + minSize, 1f); b = b.coerceIn(t + minSize, 1f)
                            crop = Rect(l, t, r, b)
                        }
                    )
                }
            ) {
                val left = offX + crop.left * dispW
                val top = offY + crop.top * dispH
                val right = offX + crop.right * dispW
                val bottom = offY + crop.bottom * dispH
                val dim = Color.Black.copy(alpha = 0.45f)
                drawRect(dim, Offset(0f, 0f), Size(size.width, top))
                drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(dim, Offset(0f, top), Size(left, bottom - top))
                drawRect(dim, Offset(right, top), Size(size.width - right, bottom - top))
                drawRect(accent, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 4f))
                listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach {
                    drawCircle(accent, radius = 16f, center = it)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onReplace(ImagePreprocessor.rotate(bitmap, 90f)) }) { Text("⟳ Rotate") }
            OutlinedButton(onClick = { crop = Rect(0f, 0f, 1f, 1f) }) { Text("Reset") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                val full = abs(crop.left) < 0.001f && abs(crop.top) < 0.001f && crop.right > 0.999f && crop.bottom > 0.999f
                if (!full) {
                    val r = android.graphics.Rect(
                        (crop.left * bitmap.width).roundToInt(), (crop.top * bitmap.height).roundToInt(),
                        (crop.right * bitmap.width).roundToInt(), (crop.bottom * bitmap.height).roundToInt()
                    )
                    onReplace(ImagePreprocessor.crop(bitmap, r))
                }
                onNext()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (index + 1 < total) "Next image →" else "🔍 Read timetable") }
    }
}
