package com.classvibe.app.update

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * One-tap install of a verified update through Android's PackageInstaller.
 *
 * - First time: Android asks the user to allow "Install unknown apps" for ClassVibe.
 * - Android then shows its "Update this app?" box (one tap).
 * - Android 12+: once ClassVibe has installed itself, later updates need no tap at all
 *   (USER_ACTION_NOT_REQUIRED – Android only allows it for the app's own installer).
 * - After the update Android restarts the app process; [UpdatedNotifier] posts
 *   "✅ Updated – tap to open" (Android doesn't let apps reopen themselves from the background).
 */
object UpdateInstaller {

    const val ACTION_INSTALL_RESULT = "com.classvibe.app.INSTALL_RESULT"
    const val EXTRA_TAG = "tag"

    /** Whether the user has allowed ClassVibe to install updates. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for ClassVibe. */
    fun openInstallPermission(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands the (already verified) APK to Android's package installer. */
    fun install(context: Context, apk: File, tag: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setOriginatingUri(Uri.fromFile(apk))
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("ClassVibe.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val result = Intent(context, InstallResultReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
                .putExtra(EXTRA_TAG, tag)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, result, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Keeps a visible copy in Downloads/ClassVibe/ (Android 10+, no storage permission needed),
     * so the file can be found and reinstalled by hand if anything goes wrong.
     */
    fun saveBackupCopy(context: Context, apk: File, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Replace an older copy with the same name (only files this app created are visible to it).
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, BACKUP_PATH)
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, BACKUP_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // A backup copy is a nice-to-have; never block the update because of it.
        }
    }

    private val BACKUP_PATH = Environment.DIRECTORY_DOWNLOADS + "/ClassVibe/"
}
