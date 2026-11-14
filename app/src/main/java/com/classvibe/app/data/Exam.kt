package com.classvibe.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class ExamType(val label: String, val emoji: String) {
    SEMESTER("Semester exam", "🎓"),
    MID("Mid exam", "📝"),
    LAB("Lab exam", "🔬"),
    SLIP_TEST("Slip test", "✍️"),
    QUIZ("Quiz", "❓"),
    VIVA("Viva", "🎤"),
    ASSIGNMENT("Assignment / submission", "📤"),
    OTHER("Other", "📌");

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
    val createdEpochDay: Long
) {
    val examType: ExamType get() = ExamType.from(type)
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
    val typeLabel: String get() = if (examType == ExamType.OTHER && customType.isNotBlank()) customType else examType.label
}
