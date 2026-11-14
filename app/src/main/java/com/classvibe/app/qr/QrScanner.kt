package com.classvibe.app.qr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/**
 * Two ways to read a ClassVibe QR code – neither needs the camera permission:
 *  - camera: Google's code scanner UI (provided by Google Play services)
 *  - image:  a screenshot / photo of the QR from the gallery (bundled ML Kit, fully offline)
 */
object QrScanner {

    /** Returns the QR text, or null if cancelled. Throws if the scanner isn't available. */
    suspend fun scanWithCamera(context: Context): String? {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        return try {
            GmsBarcodeScanning.getClient(context, options).startScan().await().rawValue
        } catch (e: kotlinx.coroutines.CancellationException) {
            null
        }
    }

    suspend fun scanImage(context: Context, uri: Uri): List<String> {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        return try {
            scanner.process(InputImage.fromFilePath(context, uri)).await().mapNotNull { it.rawValue }
        } finally {
            scanner.close()
        }
    }
}
