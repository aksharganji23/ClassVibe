package com.classvibe.app.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.classvibe.app.data.AppSettings
import com.classvibe.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Verifies a finished update download, then tells the user it's safe to install. */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val raw = AppSettings.raw(context)
                if (raw[AppSettings.Keys.DOWNLOAD_ID] != id) return@launch
                val info = UpdateInfo.fromJson(raw[AppSettings.Keys.AVAILABLE_UPDATE]) ?: return@launch
                val problem = UpdateChecker.verify(context, info)
                if (problem == null) {
                    val open = PendingIntent.getActivity(
                        context, 77,
                        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    NotificationHelper.post(
                        context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                        "✅ ClassVibe ${info.tag} verified – tap to install",
                        "Checksum and signature are OK. Tap, then tap the ClassVibe file to install.",
                        contentIntent = open
                    )
                } else {
                    val dm = context.getSystemService(DownloadManager::class.java)
                    dm.remove(id)
                    NotificationHelper.post(
                        context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                        "⚠️ Update blocked for your safety",
                        problem
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
