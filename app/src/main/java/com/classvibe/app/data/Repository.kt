package com.classvibe.app.data

import android.content.Context
import com.classvibe.app.notifications.ReminderScheduler
import com.classvibe.app.widget.NowNextWidget
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Single entry point for writes. Every write keeps AlarmManager reminders
 * and the home-screen widget in sync with the database.
 */
class ClassVibeRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val classes = db.scheduleDao()
    private val exams = db.examDao()
    private val attendance = db.attendanceDao()
    private val holidays = db.holidayDao()
    private val customMessages = db.customMessageDao()

    fun observeClasses(): Flow<List<ClassSchedule>> = classes.observeAll()
    fun observeExams(): Flow<List<Exam>> = exams.observeAll()
    fun observeAttendance(): Flow<List<AttendanceRecord>> = attendance.observeAll()
    fun observeHolidays(): Flow<List<Holiday>> = holidays.observeAll()
    fun observeCustomMessages(): Flow<List<CustomMessage>> = customMessages.observeAll()

    suspend fun allClasses() = classes.getAll()

    // ---------- Timetable ----------

    suspend fun saveClasses(items: List<ClassSchedule>, replace: Boolean) {
        if (replace) {
            classes.getAll().forEach { ReminderScheduler.cancelClass(context, it) }
            classes.replaceAll(items.map { it.copy(id = 0) })
        } else {
            classes.insertAll(items.map { it.copy(id = 0) })
        }
        classes.getAll().forEach { ReminderScheduler.scheduleClass(context, it) }
        ReminderScheduler.scheduleGlobal(context)
        NowNextWidget.requestUpdate(context)
    }

    suspend fun updateClass(item: ClassSchedule) {
        classes.update(item)
        ReminderScheduler.scheduleClass(context, item)
        NowNextWidget.requestUpdate(context)
    }

    suspend fun deleteClass(item: ClassSchedule) {
        ReminderScheduler.cancelClass(context, item)
        classes.delete(item)
        NowNextWidget.requestUpdate(context)
    }

    suspend fun clearTimetable() {
        classes.getAll().forEach { ReminderScheduler.cancelClass(context, it) }
        classes.deleteAll()
        NowNextWidget.requestUpdate(context)
    }

    // ---------- Exams ----------

    suspend fun saveExam(exam: Exam): Long {
        val id = if (exam.id == 0L) exams.insert(exam) else { exams.update(exam); exam.id }
        exams.getById(id)?.let { ReminderScheduler.scheduleExam(context, it) }
        ReminderScheduler.scheduleGlobal(context)
        NowNextWidget.requestUpdate(context)
        return id
    }

    suspend fun saveExams(list: List<Exam>) {
        exams.insertAll(list.map { it.copy(id = 0) })
        exams.getAll().forEach { ReminderScheduler.scheduleExam(context, it) }
        ReminderScheduler.scheduleGlobal(context)
        NowNextWidget.requestUpdate(context)
    }

    suspend fun deleteExam(exam: Exam) {
        ReminderScheduler.cancelExam(context, exam.id)
        exams.delete(exam)
        NowNextWidget.requestUpdate(context)
    }

    // ---------- Attendance ----------

    suspend fun mark(c: ClassSchedule, date: LocalDate, status: AttendanceStatus?) {
        markById(context, c.id, c.course, date.toEpochDay(), status)
    }

    // ---------- Holidays ----------

    suspend fun addHoliday(start: LocalDate, end: LocalDate, label: String) {
        val (s, e) = if (end.isBefore(start)) end to start else start to end
        holidays.insert(Holiday(startEpochDay = s.toEpochDay(), endEpochDay = e.toEpochDay(), label = label.ifBlank { "Holiday" }))
        NowNextWidget.requestUpdate(context)
    }

    suspend fun deleteHoliday(h: Holiday) {
        holidays.delete(h)
        NowNextWidget.requestUpdate(context)
    }

    suspend fun addHolidays(list: List<Holiday>) {
        if (list.isEmpty()) return
        holidays.insertAll(list.map { it.copy(id = 0) })
        NowNextWidget.requestUpdate(context)
    }

    /** Applies a QR merge plan: only adds things, never changes existing data. */
    suspend fun applyMerge(plan: MergePlan, clashesToAdd: List<ClassSchedule>, includeAttendance: Boolean, today: java.time.LocalDate) {
        val toAdd = plan.newClasses + clashesToAdd
        if (toAdd.isNotEmpty()) saveClasses(toAdd, replace = false)
        if (plan.newExams.isNotEmpty()) saveExams(plan.newExams.map { it.copy(createdEpochDay = today.toEpochDay()) })
        addHolidays(plan.newHolidays)
        AppSettings.update(context) { p ->
            plan.setSemesterStart?.let { p[AppSettings.Keys.SEM_START] = it }
            plan.setSemesterEnd?.let { p[AppSettings.Keys.SEM_END] = it }
        }
        plan.newSubjects.forEach { AppSettings.addCustomSubject(context, it) }
        if (includeAttendance && plan.attendance.isNotEmpty()) {
            val mine = classes.getAll()
            plan.attendance.forEach { a ->
                val c = ShareMerge.matchClass(a, mine) ?: return@forEach
                if (attendance.get(c.id, a.epochDay) == null) {
                    attendance.upsert(AttendanceRecord(classId = c.id, course = c.course, epochDay = a.epochDay, status = a.status))
                }
            }
        }
        ReminderScheduler.scheduleGlobal(context)
        NowNextWidget.requestUpdate(context)
    }

    // ---------- Custom messages ----------

    suspend fun addCustomMessage(category: String, text: String) {
        if (text.isNotBlank()) customMessages.insert(CustomMessage(category = category, text = text.trim()))
    }

    suspend fun deleteCustomMessage(m: CustomMessage) = customMessages.delete(m)

    // ---------- Everything ----------

    suspend fun wipeAll() {
        clearTimetable()
        exams.getAll().forEach { ReminderScheduler.cancelExam(context, it.id) }
        exams.deleteAll()
        attendance.deleteAll()
        holidays.deleteAll()
        customMessages.deleteAll()
        NowNextWidget.requestUpdate(context)
    }

    companion object {
        /** Also used by the notification action receiver. null status = clear the mark. */
        suspend fun markById(context: Context, classId: Long, course: String, epochDay: Long, status: AttendanceStatus?) {
            val dao = AppDatabase.get(context).attendanceDao()
            if (status == null) {
                dao.clear(classId, epochDay)
            } else {
                dao.upsert(
                    AttendanceRecord(
                        id = dao.get(classId, epochDay)?.id ?: 0,
                        classId = classId,
                        course = course,
                        epochDay = epochDay,
                        status = status.name
                    )
                )
            }
            NowNextWidget.requestUpdate(context)
        }
    }
}
