package com.classvibe.app.parser

import com.classvibe.app.data.Exam
import com.classvibe.app.data.ExamType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses exams from CSV:
 *   Exam Type,Course,Date,Time,Room[,Notes]
 *   Semester,Algorithms,2026-12-15,10:00,1205-BL1-SF,Units 1-5
 */
object ExamCsvParser {

    const val TEMPLATE = "Exam Type,Course,Date,Time,Room,Notes\n" +
        "Mid,Algorithms,2026-12-01,10:00,1205-BL1-SF,Units 1-3\n" +
        "Slip test,Artificial Intelligence and Deep Learning,2026-11-24,9:30,1205-BL1-SF,\n"

    private val formats = listOf(
        "yyyy-MM-dd", "dd-MM-yyyy", "d-M-yyyy", "dd/MM/yyyy", "d/M/yyyy", "dd.MM.yyyy",
        "d MMM yyyy", "dd MMM yyyy", "d MMMM yyyy", "MMM d yyyy", "MMM d, yyyy"
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun parseDate(text: String): LocalDate? {
        val t = text.trim()
        for (f in formats) {
            try { return LocalDate.parse(t, f) } catch (e: Exception) { /* try next format */ }
        }
        return null
    }

    data class Result(val exams: List<Exam>, val warnings: List<String>)

    fun parse(text: String, today: LocalDate = LocalDate.now()): Result {
        val rows = CsvTimetableParser.tokenize(text.removePrefix("﻿")).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return Result(emptyList(), listOf("The file is empty."))
        val header = rows.first().map { it.lowercase().filter { c -> c.isLetter() } }
        val hasHeader = header.any { it in setOf("examtype", "type", "course", "subject", "date") }
        fun idx(vararg names: String, default: Int): Int =
            if (!hasHeader) default else header.indexOfFirst { it in names }.takeIf { it >= 0 } ?: -1
        val iType = idx("examtype", "type", "exam", default = 0)
        val iCourse = idx("course", "subject", default = 1)
        val iDate = idx("date", default = 2)
        val iTime = idx("time", "starttime", default = 3)
        val iRoom = idx("room", "venue", default = 4)
        val iNotes = idx("notes", "syllabus", "remarks", default = 5)

        val warnings = mutableListOf<String>()
        val exams = (if (hasHeader) rows.drop(1) else rows).mapIndexedNotNull { i, row ->
            fun cell(k: Int) = if (k < 0) "" else row.getOrNull(k)?.trim().orEmpty()
            val date = parseDate(cell(iDate))
            val course = cell(iCourse)
            if (date == null || course.isBlank()) {
                warnings += "Row ${i + 1}: needs a course and a date (YYYY-MM-DD)."
                return@mapIndexedNotNull null
            }
            val rawType = cell(iType)
            val type = ExamType.from(rawType)
            Exam(
                type = type.name,
                customType = if (type == ExamType.OTHER) rawType else "",
                course = course,
                epochDay = date.toEpochDay(),
                timeMinutes = TimeUtils.parse(cell(iTime)) ?: (10 * 60),
                room = cell(iRoom),
                instructor = "",
                notes = cell(iNotes),
                createdEpochDay = today.toEpochDay()
            )
        }
        return Result(exams, warnings)
    }
}
