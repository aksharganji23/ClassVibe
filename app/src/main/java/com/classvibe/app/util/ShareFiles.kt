package com.classvibe.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes temporary export files and opens Android's share sheet (no storage permission needed). */
object ShareFiles {

    fun cacheFile(context: Context, name: String): File =
        File(File(context.cacheDir, "export").apply { mkdirs() }, name)

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun sharePngs(context: Context, images: List<Bitmap>, text: String) {
        val uris = ArrayList(images.mapIndexed { i, bmp ->
            val f = cacheFile(context, "classvibe_qr_${i + 1}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            uri(context, f)
        })
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.setType("image/png").putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("qr", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        context.startActivity(Intent.createChooser(intent, "Share QR code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens a text file (e.g. .ics) in a capable app, falling back to the share sheet. */
    fun openOrShare(context: Context, name: String, mime: String, content: String) {
        val f = cacheFile(context, name)
        f.writeText(content)
        val u = uri(context, f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (e: Exception) {
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
