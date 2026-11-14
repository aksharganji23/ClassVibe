package com.classvibe.app

import android.app.Application
import com.classvibe.app.notifications.NotificationHelper
import com.classvibe.app.notifications.ReminderScheduler
import com.classvibe.app.update.UpdateWorker

class ClassVibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        ReminderScheduler.enqueuePeriodicResync(this)
        // Re-register all alarms on every app start (cheap, and covers upgrades from older versions).
        ReminderScheduler.enqueueImmediateResync(this)
        UpdateWorker.schedule(this) // the worker itself respects the "Check for updates" setting
    }
}
