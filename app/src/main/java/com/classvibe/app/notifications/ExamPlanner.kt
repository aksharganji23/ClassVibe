package com.classvibe.app.notifications

import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.Exam
import com.classvibe.app.data.ExamSchedule
import com.classvibe.app.data.ExamType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class ExamReminderKind { WEEKLY, MILESTONE, DAILY, DAY_BEFORE_MORNING, EVENING_BEFORE, EXAM_MORNING, HOURS_BEFORE }

data class ExamReminder(val at: LocalDateTime, val kind: ExamReminderKind, val daysLeft: Long)

/**
 * Works out every reminder for an exam:
 *
 * | Type        | Schedule                                                                   |
 * |-------------|----------------------------------------------------------------------------|
 * | Semester    | weekly + 90/60/45/30/20/15/10/7/5/3/2/1 days, daily final 7, exam morning   |
 * | Mid         | weekly + 30/20/15/10/7/5/3/2/1 days, daily final 3, exam morning            |
 * | Lab exam    | 7/3/1 days, evening before, exam morning                                   |
 * | Slip test   | morning + evening of the day before                                        |
 * | Quiz / Viva | evening before, morning of                                                 |
 * | Assignment  | 3 days, 1 day, 3 hours before the deadline                                 |
 * | Other       | 7/3/1 days, evening before, morning of                                     |
 *
 * One countdown per day (milestone > daily > weekly). Countdowns that fall on a weekend
 * day are merged into the weekend-prep summary when that summary covers the exam.
 */
object ExamPlanner {

    private val SEM_MILESTONES = listOf(90, 60, 45, 30, 20, 15, 10, 7, 5, 3, 2, 1)
    private val MID_MILESTONES = listOf(30, 20, 15, 10, 7, 5, 3, 2, 1)
    private val SHORT_MILESTONES = listOf(7, 3, 1)

    /** Built-in schedule per exam type (users can override milestones / daily / weekly in Settings). */
    fun defaultSchedule(type: ExamType): ExamSchedule = when (type) {
        ExamType.SEMESTER -> ExamSchedule(SEM_MILESTONES, 7, true)
        ExamType.MID -> ExamSchedule(MID_MILESTONES, 3, true)
        ExamType.LAB, ExamType.OTHER -> ExamSchedule(SHORT_MILESTONES, 0, false)
        ExamType.ASSIGNMENT -> ExamSchedule(listOf(3, 1), 0, false)
        ExamType.SLIP_TEST, ExamType.QUIZ, ExamType.VIVA -> ExamSchedule(emptyList(), 0, false)
    }

    fun scheduleFor(type: ExamType, prefs: AppPrefs): ExamSchedule = prefs.examSchedules[type.name] ?: defaultSchedule(type)

    /** Fixed extras that always apply, shown next to the editable part in Settings. */
    fun fixedExtras(type: ExamType): String = when (type) {
        ExamType.SEMESTER, ExamType.MID -> "+ exam morning"
        ExamType.LAB, ExamType.OTHER -> "+ evening before + exam morning"
        ExamType.SLIP_TEST -> "+ morning and evening of the day before"
        ExamType.QUIZ, ExamType.VIVA -> "+ evening before + morning of"
        ExamType.ASSIGNMENT -> "+ 3 hours before the deadline"
    }

    fun reminders(exam: Exam, prefs: AppPrefs): List<ExamReminder> {
        val examDate = exam.date
        val examAt = examDate.atTime(LocalTime.of(exam.timeMinutes / 60, exam.timeMinutes % 60))
        val countdown = LocalTime.of(prefs.examCountdownMinutes / 60, prefs.examCountdownMinutes % 60)
        val evening = LocalTime.of(prefs.examEveningMinutes / 60, prefs.examEveningMinutes % 60)
        val morningOfMinutes = minOf(prefs.examCountdownMinutes, exam.timeMinutes - 60).let {
            if (it < 6 * 60) maxOf(5 * 60, exam.timeMinutes - 30) else it
        }.coerceAtLeast(0)
        val morningOf = examDate.atTime(LocalTime.of(morningOfMinutes / 60, morningOfMinutes % 60))
        val schedule = scheduleFor(exam.examType, prefs)

        val list = mutableListOf<ExamReminder>()
        fun daysBefore(d: Int, kind: ExamReminderKind) {
            list += ExamReminder(examDate.minusDays(d.toLong()).atTime(countdown), kind, d.toLong())
        }
        // Editable part
        if (schedule.weekly) {
            var day = LocalDate.ofEpochDay(exam.createdEpochDay).plusWeeks(1)
            while (day.isBefore(examDate)) {
                list += ExamReminder(day.atTime(countdown), ExamReminderKind.WEEKLY, ChronoUnit.DAYS.between(day, examDate))
                day = day.plusWeeks(1)
            }
        }
        schedule.milestones.forEach { daysBefore(it, ExamReminderKind.MILESTONE) }
        (1..schedule.dailyFinal).forEach { daysBefore(it, ExamReminderKind.DAILY) }

        // Fixed part
        val eveningBefore = ExamReminder(examDate.minusDays(1).atTime(evening), ExamReminderKind.EVENING_BEFORE, 1)
        val examMorning = ExamReminder(morningOf, ExamReminderKind.EXAM_MORNING, 0)
        when (exam.examType) {
            ExamType.SEMESTER, ExamType.MID -> list += examMorning
            ExamType.LAB, ExamType.OTHER -> { list += eveningBefore; list += examMorning }
            ExamType.SLIP_TEST -> { daysBefore(1, ExamReminderKind.DAY_BEFORE_MORNING); list += eveningBefore }
            ExamType.QUIZ, ExamType.VIVA -> { list += eveningBefore; list += examMorning }
            ExamType.ASSIGNMENT -> list += ExamReminder(examAt.minusHours(3), ExamReminderKind.HOURS_BEFORE, 0)
        }

        // One countdown per day: milestone > daily > weekly.
        val countdownKinds = setOf(ExamReminderKind.WEEKLY, ExamReminderKind.MILESTONE, ExamReminderKind.DAILY)
        val priority = mapOf(ExamReminderKind.MILESTONE to 0, ExamReminderKind.DAILY to 1, ExamReminderKind.WEEKLY to 2)
        val countdowns = list.filter { it.kind in countdownKinds }
            .groupBy { it.at.toLocalDate() }
            .map { (_, group) -> group.minByOrNull { priority[it.kind] ?: 9 }!! }
        val others = list.filter { it.kind !in countdownKinds }

        return (countdowns + others)
            .filter { it.at.isBefore(examAt) }
            .filterNot { r ->
                // Merged into the weekend summary instead.
                r.kind in countdownKinds && prefs.weekendPrep &&
                    r.at.dayOfWeek.value in prefs.weekendDays &&
                    WeekendPlanner.covers(exam, r.at.toLocalDate(), prefs)
            }
            .distinctBy { it.at }
            .sortedBy { it.at }
    }

