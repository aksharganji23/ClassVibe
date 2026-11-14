package com.classvibe.app.notifications

import android.content.Context
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AttendanceRecord
import com.classvibe.app.data.AttendanceStats
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.Exam
import com.classvibe.app.data.ExamType
import com.classvibe.app.data.Holiday
import com.classvibe.app.data.MessageStyle
import com.classvibe.app.messages.MessageBank
import com.classvibe.app.messages.MessageBank.Choice
import com.classvibe.app.parser.TimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Builds the text of every notification type from the message bank + live data. */
object ReminderContent {

    data class Built(val title: String, val body: String, val bigText: String, val messageId: String?)

    private val DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

    private fun room(r: String) = r.removePrefix("Room ").ifBlank { "TBA" }

    private fun classVars(c: ClassSchedule, lead: Int) = mapOf(
        "course" to c.course,
        "class_type" to c.classType.ifBlank { "Class" },
        "room" to room(c.room),
        "instructor" to c.instructor.ifBlank { "your professor" },
        "start_time" to TimeUtils.formatFriendly(c.startMinutes),
        "end_time" to TimeUtils.formatFriendly(c.endMinutes),
        "when" to TimeUtils.whenText(lead)
    )

    private fun details(c: ClassSchedule): String = buildString {
        append("📘 ").append(c.course)
        append("\n🏷️ ").append(c.classType.ifBlank { "Class" }).append("  •  🕒 ").append(c.timeRangeText)
        if (c.room.isNotBlank()) append("\n📍 Room ").append(room(c.room))
        if (c.instructor.isNotBlank()) append("\n👤 ").append(c.instructor)
    }

    fun courseCategory(course: String): String? {
        val c = " " + course.lowercase() + " "
        return when {
            "algorithm" in c -> "COURSE_ALGO"
            "artificial" in c || "deep learning" in c || "machine learning" in c || " ai " in c || "neural" in c -> "COURSE_AI"
            "software" in c -> "COURSE_SE"
            "aptitude" in c || "quantitative" in c || "reasoning" in c -> "COURSE_APTITUDE"
            "critical" in c || "interpersonal" in c || "soft skill" in c || "communication" in c -> "COURSE_CRITICAL"
            else -> null
        }
    }

    // ------------------------------------------------------------ class reminder

    suspend fun classReminder(
        context: Context,
        c: ClassSchedule,
        lead: Int,
        date: LocalDate,
        prefs: AppPrefs,
        todaysActive: List<ClassSchedule>,
        attendance: List<AttendanceRecord>
    ): Built {
        val vars = classVars(c, lead).toMutableMap()
        val type = c.classType.lowercase()
        val choices = mutableListOf<Choice>()
        choices += Choice(
            when {
                "lab" in type || "practical" in type -> "CLASS_LAB"
                "tut" in type -> "CLASS_TUTORIAL"
                else -> "CLASS_LECTURE"
            }, 3.0
        )
        when {
            lead == 0 -> choices += Choice("CLASS_NOW", 3.0)
            lead in 1..5 -> choices += Choice("CLASS_SOON", 2.0)
            lead >= 30 -> choices += Choice("CLASS_EARLY", 2.5)
        }
        if (c.startMinutes < 10 * 60) choices += Choice("TOD_MORNING", 1.0)
        if (c.startMinutes in (13 * 60) until (15 * 60 + 30)) choices += Choice("TOD_AFTERNOON", 1.0)
        if (todaysActive.lastOrNull()?.id == c.id && todaysActive.size > 1) choices += Choice("TOD_LAST", 1.5)
        if (date.dayOfWeek == DayOfWeek.MONDAY) choices += Choice("DAY_MONDAY", 0.8)
        if (date.dayOfWeek == DayOfWeek.FRIDAY) choices += Choice("DAY_FRIDAY", 0.8)
        courseCategory(c.course)?.let { choices += Choice(it, 2.0) }

        val courseStats = AttendanceStats.byCourse(attendance.filter { it.course == c.course }).firstOrNull()
        if (courseStats != null && courseStats.isLow) {
            choices += Choice("ATTEND_LOW", 2.5)
            vars["pct"] = courseStats.percent.toString()
        }
        val streak = AttendanceStats.streakDays(attendance, date.minusDays(1))
        if (streak >= 3) {
            choices += Choice("ATTEND_STREAK", 1.0)
            vars["streak"] = streak.toString()
        }

        val title = MessageBank.render(
            if (lead == 0) "🔔 {course} is starting now"
            else listOf("⏰ {course} {when}", "🔔 {class_type}: {course}", "📚 {course} at {start_time}", "⚡ Heads up — {course}").random(),
            vars
        )
        val line = MessageBank.pick(context, choices, prefs.messageStyle, "CLASS")
        val body = if (line == null) {
            "${c.classType} • ${c.timeRangeText}" + (if (c.room.isNotBlank()) " • Room ${room(c.room)}" else "") +
                (if (c.instructor.isNotBlank()) " • ${c.instructor}" else "")
        } else MessageBank.render(line.text, vars)
        line?.let { MessageBank.log(context, it.id, body) }
        return Built(title, body, body + "\n\n" + details(c), line?.id)
    }

