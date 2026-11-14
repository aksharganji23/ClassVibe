package com.classvibe.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Builds an iCalendar (.ics) file with classes (weekly, odd/even aware), exams and holidays.
 * UIDs are stable, so re-importing updates events instead of duplicating them in most calendar apps.
 */
object CalendarExport {

    data class Options(
        val classes: Boolean = true,
        val exams: Boolean = true,
        val holidays: Boolean = true,
        val reminders: Boolean = true
    )

    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val BYDAY = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU"
    )

    fun escape(s: String): String = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** RFC 5545 line folding (75 characters, continuation lines start with a space). */
    fun fold(line: String): String {
        if (line.length <= 75) return line
        val sb = StringBuilder()
        var i = 0
        var first = true
        while (i < line.length) {
            val size = if (first) 75 else 74
            val end = minOf(line.length, i + size)
            if (!first) sb.append("\r\n ")
            sb.append(line, i, end)
            i = end
            first = false
        }
        return sb.toString()
    }

    fun build(
        classes: List<ClassSchedule>,
        exams: List<Exam>,
        holidays: List<Holiday>,
        prefs: AppPrefs,
        options: Options,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        stamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    ): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//ClassVibe//Timetable//EN", "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH", "X-WR-CALNAME:ClassVibe", "X-WR-TIMEZONE:${zone.id}"
        )
        val dtStamp = "DTSTAMP:" + stamp.format(UTC)

        if (options.classes) {
            val start = prefs.semesterStart ?: today
            val end = prefs.semesterEnd ?: start.plusWeeks(26)
            val until = end.atTime(23, 59, 59).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(UTC)
            classes.forEach { c ->
                var first = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(c.dayOfWeek)))
                // Odd/even weeks: move to the first matching week and repeat every 2 weeks.
                if (c.weekParity != 0 && prefs.semesterStart != null) {
                    var guard = 0
                    while (!ClassCalendar.parityMatches(c, first, prefs) && guard++ < 3) first = first.plusWeeks(1)
                }
                if (first.isAfter(end)) return@forEach
                val interval = if (c.weekParity != 0 && prefs.semesterStart != null) ";INTERVAL=2" else ""
                lines += "BEGIN:VEVENT"
                lines += "UID:class-${c.id}@classvibe"
                lines += dtStamp
                lines += "DTSTART;TZID=${zone.id}:" + first.atTime(c.startMinutes / 60, c.startMinutes % 60).format(LOCAL)
                lines += "DTEND;TZID=${zone.id}:" + first.atTime(c.endMinutes / 60, c.endMinutes % 60).format(LOCAL)
                lines += "RRULE:FREQ=WEEKLY$interval;BYDAY=${BYDAY.getValue(DayOfWeek.of(c.dayOfWeek))};UNTIL=$until"
                // Skip holiday dates.
                val skipped = holidays.flatMap { h ->
                    generateSequence(LocalDate.ofEpochDay(h.startEpochDay)) { it.plusDays(1) }
                        .takeWhile { !it.isAfter(LocalDate.ofEpochDay(h.endEpochDay)) }.toList()
                }.filter { it.dayOfWeek.value == c.dayOfWeek && !it.isBefore(first) && !it.isAfter(end) }
                if (skipped.isNotEmpty()) {
                    lines += "EXDATE;TZID=${zone.id}:" + skipped.joinToString(",") { it.atTime(c.startMinutes / 60, c.startMinutes % 60).format(LOCAL) }
                }
                lines += "SUMMARY:" + escape("${c.course} (${c.classType})")
                if (c.room.isNotBlank()) lines += "LOCATION:" + escape(c.room)
                lines += "DESCRIPTION:" + escape(listOf(c.classType, c.instructor, c.parityLabel ?: "").filter { it.isNotBlank() }.joinToString(" • "))
                if (options.reminders) {
                    lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + escape(c.course), "TRIGGER:-PT10M", "END:VALARM")
                }
                lines += "END:VEVENT"
            }
        }

        if (options.exams) {
            exams.forEach { e ->
                val startAt = e.date.atTime(e.timeMinutes / 60, e.timeMinutes % 60)
                val hours = if (e.examType == ExamType.SEMESTER) 3L else if (e.examType == ExamType.SLIP_TEST || e.examType == ExamType.QUIZ) 1L else 2L
                lines += "BEGIN:VEVENT"
                lines += "UID:exam-${e.id}@classvibe"
                lines += dtStamp
                lines += "DTSTART;TZID=${zone.id}:" + startAt.format(LOCAL)
                lines += "DTEND;TZID=${zone.id}:" + startAt.plusHours(hours).format(LOCAL)
                lines += "SUMMARY:" + escape("${e.examType.emoji} ${e.typeLabel}: ${e.course}")
                if (e.room.isNotBlank()) lines += "LOCATION:" + escape(e.room)
                if (e.notes.isNotBlank()) lines += "DESCRIPTION:" + escape(e.notes)
                if (options.reminders) {
                    lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + escape(e.course), "TRIGGER:-P1D", "END:VALARM")
                    lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + escape(e.course), "TRIGGER:-PT1H", "END:VALARM")
                }
                lines += "END:VEVENT"
            }
        }

        if (options.holidays) {
            holidays.forEach { h ->
                lines += "BEGIN:VEVENT"
                lines += "UID:holiday-${h.id}@classvibe"
                lines += dtStamp
                lines += "DTSTART;VALUE=DATE:" + LocalDate.ofEpochDay(h.startEpochDay).format(DATE)
                lines += "DTEND;VALUE=DATE:" + LocalDate.ofEpochDay(h.endEpochDay).plusDays(1).format(DATE)
                lines += "SUMMARY:" + escape("🏖️ ${h.label}")
                lines += "TRANSP:TRANSPARENT"
                lines += "END:VEVENT"
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }
}
