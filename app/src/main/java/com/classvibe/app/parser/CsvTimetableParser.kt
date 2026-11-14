package com.classvibe.app.parser

/**
 * Parses CSV with the header:
 *   Day,Start Time,End Time,Course,Class Type,Room,Instructor
 *
 * - Header names are matched case-insensitively and in any order
 *   (aliases like "Subject", "Faculty", "Venue" also work).
 * - If no header row exists, columns are read in the standard order.
 * - Quoted fields ("Dr. Banu, PhD"), escaped quotes and a UTF-8 BOM are handled.
 * - Blank "Day" cells inherit the day from the previous row (common in exports).
 */
object CsvTimetableParser {

    private enum class Col { DAY, START, END, COURSE, TYPE, ROOM, INSTRUCTOR, WEEK, TIME }

    private val aliases: Map<String, Col> = mapOf(
        "day" to Col.DAY, "weekday" to Col.DAY,
        "starttime" to Col.START, "start" to Col.START, "from" to Col.START,
        "endtime" to Col.END, "end" to Col.END, "to" to Col.END,
        "course" to Col.COURSE, "subject" to Col.COURSE, "coursename" to Col.COURSE,
        "classtype" to Col.TYPE, "type" to Col.TYPE, "sessiontype" to Col.TYPE,
        "room" to Col.ROOM, "venue" to Col.ROOM, "location" to Col.ROOM, "roomno" to Col.ROOM,
        "instructor" to Col.INSTRUCTOR, "faculty" to Col.INSTRUCTOR,
        "teacher" to Col.INSTRUCTOR, "professor" to Col.INSTRUCTOR, "lecturer" to Col.INSTRUCTOR,
        "week" to Col.WEEK, "weeks" to Col.WEEK, "frequency" to Col.WEEK,
        "time" to Col.TIME, "timing" to Col.TIME, "slot" to Col.TIME, "period" to Col.TIME
    )

    /** Downloadable template (also shown in the app). */
    const val TEMPLATE = "Day,Start Time,End Time,Course,Class Type,Room,Instructor,Week\n" +
        "Monday,9:30,10:30,Algorithms,Lecture,1205-BL1-SF,Dr. Sharmila Banu,Every\n" +
        "Monday,13:30,15:30,Software Engineering and System Design,Lab,1114-B_BL1-FF,Mr. Ashok Rachapalli,Odd\n"

    private val RANGE = Regex("""(\d{1,2}[:.]\d{2}\s*(?:[AaPp]\.?[Mm]\.?)?)\s*(?:-|–|—|to)\s*(\d{1,2}[:.]\d{2}\s*(?:[AaPp]\.?[Mm]\.?)?)""")

    private val defaultOrder = listOf(
        Col.DAY, Col.START, Col.END, Col.COURSE, Col.TYPE, Col.ROOM, Col.INSTRUCTOR
    )

    fun parse(text: String): ParseResult {
        val rows = tokenize(text.removePrefix("﻿"))
            .filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ParseResult(emptyList(), listOf("The file is empty."), "CSV")

        val warnings = mutableListOf<String>()
        val headerMap = detectHeader(rows.first())
        val columnOrder: Map<Col, Int>
        val dataRows: List<List<String>>
        if (headerMap != null) {
            columnOrder = headerMap
            dataRows = rows.drop(1)
            val missing = listOf(Col.DAY, Col.COURSE).filter { it !in headerMap } +
                (if (Col.TIME !in headerMap) listOf(Col.START, Col.END).filter { it !in headerMap } else emptyList())
            if (missing.isNotEmpty()) {
                warnings += "Header is missing: ${missing.joinToString { it.name.lowercase() }}."
            }
        } else {
            columnOrder = defaultOrder.withIndex().associate { (i, c) -> c to i }
            dataRows = rows
            warnings += "No header row found – assumed the standard column order."
        }

        var lastDay = ""
        val drafts = dataRows.mapIndexedNotNull { index, row ->
            fun cell(c: Col) = columnOrder[c]?.let { row.getOrNull(it) }?.trim().orEmpty()

            var day = cell(Col.DAY)
            if (day.isBlank()) day = lastDay else lastDay = day
            val parsedDay = DayParser.parse(day)
            if (parsedDay == null && day.isNotBlank()) {
                warnings += "Row ${index + 1}: couldn't understand day \"$day\"."
            }

            val course = cell(Col.COURSE)
            var start = cell(Col.START)
            var end = cell(Col.END)
            // A single "Time" column like "9:30 - 10:30".
            if (start.isBlank() && end.isBlank()) {
                RANGE.find(cell(Col.TIME))?.let { m -> start = m.groupValues[1]; end = m.groupValues[2] }
            }
            if (course.isBlank() && start.isBlank()) return@mapIndexedNotNull null

            ClassDraft(
                day = parsedDay?.let { DayParser.name(it) } ?: day,
                startTime = normalizeTime(start),
                endTime = normalizeTime(end),
                course = course,
                classType = cell(Col.TYPE).ifBlank { "Lecture" },
                room = cell(Col.ROOM),
                instructor = cell(Col.INSTRUCTOR),
                weekParity = ClassDraft.parityFrom(cell(Col.WEEK))
            )
        }

        val invalid = drafts.count { !it.isValid }
        if (invalid > 0) warnings += "$invalid row(s) need a quick fix before saving (highlighted in red)."
        return ParseResult(drafts, warnings, "CSV")
    }

    /** "9:30" -> "09:30", "1:30 PM" -> "13:30"; leaves unparseable text untouched for the user to fix. */
    private fun normalizeTime(raw: String): String =
        TimeUtils.parse(raw)?.let { TimeUtils.format(it) } ?: raw

    private fun detectHeader(first: List<String>): Map<Col, Int>? {
        val map = mutableMapOf<Col, Int>()
        first.forEachIndexed { i, name ->
            val key = name.lowercase().filter { it.isLetter() }
            aliases[key]?.let { col -> if (col !in map) map[col] = i }
        }
        // Treat as a header only if it clearly names at least two known columns.
        return if (map.size >= 2) map else null
    }

    /** RFC-4180-ish tokenizer: handles quotes, escaped quotes, CRLF and newlines inside quotes. */
    fun tokenize(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); rows.add(row)
        }
        return rows
    }
}