    // ------------------------------------------------------------ class ending

    suspend fun classEnd(context: Context, c: ClassSchedule, next: ClassSchedule?, prefs: AppPrefs): Built {
        val vars = classVars(c, 5).toMutableMap()
        val choices: List<Choice>
        val title: String
        if (next != null) {
            vars["next_course"] = next.course
            vars["next_room"] = room(next.room)
            vars["next_time"] = TimeUtils.formatFriendly(next.startMinutes)
            val otherBlock = next.room.isNotBlank() && c.room.isNotBlank() &&
                ClassCalendar.blockOf(next.room) != ClassCalendar.blockOf(c.room)
            choices = listOf(Choice(if (otherBlock) "NEXT_BLOCK" else "CLASS_END"))
            title = if (otherBlock) "🏃 Next class is in another block" else "⏭️ Next: ${next.course} at ${TimeUtils.formatFriendly(next.startMinutes)}"
        } else {
            choices = listOf(Choice("CLASS_END_LAST"))
            title = "🏁 ${c.course} is wrapping up"
        }
        val line = MessageBank.pick(context, choices, prefs.messageStyle, "CLASS")
        val body = line?.let { MessageBank.render(it.text, vars) }
            ?: (if (next != null) "Next: ${next.course} at ${next.startTimeText}, Room ${room(next.room)}" else "That was the last class today.")
        line?.let { MessageBank.log(context, it.id, body) }
        return Built(title, body, "$body\n\nDid you attend ${c.course}? Mark it below 👇", line?.id)
    }

    // ------------------------------------------------------------ exams

    private fun examCategory(exam: Exam, r: ExamReminder): String = when {
        r.kind == ExamReminderKind.EXAM_MORNING -> "EXAM_DAY"
        else -> when (exam.examType) {
            ExamType.SEMESTER -> when {
                r.daysLeft > 30 -> "EXAM_SEM_FAR"
                r.daysLeft > 7 -> "EXAM_SEM_NEAR"
                else -> "EXAM_SEM_FINAL"
            }
            ExamType.MID, ExamType.OTHER -> "EXAM_MID"
            ExamType.LAB -> "EXAM_LAB"
            ExamType.SLIP_TEST -> "EXAM_SLIP"
            ExamType.QUIZ, ExamType.VIVA -> "EXAM_QUIZ"
            ExamType.ASSIGNMENT -> "EXAM_ASSIGN"
        }
    }

