package com.classvibe.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.classvibe.app.data.AppSettings
import com.classvibe.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** "⬇️ Update now" button on the new-version notification: starts the download straight away. */
class UpdateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_NOW) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val info = UpdateInfo.fromJson(AppSettings.raw(context)[AppSettings.Keys.AVAILABLE_UPDATE]) ?: return@launch
                UpdateChecker.download(context, info)
                NotificationHelper.post(
                    context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                    "⬇️ Downloading ClassVibe ${info.tag}…",
                    "We'll tell you when it's safety-checked and ready to install.",
                    category = androidx.core.app.NotificationCompat.CATEGORY_PROGRESS
                )
            } catch (e: Exception) {
                NotificationHelper.post(
                    context, NotificationHelper.ID_UPDATE, NotificationHelper.CH_UPDATES,
                    "⚠️ Couldn't start the update", e.message ?: "Open ClassVibe and tap Update."
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val ACTION_UPDATE_NOW = "com.classvibe.app.UPDATE_NOW" }
}
