package com.classvibe.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.classvibe.app.data.AppDatabase
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AppSettings
import com.classvibe.app.data.TrainingDay
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.Exam
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Owns every AlarmManager alarm:
 *  - CLASS      one per (class, reminder time) – e.g. 30, 10 and 5 minutes before
 *  - CLASS_END  5 minutes before a class ends (next-class / attendance nudge)
 *  - EXAM       the next reminder of each exam (re-armed after it fires)
 *  - WEEKEND    the next weekend-prep slot
 *  - MORNING    the next morning summary
 * Each alarm wakes [AlarmReceiver], which hands the work to [ClassReminderWorker].
 */
object ReminderScheduler {

    // New kinds go at the END: the ordinal is part of each alarm's request code.
    enum class Kind { CLASS, CLASS_END, EXAM, WEEKEND, MORNING, TRAINING }

    /** Training alarm "lead" values: evening before, and N minutes before it starts. */
    const val TRAINING_EVENING = 1
    const val TRAINING_BEFORE = 2

    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val EXTRA_LEAD = "lead"
    private const val ACTION_ALARM = "com.classvibe.app.ACTION_ALARM"
    private const val PERIODIC_RESYNC = "classvibe_periodic_resync"
    private const val IMMEDIATE_RESYNC = "classvibe_immediate_resync"

