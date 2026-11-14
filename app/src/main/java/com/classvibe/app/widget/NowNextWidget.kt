package com.classvibe.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.classvibe.app.R
import com.classvibe.app.data.AppDatabase
import com.classvibe.app.data.AppSettings
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.notifications.ExamPlanner
import com.classvibe.app.notifications.ExamReminderKind
import com.classvibe.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Home-screen widget: current class, next class, nearest exam countdown. */
class NowNextWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val views = buildViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, NowNextWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, NowNextWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }

        private suspend fun buildViews(context: Context): RemoteViews {
            val db = AppDatabase.get(context)
            val prefs = AppSettings.get(context)
            val now = LocalDateTime.now()
            val date = now.toLocalDate()
            val minutes = now.hour * 60 + now.minute
            val holidays = db.holidayDao().getAll()
            val holiday = ClassCalendar.holidayOn(date, holidays)
            val today = db.scheduleDao().getAll().filter {
                ClassCalendar.isActive(it, date, prefs, holidays, db.attendanceDao().get(it.id, date.toEpochDay()))
            }.sortedBy { it.startMinutes }

            val current = today.firstOrNull { minutes in it.startMinutes until it.endMinutes }
            val next = today.firstOrNull { it.startMinutes > minutes }

            val nowText = when {
                holiday != null -> "🏖️ ${holiday.label} — no classes"
                current != null -> "🔥 Now: ${current.course} (${current.room.ifBlank { "—" }}) until ${current.endTimeText}"
                today.isEmpty() -> "🎉 No classes today"
                next == null -> "✅ All classes done for today"
                else -> "☕ Free right now"
            }
            val nextText = next?.let { "⏭️ Next: ${it.course} at ${it.startTimeText}" + if (it.room.isNotBlank()) " • ${it.room}" else "" } ?: ""
            val exam = db.examDao().getAll().filter { !it.date.isBefore(date) }.minByOrNull { it.epochDay }
            val examText = exam?.let {
                val days = ChronoUnit.DAYS.between(date, it.date)
                "${it.examType.emoji} ${it.typeLabel}: ${it.course} ${ExamPlanner.countdownText(days, ExamReminderKind.MILESTONE)}"
            } ?: ""

            return RemoteViews(context.packageName, R.layout.widget_now_next).apply {
                setTextViewText(R.id.widget_now, nowText)
                setTextViewText(R.id.widget_next, nextText)
                setTextViewText(R.id.widget_exam, examText)
                setOnClickPendingIntent(R.id.widget_root, NotificationHelper.openAppIntent(context))
            }
        }
    }
}
