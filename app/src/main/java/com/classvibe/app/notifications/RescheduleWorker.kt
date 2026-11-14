package com.classvibe.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.classvibe.app.widget.NowNextWidget

/** Re-registers every alarm from the database (classes, exams, weekend prep, morning summary). */
class RescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ReminderScheduler.rescheduleAll(applicationContext)
        NowNextWidget.requestUpdate(applicationContext)
        return Result.success()
    }
}
