package com.classvibe.app.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are wiped on reboot (and drift after clock changes), so re-register everything. */
class BootReceiver : BroadcastReceiver() {

    private val handledActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in handledActions) return
        ReminderScheduler.enqueueImmediateResync(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            com.classvibe.app.update.UpdatedNotifier.notifyIfJustUpdated(context)
        }
    }
}
