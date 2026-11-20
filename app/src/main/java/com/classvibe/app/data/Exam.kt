package com.classvibe.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class ExamType(val label: String, val emoji: String, val defaultDuration: Int) {
    SEMESTER("Semester exam", "🎓", 180),
    MID("Mid exam", "📝", 90),
    LAB("Lab exam", "🔬", 180),
    SLIP_TEST("Slip test", "✍️", 30),
    QUIZ("Quiz", "❓", 30),
    VIVA("Viva", "🎤", 30),
    ASSIGNMENT("Assignment / submission", "📤", 0),
    OTHER("Other", "📌", 120);

    companion object {
        fun from(value: String): ExamType =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: entries.firstOrNull { it.label.equals(value.trim(), ignoreCase = true) }
                ?: parseLoose(value)

        /** Accepts loose CSV values such as "sem", "mid-1", "slip", "lab internal". */
        fun parseLoose(value: String): ExamType {
            val v = value.lowercase()
            return when {
                "sem" in v || "end" in v || "final" in v -> SEMESTER
                "mid" in v || "internal" in v && "lab" !in v -> MID
                "lab" in v || "practical" in v -> LAB
                "slip" in v || "class test" in v || "unit test" in v -> SLIP_TEST
                "quiz" in v -> QUIZ
                "viva" in v -> VIVA
                "assign" in v || "submission" in v || "project" in v || "record" in v -> ASSIGNMENT
                else -> OTHER
            }
        }
    }
}

/**
 * An exam / test / deadline. [customType] holds the user's own label when [type] is OTHER.
 */
@Entity(tableName = "exam")
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val customType: String,
    val course: String,
    val epochDay: Long,
    val timeMinutes: Int,
    val room: String,
    val instructor: String,
    val notes: String,
    val createdEpochDay: Long,
    /** Length in minutes; 0 = use the default for the exam type. */
    @androidx.room.ColumnInfo(defaultValue = "0") val durationMinutes: Int = 0
) {
    /** Duration actually used (custom or the type's default). */
    val effectiveDuration: Int get() = if (durationMinutes > 0) durationMinutes else examType.defaultDuration
    val startsAt: java.time.LocalDateTime get() = date.atStartOfDay().plusMinutes(timeMinutes.toLong())
    val endsAt: java.time.LocalDateTime get() = startsAt.plusMinutes(effectiveDuration.toLong())
    /** Finished = its end time has passed. */
    fun isOver(now: java.time.LocalDateTime): Boolean = !endsAt.isAfter(now)
    val examType: ExamType get() = ExamType.from(type)
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
    val typeLabel: String get() = if (examType == ExamType.OTHER && customType.isNotBlank()) customType else examType.label
}
