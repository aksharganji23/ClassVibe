package com.classvibe.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Decides whether a class really happens on a date, taking into account
 * semester dates, odd/even weeks, holidays and one-off cancellations.
 */
object ClassCalendar {

    /** 1-based teaching week counted from the Monday of the semester's first week. */
    fun weekNumber(date: LocalDate, semesterStart: LocalDate): Long {
        val firstMonday = semesterStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return ChronoUnit.WEEKS.between(firstMonday, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) + 1
    }

    fun inSemester(date: LocalDate, prefs: AppPrefs): Boolean {
        val start = prefs.semesterStart
        val end = prefs.semesterEnd
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
    }

    fun parityMatches(c: ClassSchedule, date: LocalDate, prefs: AppPrefs): Boolean {
        if (c.weekParity == 0) return true
        val start = prefs.semesterStart ?: return true // unknown week numbering → treat as every week
        val odd = weekNumber(date, start) % 2L == 1L
        return if (c.weekParity == 1) odd else !odd
    }

    /** Scheduled on this date (ignores holidays and cancellations). */
    fun isScheduled(c: ClassSchedule, date: LocalDate, prefs: AppPrefs): Boolean =
        date.dayOfWeek.value == c.dayOfWeek && inSemester(date, prefs) && parityMatches(c, date, prefs)

    fun holidayOn(date: LocalDate, holidays: List<Holiday>): Holiday? =
        holidays.firstOrNull { it.contains(date.toEpochDay()) }

    /** Happens for real: scheduled, not a holiday and not cancelled. */
    fun isActive(
        c: ClassSchedule,
        date: LocalDate,
        prefs: AppPrefs,
        holidays: List<Holiday>,
        attendance: AttendanceRecord?
    ): Boolean = isScheduled(c, date, prefs) &&
        holidayOn(date, holidays) == null &&
        attendance?.attendanceStatus != AttendanceStatus.CANCELLED

    /** "1205-BL1-SF" → "BL1"; used to warn when the next class is in another block. */
    fun blockOf(room: String): String {
        val parts = room.uppercase().split('-', '_', ' ').filter { it.isNotBlank() }
        return parts.firstOrNull { p -> p.any { it.isLetter() } && p.any { it.isDigit() } && p.startsWith("B") }
            ?: parts.getOrNull(1)
            ?: room.uppercase()
    }
}