    suspend fun examReminder(context: Context, exam: Exam, r: ExamReminder, prefs: AppPrefs): Built {
        val countdown = ExamPlanner.countdownText(r.daysLeft, r.kind)
        val vars = mapOf(
            "exam" to exam.typeLabel.lowercase(),
            "course" to exam.course,
            "countdown" to countdown,
            "days" to ExamPlanner.daysText(r.daysLeft),
            "date" to exam.date.format(DATE_FMT),
            "time" to TimeUtils.formatFriendly(exam.timeMinutes),
            "room" to room(exam.room),
            "emoji" to exam.examType.emoji
        )
        val title = if (r.kind == ExamReminderKind.EXAM_MORNING) "🍀 Today: ${exam.typeLabel} – ${exam.course}"
        else "${exam.examType.emoji} ${exam.typeLabel}: ${exam.course} $countdown"
        val line = MessageBank.pick(context, listOf(Choice(examCategory(exam, r))), prefs.messageStyle, "EXAM", scope = "exam${exam.id}")
        val body = line?.let { MessageBank.render(it.text, vars) }
            ?: "${exam.typeLabel} for ${exam.course} $countdown (${exam.date.format(DATE_FMT)}, ${TimeUtils.formatFriendly(exam.timeMinutes)})."
        line?.let { MessageBank.log(context, it.id, body) }
        val big = buildString {
            append(body)
            append("\n\n📅 ").append(exam.date.format(DATE_FMT)).append(" • ").append(TimeUtils.formatFriendly(exam.timeMinutes))
            if (exam.room.isNotBlank()) append("\n📍 Room ").append(room(exam.room))
            if (exam.notes.isNotBlank()) append("\n📝 ").append(exam.notes)
        }
        return Built(title, body, big, line?.id)
    }

    // ------------------------------------------------------------ weekend prep

    /** Returns null when there's nothing to say (no exams and "no exams" message disabled). */
    suspend fun weekend(context: Context, slot: LocalDateTime, exams: List<Exam>, prefs: AppPrefs): Built? {
        val kind = WeekendPlanner.kindOf(slot, prefs)
        if (exams.isEmpty()) {
            if (!prefs.weekendNoExamMessage || kind != WeekendPlanner.SlotKind.OVERVIEW) return null
            val line = MessageBank.pick(context, listOf(Choice("WEEKEND_FREE")), prefs.messageStyle, "WEEKEND")
            val body = line?.text ?: "No exams next week. Enjoy your weekend!"
            return Built("😎 No exams next week", body, body, line?.id)
        }
        val sorted = exams.sortedWith(compareBy({ it.epochDay }, { it.timeMinutes }))
        val first = sorted.first()
        val vars = mapOf(
            "count" to sorted.size.toString(),
            "first_exam" to "${first.course} ${first.typeLabel.lowercase()}",
            "first_day" to first.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        )
        val category = when (kind) {
            WeekendPlanner.SlotKind.OVERVIEW -> "WEEKEND_OVERVIEW"
            WeekendPlanner.SlotKind.PROGRESS -> "WEEKEND_PROGRESS"
            WeekendPlanner.SlotKind.FOCUS -> "WEEKEND_FOCUS"
            WeekendPlanner.SlotKind.FINAL -> "WEEKEND_FINAL"
        }
        val title = when (kind) {
            WeekendPlanner.SlotKind.OVERVIEW -> "📚 Weekend prep: ${sorted.size} exam(s) coming up"
            WeekendPlanner.SlotKind.PROGRESS -> "📈 Weekend check-in"
            WeekendPlanner.SlotKind.FOCUS -> "☀️ Weekend focus"
            WeekendPlanner.SlotKind.FINAL -> "🌙 The week starts tomorrow"
        }
        val line = MessageBank.pick(context, listOf(Choice(category)), prefs.messageStyle, "WEEKEND")
        val body = line?.let { MessageBank.render(it.text, vars) } ?: "${sorted.size} exam(s) coming up. Time to prepare."
        line?.let { MessageBank.log(context, it.id, body) }
        val big = buildString {
            append(body).append("\n\n📚 Coming up:")
            sorted.take(8).forEach { e ->
                append("\n• ").append(e.examType.emoji).append(' ').append(e.course).append(" — ")
                    .append(e.typeLabel.lowercase()).append(", ").append(e.date.format(DATE_FMT))
            }
            if (kind == WeekendPlanner.SlotKind.OVERVIEW) {
                val split = WeekendPlanner.studySplit(sorted, slot, prefs)
                if (split.isNotBlank()) append("\n\n🗓️ Plan: ").append(split)
            }
        }
        return Built(title, body, big, line?.id)
    }

