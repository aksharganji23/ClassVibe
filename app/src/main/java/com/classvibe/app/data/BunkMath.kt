package com.classvibe.app.data

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Attendance maths for the calculator and the bunk planner. All pure functions. */
object BunkMath {

    fun percent(attended: Int, total: Int): Double = if (total <= 0) 100.0 else attended * 100.0 / total

    /** Classes you can still skip and stay at or above [target] %. */
    fun canBunk(attended: Int, total: Int, target: Int): Int {
        if (target <= 0) return Int.MAX_VALUE
        if (target > 100) return 0
        return max(0, floor(attended * 100.0 / target - total + 1e-9).toInt())
    }

    /** Classes in a row you must attend to reach [target] %. */
    fun needToAttend(attended: Int, total: Int, target: Int): Int {
        if (percent(attended, total) >= target) return 0
        if (target >= 100) return Int.MAX_VALUE
        return max(0, ceil((target * total - 100.0 * attended) / (100.0 - target) - 1e-9).toInt())
    }

    fun afterSkipping(attended: Int, total: Int, n: Int): Double = percent(attended, total + n)
    fun afterAttending(attended: Int, total: Int, n: Int): Double = percent(attended + n, total + n)

    enum class Level(val emoji: String) { SAFE("🟢"), RISKY("🟡"), DANGER("🔴") }

    fun level(pct: Double, target: Int): Level = when {
        pct >= target + 5 -> Level.SAFE
        pct >= target -> Level.RISKY
        else -> Level.DANGER
    }

    private val safeLines = listOf("Go ahead, sleep in 😴", "Bunk approved ✅ (we never said this)", "Your attendance can take it 😎", "Chill day unlocked 🛋️")
    private val riskyLines = listOf("Risky bro 😬", "You're walking on thin ice 🧊", "Possible… but your future self is sweating 😅", "One bunk away from trouble ⚠️")
    private val dangerLines = listOf("Absolutely not. Go to class 🚫", "Detention list is calling 📞", "Bro, your attendance is on a ventilator 🏥", "Nope. Get up. Go. 🏃")

    fun verdict(level: Level, seed: Int = 0): String {
        val list = when (level) { Level.SAFE -> safeLines; Level.RISKY -> riskyLines; Level.DANGER -> dangerLines }
        return list[Math.floorMod(seed, list.size)]
    }

    data class Counts(val attended: Int, val total: Int)

    data class CourseImpact(val course: String, val classes: Int, val before: Double, val after: Double, val level: Level)

    data class DayPlan(val date: LocalDate, val impacts: List<CourseImpact>) {
        val classCount: Int get() = impacts.sumOf { it.classes }
        val worst: Level get() = impacts.maxByOrNull { it.level.ordinal }?.level ?: Level.SAFE
        val isSafe: Boolean get() = classCount > 0 && worst != Level.DANGER
    }

    /**
     * What happens to each subject's attendance if you skip every real class on [date].
     * [isActive] decides whether a class actually happens that day (holidays, odd/even weeks, cancellations).
     */
    fun planDay(
        date: LocalDate,
        classes: List<ClassSchedule>,
        counts: Map<String, Counts>,
        target: Int,
        isActive: (ClassSchedule, LocalDate) -> Boolean
    ): DayPlan {
        val skipped = classes.filter { isActive(it, date) }.groupBy { it.course }
        val impacts = skipped.map { (course, list) ->
            val c = counts[course] ?: Counts(0, 0)
            val before = percent(c.attended, c.total)
            val after = afterSkipping(c.attended, c.total, list.size)
            CourseImpact(course, list.size, before, after, level(after, target))
        }.sortedBy { it.after }
        return DayPlan(date, impacts)
    }

    /** Upcoming days (with classes) you could skip entirely and still stay at or above target. */
    fun safeDays(
        from: LocalDate,
        days: Int,
        classes: List<ClassSchedule>,
        counts: Map<String, Counts>,
        target: Int,
        isActive: (ClassSchedule, LocalDate) -> Boolean
    ): List<DayPlan> = (0 until days).map { from.plusDays(it.toLong()) }
        .map { planDay(it, classes, counts, target, isActive) }
        .filter { it.isSafe }

    fun countsFrom(records: List<AttendanceRecord>, baseline: Map<String, AttendanceBaseline> = emptyMap()): Map<String, Counts> =
        AttendanceStats.byCourse(records, baseline).associate { it.course to Counts(it.attended, it.total) }
}
