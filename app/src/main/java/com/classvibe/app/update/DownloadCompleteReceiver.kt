package com.classvibe.app.update

import android.app.DownloadManager
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
                    val file = UpdateChecker.downloadedFile(context, info)
                    // ClassVibe doesn't install apps itself (that's what Play Protect blocks) –
                    // it puts the verified file in Downloads/ClassVibe and Android's installer does the rest.
                    val saved = UpdateFiles.saveToDownloads(context, file, info.apkName)
                    NotificationHelper.post(
                        context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                        "✅ ClassVibe ${info.tag} is ready to install",
                        "Tap → open ${info.apkName} in ${UpdateFiles.whereIsIt()} → Update.",
                        bigText = "Downloaded and safety-checked ✔️\n\nTap here, then tap ${info.apkName} " +
                            "(in ${UpdateFiles.whereIsIt()}) and press Update. Your timetable and settings stay as they are." +
                            (if (saved == null) "" else "\n\nIf Android asks, allow your Files app to install apps – only once."),
                        contentIntent = UpdateChecker.downloadsIntent(context)
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
