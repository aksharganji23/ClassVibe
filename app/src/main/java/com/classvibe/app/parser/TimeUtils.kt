package com.classvibe.app.parser

import java.time.DayOfWeek
import kotlin.math.abs

/** Helpers for turning loose timetable text ("9:30", "1:30 PM", "13.30", OCR "9.3O") into minutes. */
object TimeUtils {

    private val FIELD_REGEX =
        Regex("""^\s*(\d{1,2})(?:[:.](\d{2}))?(?::\d{2})?\s*([AaPp])?\.?\s*[Mm]?\.?\s*$""")

    /**
     * Parses a single time field. Returns minutes since midnight or null.
     * Bare hours 1–6 without AM/PM are read as PM when [assumeAfternoon] is true.
     */
    fun parse(text: String, assumeAfternoon: Boolean = true): Int? {
        val m = FIELD_REGEX.matchEntire(fixOcrDigits(text)) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3].lowercase()
        if (minute !in 0..59) return null
        when {
            ampm == "p" && hour in 1..11 -> hour += 12
            ampm == "a" && hour == 12 -> hour = 0
            ampm.isEmpty() && assumeAfternoon && hour in 1..6 -> hour += 12
        }
        if (hour !in 0..23) return null
        return hour * 60 + minute
    }

    /** OCR commonly confuses O/o→0, l/I/|→1, S→5, B→8 inside numbers like "9.3O" or "1l:30". */
    fun fixOcrDigits(text: String): String {
        val t = text.trim()
        // Only touch strings that already look mostly like a time.
        val digits = t.count { it.isDigit() }
        if (digits == 0) return t
        return buildString {
            t.forEachIndexed { i, ch ->
                val prevDigit = i > 0 && (t[i - 1].isDigit() || t[i - 1] == ':' || t[i - 1] == '.')
                val nextDigit = i < t.length - 1 && (t[i + 1].isDigit() || t[i + 1] == ':' || t[i + 1] == '.')
                val nearDigits = prevDigit || nextDigit
                append(
                    when {
                        !nearDigits -> ch
                        ch == 'O' || ch == 'o' || ch == 'D' -> '0'
                        ch == 'l' || ch == 'I' || ch == '|' || ch == 'i' -> '1'
                        ch == 'S' || ch == 's' -> '5'
                        ch == 'B' -> '8'
                        ch == ';' -> ':'
                        else -> ch
                    }
                )
            }
        }
    }

    /** If an end time lands before its start (e.g. 11:30 – 1:30), push it into the afternoon. */
    fun fixRange(start: Int, end: Int): Pair<Int, Int> {
        if (end > start) return start to end
        val shifted = end + 12 * 60
        return if (shifted in (start + 1) until 24 * 60) start to shifted else start to end
    }

    /** Moves [minutes] to the closest known slot time when within [tolerance] minutes. */
    fun snap(minutes: Int, slots: Collection<Int>, tolerance: Int = 10): Int {
        val best = slots.minByOrNull { abs(it - minutes) } ?: return minutes
        return if (abs(best - minutes) in 1..tolerance) best else minutes
    }

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** 12-hour label, e.g. "1:30 PM". */
    fun formatFriendly(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "%d:%02d %s".format(h12, m, suffix)
    }

    /** "in 10 mins", "in 1 hour", "right now". */
    fun whenText(lead: Int): String = when {
        lead <= 0 -> "right now"
        lead == 60 -> "in 1 hour"
        lead == 1 -> "in 1 min"
        else -> "in $lead mins"
    }
}

object DayParser {
    private val map = mapOf(
        "mon" to 1, "monday" to 1,
        "tue" to 2, "tues" to 2, "tuesday" to 2,
        "wed" to 3, "weds" to 3, "wednesday" to 3,
        "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4,
        "fri" to 5, "friday" to 5,
        "sat" to 6, "saturday" to 6,
        "sun" to 7, "sunday" to 7
    )

    /** Word-boundary regex that finds a day name anywhere inside a string. */
    val FIND_REGEX = Regex(
        """\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|weds|wed|thurs|thur|thu|fri|sat|sun)\b\.?""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Int? {
        val key = text.trim().trimEnd('.').lowercase()
        map[key]?.let { return it }
        // OCR-tolerant: "Mondav", "Tuesdav", "Wednesdy" → closest day name.
        if (key.length >= 5 && key.none { it.isDigit() }) {
            val best = map.keys.filter { it.length > 3 }
                .maxByOrNull { FuzzyMatcher.similarity(it, key) } ?: return null
            if (FuzzyMatcher.similarity(best, key) >= 0.75) return map[best]
        }
        return null
    }

    /** Exact day-name search inside longer text (no fuzzy matching, so surnames like "Mondal" are safe). */
    fun find(text: String): Int? = FIND_REGEX.find(text)?.value?.let { map[it.trimEnd('.').lowercase()] }

    fun name(day: Int): String =
        DayOfWeek.of(day).name.lowercase().replaceFirstChar { it.uppercase() }

    fun short(day: Int): String = name(day).take(3)

    val ALL_NAMES: List<String> = (1..7).map { name(it) }
}
