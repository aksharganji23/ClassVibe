package com.classvibe.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.classvibe.app.MainActivity
import com.classvibe.app.R

object NotificationHelper {

    const val CH_CLASSES = "class_reminders"
    const val CH_EXAMS = "exam_reminders"
    const val CH_WEEKEND = "weekend_prep"
    const val CH_SUMMARY = "daily_summary"
    const val CH_UPDATES = "app_updates"
    private const val CH_BACKGROUND = "background_work"

    const val FOREGROUND_ID = 9_999
    const val ID_WEEKEND = 400_001
    const val ID_MORNING = 400_002
    const val ID_UPDATE = 400_003
    fun classId(id: Long) = 100_000 + (id % 90_000).toInt()
    fun classEndId(id: Long) = 200_000 + (id % 90_000).toInt()
    fun examId(id: Long) = 300_000 + (id % 90_000).toInt()

    private const val BRAND = 0xFF0055A4.toInt()

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, desc: String, importance: Int) =
            NotificationChannel(id, name, importance).apply { description = desc }
        nm.createNotificationChannels(
            listOf(
                ch(CH_CLASSES, "Class reminders", "Funny heads-ups before each class", NotificationManager.IMPORTANCE_HIGH),
                ch(CH_EXAMS, "Exam reminders", "Countdowns for semester, mid, lab exams, slip tests and deadlines", NotificationManager.IMPORTANCE_HIGH),
                ch(CH_WEEKEND, "Weekend prep", "Saturday/Sunday summaries of next week's exams", NotificationManager.IMPORTANCE_DEFAULT),
                ch(CH_SUMMARY, "Morning summary", "Your day at a glance", NotificationManager.IMPORTANCE_DEFAULT),
                ch(CH_UPDATES, "App updates", "New ClassVibe versions", NotificationManager.IMPORTANCE_DEFAULT),
                ch(CH_BACKGROUND, "Background work", "Shown briefly while reminders are prepared", NotificationManager.IMPORTANCE_MIN)
            )
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun openAppIntent(context: Context, tab: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, tab,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, tab),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    data class Action(val title: String, val intent: PendingIntent)

    @SuppressLint("MissingPermission") // checked by canPostNotifications()
    fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        body: String,
        bigText: String = body,
        subText: String? = null,
        actions: List<Action> = emptyList(),
        contentIntent: PendingIntent = openAppIntent(context),
        category: String = NotificationCompat.CATEGORY_REMINDER,
        /** Shows a live countdown to this moment (epoch millis) in the notification header. */
        countdownTo: Long? = null,
        /** Removes the notification automatically after this many millis. */
        timeoutAfter: Long? = null,
        /** Notifications in the same group stack together under one ClassVibe header. */
        group: String? = null
    ) {
        if (!canPostNotifications(context)) return
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setCategory(category)
            .setPriority(if (channel == CH_CLASSES || channel == CH_EXAMS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        subText?.let { builder.setSubText(it) }
        actions.forEach { builder.addAction(0, it.title, it.intent) }
        if (countdownTo != null && countdownTo > System.currentTimeMillis()) {
            builder.setWhen(countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        timeoutAfter?.takeIf { it > 0 }?.let { builder.setTimeoutAfter(it) }
        val manager = NotificationManagerCompat.from(context)
        if (group != null) {
            builder.setGroup(group)
            manager.notify(id, builder.build())
            // Group summary so the stack folds neatly under one header.
            val summary = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(BRAND)
                .setContentTitle("ClassVibe")
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(group.hashCode(), summary)
        } else {
            manager.notify(id, builder.build())
        }
    }

    const val GROUP_CLASSES = "classvibe_classes"
    const val GROUP_EXAMS = "classvibe_exams"
    private var testCounter = 0

    /** A fresh id for each test notification, so tests stack instead of replacing each other. */
    fun nextTestId(): Int = 450_000 + (testCounter++ % 1000)

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Low-priority notification required when WorkManager runs expedited work on Android < 12. */
    fun workerNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CH_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Preparing your reminder…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
}