    // ------------------------------------------------------------ morning summary

    fun festivalCategory(date: LocalDate): String? {
        val md = date.monthValue * 100 + date.dayOfMonth
        val special = mapOf(
            LocalDate.of(2027, 10, 29) to "FESTIVAL_DIWALI",
            LocalDate.of(2028, 10, 17) to "FESTIVAL_DIWALI",
            LocalDate.of(2027, 3, 22) to "FESTIVAL_HOLI",
            LocalDate.of(2028, 3, 11) to "FESTIVAL_HOLI"
        )
        special[date]?.let { return it }
        return when (md) {
            101 -> "FESTIVAL_NEWYEAR"
            114, 115 -> "FESTIVAL_SANKRANTI"
            1225 -> "FESTIVAL_CHRISTMAS"
            else -> null
        }
    }

    suspend fun morningSummary(
        context: Context,
        date: LocalDate,
        classes: List<ClassSchedule>,
        upcomingExams: List<Exam>,
        holiday: Holiday?,
        prefs: AppPrefs
    ): Built {
        val vars = mapOf(
            "count" to classes.size.toString(),
            "first_course" to (classes.firstOrNull()?.course ?: ""),
            "first_time" to (classes.firstOrNull()?.let { TimeUtils.formatFriendly(it.startMinutes) } ?: "")
        )
        val festival = festivalCategory(date)?.let { cat ->
            MessageBank.pick(context, listOf(Choice(cat)), if (prefs.messageStyle == MessageStyle.PLAIN) MessageStyle.MIX else prefs.messageStyle, "SUMMARY")?.text
        }
        val (title, line) = when {
            holiday != null -> "🏖️ Holiday: ${holiday.label}" to null
            classes.isEmpty() -> "🎉 No classes today" to MessageBank.pick(context, listOf(Choice("MORNING_FREE")), prefs.messageStyle, "SUMMARY")
            else -> "☀️ Today: ${classes.size} class${if (classes.size == 1) "" else "es"}" to
                MessageBank.pick(context, listOf(Choice("MORNING_SUMMARY")), prefs.messageStyle, "SUMMARY")
        }
        val body = when {
            holiday != null -> "No class reminders today. Enjoy the break! 🎉"
            line != null -> MessageBank.render(line.text, vars)
            classes.isEmpty() -> "No classes today."
            else -> "${classes.size} class(es) today, first at ${classes.first().startTimeText}."
        }
        line?.let { MessageBank.log(context, it.id, body) }
        val big = buildString {
            festival?.let { append(it).append("\n\n") }
            append(body)
            if (holiday == null && classes.isNotEmpty()) {
                append("\n")
                classes.forEach { c ->
                    append("\n• ").append(c.startTimeText).append("  ").append(c.course)
                    if (c.room.isNotBlank()) append(" (").append(room(c.room)).append(")")
                }
            }
            val soon = upcomingExams.filter { !it.date.isBefore(date) }.sortedBy { it.epochDay }.take(3)
            if (soon.isNotEmpty()) {
                append("\n")
                soon.forEach { e ->
                    val days = java.time.temporal.ChronoUnit.DAYS.between(date, e.date)
                    append("\n").append(e.examType.emoji).append(' ').append(e.typeLabel).append(": ")
                        .append(e.course).append(' ').append(ExamPlanner.countdownText(days, ExamReminderKind.MILESTONE))
                }
            }
        }
        return Built(title, festival ?: body, big, line?.id)
    }
}
