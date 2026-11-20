package com.classvibe.app.update

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.classvibe.app.BuildConfig
import com.classvibe.app.data.AppSettings
import com.classvibe.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Puts a verified update where the user can install it with Android's own installer.
 *
 * v1.7.0: ClassVibe no longer installs APKs itself (no REQUEST_INSTALL_PACKAGES, no
 * PackageInstaller session). Apps that install other APKs are exactly what Google Play
 * Protect flags as "Harmful app blocked", so the update is saved to Downloads/ClassVibe/
 * and opened from the Files/Downloads app – the same way the very first install works.
 */
object UpdateFiles {

    private val FOLDER = Environment.DIRECTORY_DOWNLOADS + "/ClassVibe/"

    /** Copies the APK to Downloads/ClassVibe/ (Android 10+, no storage permission needed). */
    fun saveToDownloads(context: Context, apk: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Replace an older copy with the same name (only files this app created are visible to it).
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, FOLDER)
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }

    /** Where the user should look for the file, in plain words. */
    fun whereIsIt(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Downloads › ClassVibe" else "the Downloads list"
}

/** Posts "✅ Updated – tap to open" once, after the app was replaced by a newer version. */
object UpdatedNotifier {
    fun notifyIfJustUpdated(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val raw = AppSettings.raw(context)
            val tag = raw[AppSettings.Keys.DOWNLOAD_TAG] ?: return@launch
            val installed = Semver.parse(BuildConfig.VERSION_NAME) ?: return@launch
            val wanted = Semver.parse(tag) ?: return@launch
            if (installed < wanted) return@launch
            AppSettings.update(context) { it.remove(AppSettings.Keys.DOWNLOAD_TAG) }
            NotificationHelper.post(
                context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                "✅ ClassVibe updated to ${BuildConfig.VERSION_NAME}",
                "Tap to open and see what's new 🎉",
                contentIntent = NotificationHelper.openAppIntent(context, tab = 0),
                category = androidx.core.app.NotificationCompat.CATEGORY_STATUS
            )
        }
    }
}