    private fun pendingIntent(context: Context, kind: Kind, id: Long, lead: Int, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("classvibe://${kind.name.lowercase()}/$id/$lead")) // unique per alarm
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_LEAD, lead)
        return PendingIntent.getBroadcast(context, (kind.ordinal * 7919 + id * 131 + lead).toInt(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun setAlarm(context: Context, at: LocalDateTime, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun cancelAlarm(context: Context, kind: Kind, id: Long, lead: Int) {
        pendingIntent(context, kind, id, lead, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            context.getSystemService(AlarmManager::class.java).cancel(pi)
            pi.cancel()
        }
    }

    /** Next moment strictly after [now] that is [offsetMinutes] before [minuteOfDay] on the class's weekday. */
    fun nextOccurrence(c: ClassSchedule, minuteOfDay: Int, offsetMinutes: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate()
        for (offset in 0L..14L) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek.value != c.dayOfWeek) continue
            val at = date.atTime(minuteOfDay / 60, minuteOfDay % 60).minusMinutes(offsetMinutes.toLong())
            if (at.isAfter(now)) return at
        }
        return now.plusDays(7)
    }

    // ---------------- classes ----------------

    suspend fun scheduleClass(context: Context, c: ClassSchedule, prefs: AppPrefs? = null) {
        if (c.id <= 0) return
        val p = prefs ?: AppSettings.get(context)
        cancelClass(context, c)
        if (!p.remindersEnabled) return
        p.leadMinutes.forEach { lead ->
            pendingIntent(context, Kind.CLASS, c.id, lead, PendingIntent.FLAG_UPDATE_CURRENT)?.let {
                setAlarm(context, nextOccurrence(c, c.startMinutes, lead), it)
            }
        }
        if (p.classEndNudge && c.endMinutes - c.startMinutes > 10) {
            pendingIntent(context, Kind.CLASS_END, c.id, 5, PendingIntent.FLAG_UPDATE_CURRENT)?.let {
                setAlarm(context, nextOccurrence(c, c.endMinutes, 5), it)
            }
        }
    }

    fun cancelClass(context: Context, c: ClassSchedule) {
        AppSettings.LEAD_OPTIONS.forEach { cancelAlarm(context, Kind.CLASS, c.id, it) }
        cancelAlarm(context, Kind.CLASS_END, c.id, 5)
    }

    // ---------------- exams ----------------

    suspend fun scheduleExam(context: Context, exam: Exam, prefs: AppPrefs? = null) {
        val p = prefs ?: AppSettings.get(context)
        cancelExam(context, exam.id)
        if (!p.examRemindersEnabled || exam.examType.name in p.disabledExamTypes) return
        val next = ExamPlanner.next(exam, p) ?: return
        pendingIntent(context, Kind.EXAM, exam.id, 0, PendingIntent.FLAG_UPDATE_CURRENT)?.let { setAlarm(context, next.at, it) }
    }

    fun cancelExam(context: Context, examId: Long) = cancelAlarm(context, Kind.EXAM, examId, 0)

    // ---------------- placement training ----------------

    suspend fun scheduleTraining(context: Context, day: TrainingDay, prefs: AppPrefs? = null) {
        val p = prefs ?: AppSettings.get(context)
        cancelTraining(context, day.id)
        if (!p.trainingEnabled || day.id <= 0) return
        val now = LocalDateTime.now()
        if (p.trainingEveningMinutes >= 0) {
            val at = day.date.minusDays(1).atStartOfDay().plusMinutes(p.trainingEveningMinutes.toLong())
            if (at.isAfter(now)) pendingIntent(context, Kind.TRAINING, day.id, TRAINING_EVENING, PendingIntent.FLAG_UPDATE_CURRENT)?.let { setAlarm(context, at, it) }
        }
        if (p.trainingLeadMinutes > 0) {
            val at = day.startsAt.minusMinutes(p.trainingLeadMinutes.toLong())
            if (at.isAfter(now)) pendingIntent(context, Kind.TRAINING, day.id, TRAINING_BEFORE, PendingIntent.FLAG_UPDATE_CURRENT)?.let { setAlarm(context, at, it) }
        }
    }

    fun cancelTraining(context: Context, dayId: Long) {
        cancelAlarm(context, Kind.TRAINING, dayId, TRAINING_EVENING)
        cancelAlarm(context, Kind.TRAINING, dayId, TRAINING_BEFORE)
    }

    suspend fun rescheduleTraining(context: Context, prefs: AppPrefs? = null) {
        val p = prefs ?: AppSettings.get(context)
        AppDatabase.get(context).trainingDao().getAll().forEach { scheduleTraining(context, it, p) }
    }

    // ---------------- weekend + morning ----------------

    suspend fun scheduleGlobal(context: Context, prefs: AppPrefs? = null) {
        val p = prefs ?: AppSettings.get(context)
        cancelAlarm(context, Kind.WEEKEND, 0, 0)
        cancelAlarm(context, Kind.MORNING, 0, 0)
        WeekendPlanner.nextSlot(p)?.let { at ->
            pendingIntent(context, Kind.WEEKEND, 0, 0, PendingIntent.FLAG_UPDATE_CURRENT)?.let { setAlarm(context, at, it) }
        }
        if (p.morningSummary) {
            val now = LocalDateTime.now()
            var at = now.toLocalDate().atTime(LocalTime.of(p.morningSummaryMinutes / 60, p.morningSummaryMinutes % 60))
            if (!at.isAfter(now)) at = at.plusDays(1)
            pendingIntent(context, Kind.MORNING, 0, 0, PendingIntent.FLAG_UPDATE_CURRENT)?.let { setAlarm(context, at, it) }
        }
    }

    // ---------------- everything ----------------

    suspend fun rescheduleAll(context: Context) {
        val db = AppDatabase.get(context)
        val prefs = AppSettings.get(context)
        db.scheduleDao().getAll().forEach { scheduleClass(context, it, prefs) }
        db.examDao().getAll().forEach { scheduleExam(context, it, prefs) }
        db.trainingDao().getAll().forEach { scheduleTraining(context, it, prefs) }
        scheduleGlobal(context, prefs)
    }

    /** Safety net: re-registers every alarm twice a day in case the OS dropped any. */
    fun enqueuePeriodicResync(context: Context) {
        val request = PeriodicWorkRequestBuilder<RescheduleWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_RESYNC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** After boot, app update, time-zone change or settings change. */
    fun enqueueImmediateResync(context: Context) {
        val request = OneTimeWorkRequestBuilder<RescheduleWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_RESYNC, ExistingWorkPolicy.REPLACE, request)
    }
}