    fun next(exam: Exam, prefs: AppPrefs, now: LocalDateTime = LocalDateTime.now()): ExamReminder? =
        reminders(exam, prefs).firstOrNull { it.at.isAfter(now) }

    /** The reminder that just fired (within the last 45 minutes). */
    fun due(exam: Exam, prefs: AppPrefs, now: LocalDateTime = LocalDateTime.now()): ExamReminder? =
        reminders(exam, prefs).lastOrNull { !it.at.isAfter(now.plusMinutes(2)) && it.at.isAfter(now.minusMinutes(45)) }

    fun countdownText(daysLeft: Long, kind: ExamReminderKind): String = when {
        kind == ExamReminderKind.HOURS_BEFORE -> "in 3 hours"
        daysLeft <= 0 -> "today"
        daysLeft == 1L -> "tomorrow"
        else -> "in $daysLeft days"
    }

    fun daysText(daysLeft: Long): String = when {
        daysLeft <= 0 -> "today"
        daysLeft == 1L -> "1 day"
        else -> "$daysLeft days"
    }
}

/** Weekend prep: every weekend, a summary of next week's exams (and big exams two weeks out). */
object WeekendPlanner {

    enum class SlotKind { OVERVIEW, PROGRESS, FOCUS, FINAL }

    fun covers(exam: Exam, weekendDate: LocalDate, prefs: AppPrefs): Boolean {
        val nextMonday = weekendDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val end = nextMonday.plusDays(7L * prefs.weekendLookaheadWeeks - 1)
        val bigEnd = nextMonday.plusDays(13)
        val d = exam.date
        if (d.isBefore(nextMonday)) return false
        return !d.isAfter(end) || ((exam.examType == ExamType.SEMESTER || exam.examType == ExamType.MID) && !d.isAfter(bigEnd))
    }

    private fun slotTimes(prefs: AppPrefs): List<LocalTime> =
        listOf(prefs.weekendMorningMinutes, prefs.weekendEveningMinutes).distinct().sorted()
            .map { LocalTime.of(it / 60, it % 60) }

    fun nextSlot(prefs: AppPrefs, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (!prefs.weekendPrep || prefs.weekendDays.isEmpty()) return null
        for (offset in 0L..8L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek.value !in prefs.weekendDays) continue
            for (t in slotTimes(prefs)) {
                val at = date.atTime(t)
                if (at.isAfter(now)) return at
            }
        }
        return null
    }

    /** Consecutive weekend days around [date] (e.g. Sat+Sun). */
    fun blockDays(date: LocalDate, prefs: AppPrefs): List<LocalDate> {
        var start = date
        while (start.minusDays(1).dayOfWeek.value in prefs.weekendDays && start.minusDays(1) != date.minusDays(7)) start = start.minusDays(1)
        val days = mutableListOf<LocalDate>()
        var d = start
        while (d.dayOfWeek.value in prefs.weekendDays && days.size < 7) { days += d; d = d.plusDays(1) }
        return days
    }

    fun kindOf(slot: LocalDateTime, prefs: AppPrefs): SlotKind {
        val slots = blockDays(slot.toLocalDate(), prefs).flatMap { day -> slotTimes(prefs).map { day.atTime(it) } }
        val index = slots.indexOf(slot)
        return when {
            index <= 0 -> SlotKind.OVERVIEW
            index == slots.size - 1 -> SlotKind.FINAL
            slot.toLocalTime().hour >= 15 -> SlotKind.PROGRESS
            else -> SlotKind.FOCUS
        }
    }

    /** "Sat: Algorithms, AI • Sun: Software Engineering" */
    fun studySplit(exams: List<Exam>, slot: LocalDateTime, prefs: AppPrefs): String {
        val days = blockDays(slot.toLocalDate(), prefs).filter { !it.isBefore(slot.toLocalDate()) }
        if (days.isEmpty() || exams.isEmpty()) return ""
        val buckets = days.associateWith { mutableListOf<String>() }
        exams.sortedBy { it.epochDay }.forEachIndexed { i, e -> buckets[days[i % days.size]]?.add(e.course) }
        return buckets.filter { it.value.isNotEmpty() }.entries.joinToString(" • ") { (day, courses) ->
            day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + ": " + courses.distinct().joinToString(", ")
        }
    }
}
