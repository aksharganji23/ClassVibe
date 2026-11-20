package com.classvibe.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.classvibe.app.data.AttendanceStatus
import com.classvibe.app.data.ClassVibeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles the ✅ Attended / ❌ Missed buttons on class notifications. */
class AttendanceActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val classId = intent.getLongExtra(EXTRA_CLASS, -1L)
        val epochDay = intent.getLongExtra(EXTRA_DAY, -1L)
        val course = intent.getStringExtra(EXTRA_COURSE).orEmpty()
        val status = intent.getStringExtra(EXTRA_STATUS)?.let { AttendanceStatus.from(it) } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (intent.getBooleanExtra(EXTRA_TEST, false)) {
            // Buttons on a test notification: show what would happen, save nothing.
            androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId)
            android.widget.Toast.makeText(context, "🧪 Test: would mark ${status.name.lowercase()} – nothing saved", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (classId <= 0 || epochDay < 0) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ClassVibeRepository.markById(context.applicationContext, classId, course, epochDay, status)
                NotificationHelper.cancel(context, notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "com.classvibe.app.ACTION_ATTENDANCE"
        private const val EXTRA_CLASS = "class_id"
        private const val EXTRA_DAY = "epoch_day"
        private const val EXTRA_COURSE = "course"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_NOTIFICATION = "notification_id"
        private const val EXTRA_TEST = "test"

        fun intent(context: Context, classId: Long, course: String, epochDay: Long, status: AttendanceStatus, notificationId: Int, test: Boolean = false): PendingIntent {
            val i = Intent(context, AttendanceActionReceiver::class.java)
                .setAction(ACTION)
                .setData(Uri.parse("classvibe://attendance/$classId/$epochDay/${status.name}" + if (test) "/test/$notificationId" else ""))
                .putExtra(EXTRA_CLASS, classId)
                .putExtra(EXTRA_DAY, epochDay)
                .putExtra(EXTRA_COURSE, course)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_NOTIFICATION, notificationId)
                .putExtra(EXTRA_TEST, test)
            return PendingIntent.getBroadcast(
                context, (classId * 31 + status.ordinal).toInt(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
