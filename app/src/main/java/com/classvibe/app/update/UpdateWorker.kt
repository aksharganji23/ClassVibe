package com.classvibe.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.classvibe.app.data.AppSettings
import java.util.concurrent.TimeUnit

/** Background update check every 6 hours on any network; notifies once per new version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = AppSettings.get(applicationContext)
        if (!prefs.updateChecks) return Result.success()
        val result = UpdateChecker.check(applicationContext, prefs.betaUpdates)
        if (result is UpdateChecker.Result.Available && result.info.tag != prefs.skippedVersion) {
            UpdateChecker.notifyAvailable(applicationContext, result.info)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "classvibe_update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
