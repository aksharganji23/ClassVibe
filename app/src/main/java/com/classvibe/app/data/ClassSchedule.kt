package com.classvibe.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.classvibe.app.parser.TimeUtils
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * One row of the timetable. Mirrors the CSV schema:
 * Day, Start Time, End Time, Course, Class Type, Room, Instructor (+ optional Week)
 *
 * Times are minutes since midnight. dayOfWeek uses ISO numbering (1 = Monday … 7 = Sunday).
 * weekParity: 0 = every week, 1 = odd weeks only, 2 = even weeks only.
 */
@Entity(
    tableName = "class_schedule",
    indices = [Index(value = ["dayOfWeek", "startMinutes"])]
)
data class ClassSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val course: String,
    val classType: String,
    val room: String,
    val instructor: String,
    @ColumnInfo(defaultValue = "0") val weekParity: Int = 0
) {
    val dayName: String
        get() = DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.FULL, Locale.getDefault())

    val startTimeText: String get() = TimeUtils.format(startMinutes)
    val endTimeText: String get() = TimeUtils.format(endMinutes)
    val timeRangeText: String get() = "$startTimeText – $endTimeText"

    val parityLabel: String?
        get() = when (weekParity) {
            1 -> "Odd weeks"
            2 -> "Even weeks"
            else -> null
        }
}

/** Live status of a class relative to "now". */
enum class ClassStatus(val label: String) {
    UPCOMING("Upcoming"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    companion object {
        fun of(schedule: ClassSchedule, nowMinutes: Int): ClassStatus = when {
            nowMinutes < schedule.startMinutes -> UPCOMING
            nowMinutes < schedule.endMinutes -> IN_PROGRESS
            else -> COMPLETED
        }
    }
}
