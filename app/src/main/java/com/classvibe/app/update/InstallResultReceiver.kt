package com.classvibe.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.classvibe.app.BuildConfig
import com.classvibe.app.data.AppSettings
import com.classvibe.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Result of an update install session started by [UpdateInstaller]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_INSTALL_RESULT) return
        val tag = intent.getStringExtra(UpdateInstaller.EXTRA_TAG) ?: ""
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Shown straight away while ClassVibe is open; the notification covers the
                // case where Android blocks it (download finished in the background).
                NotificationHelper.post(
                    context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                    "📲 ClassVibe $tag is ready – tap to update",
                    "Verified ✅ Tap, then tap Update.",
                    contentIntent = PendingIntent.getActivity(
                        context, 78, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ),
                    category = androidx.core.app.NotificationCompat.CATEGORY_STATUS
                )
                try { context.startActivity(confirm) } catch (e: Exception) { /* notification is there */ }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdatedNotifier.notifyIfJustUpdated(context)
            PackageInstaller.STATUS_FAILURE_ABORTED -> NotificationHelper.post(
                context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                "Update to $tag cancelled",
                "Tap Update in ClassVibe whenever you're ready."
            )
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                NotificationHelper.post(
                    context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                    "⚠️ Couldn't install ClassVibe $tag",
                    "Install it by hand from Downloads/ClassVibe or the website." + if (msg.isNotBlank()) " ($msg)" else ""
                )
            }
        }
    }
}

/** Posts "✅ Updated – tap to open" once, after the app was replaced by a newer version. */
object UpdatedNotifier {
    fun notifyIfJustUpdated(context: Context) {
        val pending = goAsyncScope()
        pending.launch {
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
                category = androidx.core.app.NotificationCompat.CATEGORY_STATUS
            )
        }
    }

    private fun goAsyncScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
