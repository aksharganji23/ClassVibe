package com.classvibe.app.data

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class CourseAttendance(val course: String, val attended: Int, val missed: Int, val cancelled: Int) {
    val total: Int get() = attended + missed
    val percent: Int get() = if (total == 0) 100 else (attended * 100.0 / total).toInt()
    val isLow: Boolean get() = total > 0 && percent < 75

    /** Classes you can still miss and stay at ≥ 75 %. */
    val canMiss: Int get() = max(0, floor(attended / 0.75 - total).toInt())

    /** Classes in a row you must attend to get back to 75 %. */
    val needToAttend: Int get() = if (!isLow) 0 else max(0, ceil((0.75 * total - attended) / 0.25).toInt())
}

object AttendanceStats {

    /**
     * Per-subject attendance. [baseline] adds the classes attended/held before the student
     * started marking in ClassVibe (Stats → ✏️ Edit), so percentages start from real numbers.
     */
    fun byCourse(records: List<AttendanceRecord>, baseline: Map<String, AttendanceBaseline> = emptyMap()): List<CourseAttendance> {
        val grouped = records.groupBy { it.course }
        val courses = (grouped.keys + baseline.keys).toSet()
        return courses.map { course ->
            val list = grouped[course].orEmpty()
            val b = baseline[course]
            CourseAttendance(
                course = course,
                attended = list.count { it.attendanceStatus == AttendanceStatus.ATTENDED } + (b?.attended ?: 0),
                missed = list.count { it.attendanceStatus == AttendanceStatus.MISSED } + (b?.let { it.held - it.attended } ?: 0),
                cancelled = list.count { it.attendanceStatus == AttendanceStatus.CANCELLED }
            )
        }.sortedBy { it.percent }
    }

    fun overall(records: List<AttendanceRecord>, baseline: Map<String, AttendanceBaseline> = emptyMap()): CourseAttendance =
        CourseAttendance(
            course = "Overall",
            attended = records.count { it.attendanceStatus == AttendanceStatus.ATTENDED } + baseline.values.sumOf { it.attended },
            missed = records.count { it.attendanceStatus == AttendanceStatus.MISSED } + baseline.values.sumOf { it.held - it.attended },
            cancelled = records.count { it.attendanceStatus == AttendanceStatus.CANCELLED }
        )

    /** Consecutive days (most recent first) where every marked class was attended. */
    fun streakDays(records: List<AttendanceRecord>, today: LocalDate = LocalDate.now()): Int {
        val byDay = records.filter { it.attendanceStatus != AttendanceStatus.CANCELLED && it.epochDay <= today.toEpochDay() }
            .groupBy { it.epochDay }
            .toSortedMap(Comparator.reverseOrder())
        var streak = 0
        for ((_, list) in byDay) {
            if (list.all { it.attendanceStatus == AttendanceStatus.ATTENDED }) streak++ else break
        }
        return streak
    }
}
