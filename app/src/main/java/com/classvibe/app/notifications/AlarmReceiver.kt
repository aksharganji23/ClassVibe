package com.classvibe.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Woken by AlarmManager; immediately hands the job to an expedited WorkManager worker. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND) ?: return
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L)
        val lead = intent.getIntExtra(ReminderScheduler.EXTRA_LEAD, 0)

        val request = OneTimeWorkRequestBuilder<ClassReminderWorker>()
            .setInputData(
                workDataOf(
                    ReminderScheduler.EXTRA_KIND to kind,
                    ReminderScheduler.EXTRA_ID to id,
                    ReminderScheduler.EXTRA_LEAD to lead
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("reminder_${kind}_${id}_$lead", ExistingWorkPolicy.REPLACE, request)
    }
}
