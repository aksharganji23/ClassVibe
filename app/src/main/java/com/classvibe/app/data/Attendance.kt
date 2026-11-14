package com.classvibe.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AttendanceStatus(val label: String, val emoji: String) {
    ATTENDED("Attended", "✅"),
    MISSED("Missed", "❌"),
    CANCELLED("Cancelled", "🚫");

    companion object {
        fun from(v: String) = entries.firstOrNull { it.name == v } ?: ATTENDED
    }
}

/** One mark per class occurrence (class id + date). The course name is copied so stats survive edits. */
@Entity(
    tableName = "attendance",
    indices = [Index(value = ["classId", "epochDay"], unique = true)]
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val classId: Long,
    val course: String,
    val epochDay: Long,
    val status: String
) {
    val attendanceStatus: AttendanceStatus get() = AttendanceStatus.from(status)
}

/** A holiday / break: no class reminders between start and end (inclusive). */
@Entity(tableName = "holiday")
data class Holiday(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val label: String
) {
    fun contains(epochDay: Long) = epochDay in startEpochDay..endEpochDay
}

/** A notification message written by the user. category = CLASS, EXAM, WEEKEND or SUMMARY. */
@Entity(tableName = "custom_message")
data class CustomMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val text: String
)
