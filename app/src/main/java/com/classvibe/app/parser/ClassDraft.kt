package com.classvibe.app.parser

import com.classvibe.app.data.ClassSchedule
import java.util.concurrent.atomic.AtomicLong

/** Which part of which imported image a row came from (for the side-by-side check). */
data class SourceRegion(val imageIndex: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class Confidence(val label: String) { HIGH("Looks right"), MEDIUM("Please check"), LOW("Needs a fix") }

/**
 * Editable, not-yet-validated version of a class row. Used by the import preview and the
 * edit dialog so users can fix OCR/CSV mistakes before saving.
 */
data class ClassDraft(
    val key: Long = nextKey(),
    val day: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val course: String = "",
    val classType: String = "Lecture",
    val room: String = "",
    val instructor: String = "",
    /** 0 = every week, 1 = odd weeks, 2 = even weeks. */
    val weekParity: Int = 0,
    /** Database id when editing an existing class (0 = new). */
    val existingId: Long = 0,
    /** Course text exactly as OCR read it (used to learn abbreviations / corrections). */
    val rawCourse: String = "",
    /** Reasons the importer wants a human to double-check this row. */
    val flags: List<String> = emptyList(),
    val region: SourceRegion? = null
) {
    fun dayError(): String? = if (DayParser.parse(day) == null) "Pick a day" else null

    fun startError(): String? =
        if (TimeUtils.parse(startTime) == null) "Use HH:mm (e.g. 09:30)" else null

    fun endError(): String? {
        val s = TimeUtils.parse(startTime)
        val e = TimeUtils.parse(endTime) ?: return "Use HH:mm (e.g. 10:30)"
        if (s != null && TimeUtils.fixRange(s, e).second <= s) return "Must be after start"
        return null
    }

    fun courseError(): String? = if (course.isBlank()) "Course is required" else null

    fun errors(): List<String> = listOfNotNull(dayError(), startError(), endError(), courseError())

    val isValid: Boolean get() = errors().isEmpty()

    val confidence: Confidence
        get() = when {
            !isValid -> Confidence.LOW
            flags.isNotEmpty() -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

    fun toEntityOrNull(): ClassSchedule? {
        if (!isValid) return null
        val (s, e) = TimeUtils.fixRange(TimeUtils.parse(startTime)!!, TimeUtils.parse(endTime)!!)
        return ClassSchedule(
            id = existingId,
            dayOfWeek = DayParser.parse(day)!!,
            startMinutes = s,
            endMinutes = e,
            course = course.trim(),
            classType = classType.trim().ifBlank { "Lecture" },
            room = room.trim(),
            instructor = instructor.trim(),
            weekParity = weekParity
        )
    }

    /** Stable identity for de-duplicating rows from several screenshots. */
    fun dedupeKey(): String =
        "${DayParser.parse(day)}|${TimeUtils.parse(startTime)}|${FuzzyMatcher.normalize(course)}"

    companion object {
        private val counter = AtomicLong(1)
        fun nextKey(): Long = counter.getAndIncrement()

        val PARITY_LABELS = listOf("Every week", "Odd weeks", "Even weeks")

        fun parityFrom(text: String): Int {
            val t = text.lowercase()
            return when {
                "odd" in t || t == "1" -> 1
                "even" in t || t == "2" -> 2
                else -> 0
            }
        }

        fun fromEntity(c: ClassSchedule) = ClassDraft(
            day = DayParser.name(c.dayOfWeek),
            startTime = TimeUtils.format(c.startMinutes),
            endTime = TimeUtils.format(c.endMinutes),
            course = c.course,
            classType = c.classType,
            room = c.room,
            instructor = c.instructor,
            weekParity = c.weekParity,
            existingId = c.id
        )
    }
}

/** Output of any parser: editable rows + human-readable warnings. */
data class ParseResult(
    val drafts: List<ClassDraft>,
    val warnings: List<String> = emptyList(),
    val source: String = "",
    /** Raw recognised text (OCR only) so the user can see what the camera "read". */
    val rawText: String? = null
)
