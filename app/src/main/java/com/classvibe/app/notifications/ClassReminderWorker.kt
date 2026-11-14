package com.classvibe.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.classvibe.app.data.AppDatabase
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AppSettings
import com.classvibe.app.data.AttendanceStatus
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.widget.NowNextWidget
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Runs when any ClassVibe alarm fires. Decides whether the reminder is still relevant
 * (holidays, cancellations, odd/even weeks, semester dates), posts it, then re-arms the alarm.
 */
class ClassReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db get() = AppDatabase.get(applicationContext)

    override suspend fun doWork(): Result {
        val kind = inputData.getString(ReminderScheduler.EXTRA_KIND)
            ?.let { k -> ReminderScheduler.Kind.entries.firstOrNull { it.name == k } }
            ?: return Result.failure()
        val id = inputData.getLong(ReminderScheduler.EXTRA_ID, 0L)
        val lead = inputData.getInt(ReminderScheduler.EXTRA_LEAD, 0)
        val prefs = AppSettings.get(applicationContext)
        val now = LocalDateTime.now()

        when (kind) {
            ReminderScheduler.Kind.CLASS -> handleClass(id, lead, prefs, now)
            ReminderScheduler.Kind.CLASS_END -> handleClassEnd(id, prefs, now)
            ReminderScheduler.Kind.EXAM -> handleExam(id, prefs, now)
            ReminderScheduler.Kind.WEEKEND -> handleWeekend(prefs, now)
            ReminderScheduler.Kind.MORNING -> handleMorning(prefs, now)
        }
        NowNextWidget.requestUpdate(applicationContext)
        return Result.success()
    }

    /** Today's (or tomorrow's, for classes just after midnight) real, not-cancelled classes. */
    private suspend fun activeClassesOn(date: LocalDate, prefs: AppPrefs): List<ClassSchedule> {
        val holidays = db.holidayDao().getAll()
        val classes = db.scheduleDao().getAll()
        return classes.filter { c ->
            ClassCalendar.isActive(c, date, prefs, holidays, db.attendanceDao().get(c.id, date.toEpochDay()))
        }.sortedBy { it.startMinutes }
    }

    private fun occurrenceDate(c: ClassSchedule, minuteOfDay: Int, now: LocalDateTime, lead: Int): LocalDate? =
        (0L..1L).map { now.toLocalDate().plusDays(it) }.firstOrNull { date ->
            if (date.dayOfWeek.value != c.dayOfWeek) return@firstOrNull false
            val target = date.atTime(minuteOfDay / 60, minuteOfDay % 60)
            val minutesUntil = Duration.between(now, target).toMinutes()
            minutesUntil in -10..(lead + 25).toLong()
        }

    private suspend fun handleClass(id: Long, lead: Int, prefs: AppPrefs, now: LocalDateTime) {
        val c = db.scheduleDao().getById(id) ?: return
        val date = occurrenceDate(c, c.startMinutes, now, lead)
        if (date != null && prefs.remindersEnabled && lead in prefs.leadMinutes) {
            val todays = activeClassesOn(date, prefs)
            if (todays.any { it.id == c.id }) {
                val built = ReminderContent.classReminder(
                    applicationContext, c, lead, date, prefs, todays, db.attendanceDao().getAll()
                )
                NotificationHelper.post(
                    applicationContext, NotificationHelper.classId(c.id), NotificationHelper.CH_CLASSES,
                    built.title, built.body, built.bigText, subText = "${c.classType} • ${c.startTimeText}"
                )
            }
        }
        ReminderScheduler.scheduleClass(applicationContext, c, prefs)
    }

    private suspend fun handleClassEnd(id: Long, prefs: AppPrefs, now: LocalDateTime) {
        val c = db.scheduleDao().getById(id) ?: return
        val date = occurrenceDate(c, c.endMinutes, now, 5)
        if (date != null && prefs.classEndNudge) {
            val todays = activeClassesOn(date, prefs)
            if (todays.any { it.id == c.id }) {
                val next = todays.firstOrNull { it.startMinutes >= c.endMinutes - 5 && it.id != c.id }
                val built = ReminderContent.classEnd(applicationContext, c, next, prefs)
                val notifId = NotificationHelper.classEndId(c.id)
                val epochDay = date.toEpochDay()
                NotificationHelper.post(
                    applicationContext, notifId, NotificationHelper.CH_CLASSES, built.title, built.body, built.bigText,
                    subText = c.course,
                    actions = listOf(
                        NotificationHelper.Action("✅ Attended", AttendanceActionReceiver.intent(applicationContext, c.id, c.course, epochDay, AttendanceStatus.ATTENDED, notifId)),
                        NotificationHelper.Action("❌ Missed", AttendanceActionReceiver.intent(applicationContext, c.id, c.course, epochDay, AttendanceStatus.MISSED, notifId))
                    )
                )
            }
        }
        ReminderScheduler.scheduleClass(applicationContext, c, prefs)
    }

    private suspend fun handleExam(id: Long, prefs: AppPrefs, now: LocalDateTime) {
        val exam = db.examDao().getById(id) ?: return
        if (prefs.examRemindersEnabled && exam.examType.name !in prefs.disabledExamTypes) {
            ExamPlanner.due(exam, prefs, now)?.let { r ->
                val built = ReminderContent.examReminder(applicationContext, exam, r, prefs)
                NotificationHelper.post(
                    applicationContext, NotificationHelper.examId(exam.id), NotificationHelper.CH_EXAMS,
                    built.title, built.body, built.bigText, subText = exam.typeLabel,
                    contentIntent = NotificationHelper.openAppIntent(applicationContext, tab = 2)
                )
            }
        }
        ReminderScheduler.scheduleExam(applicationContext, exam, prefs)
    }

    private suspend fun handleWeekend(prefs: AppPrefs, now: LocalDateTime) {
        if (prefs.weekendPrep && now.dayOfWeek.value in prefs.weekendDays) {
            val exams = db.examDao().getAll().filter { WeekendPlanner.covers(it, now.toLocalDate(), prefs) }
            // Use the slot that just fired so the right message kind is chosen.
            val slotMinutes = listOf(prefs.weekendMorningMinutes, prefs.weekendEveningMinutes)
                .minByOrNull { kotlin.math.abs(it - (now.hour * 60 + now.minute)) } ?: prefs.weekendMorningMinutes
            val slot = now.toLocalDate().atTime(slotMinutes / 60, slotMinutes % 60)
            ReminderContent.weekend(applicationContext, slot, exams, prefs)?.let { built ->
                NotificationHelper.post(
                    applicationContext, NotificationHelper.ID_WEEKEND, NotificationHelper.CH_WEEKEND,
                    built.title, built.body, built.bigText,
                    contentIntent = NotificationHelper.openAppIntent(applicationContext, tab = 2)
                )
            }
        }
        ReminderScheduler.scheduleGlobal(applicationContext, prefs)
    }

    private suspend fun handleMorning(prefs: AppPrefs, now: LocalDateTime) {
        if (prefs.morningSummary) {
            val date = now.toLocalDate()
            val holiday = ClassCalendar.holidayOn(date, db.holidayDao().getAll())
            val classes = if (holiday != null) emptyList() else activeClassesOn(date, prefs)
            val anyScheduled = db.scheduleDao().getAll().any { ClassCalendar.isScheduled(it, date, prefs) }
            val exams = db.examDao().getAll().filter { !it.date.isBefore(date) && it.date.isBefore(date.plusDays(46)) }
            // Skip empty days with nothing to say (e.g. Sundays without exams).
            if (holiday != null || classes.isNotEmpty() || exams.isNotEmpty() || anyScheduled) {
                val built = ReminderContent.morningSummary(applicationContext, date, classes, exams, holiday, prefs)
                NotificationHelper.post(
                    applicationContext, NotificationHelper.ID_MORNING, NotificationHelper.CH_SUMMARY,
                    built.title, built.body, built.bigText
                )
            }
        }
        ReminderScheduler.scheduleGlobal(applicationContext, prefs)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NotificationHelper.FOREGROUND_ID, NotificationHelper.workerNotification(applicationContext))
}
