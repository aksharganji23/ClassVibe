package com.classvibe.app.parser

import kotlin.math.max
import kotlin.math.min

/**
 * Small, dependency-free fuzzy matching used to fix OCR spelling mistakes
 * ("A1gorithms" → "Algorithms", "Softvvare" → "Software").
 */
object FuzzyMatcher {

    /** Lower-cases and undoes common OCR confusions so similar strings compare equal. */
    fun normalize(s: String): String = s.lowercase()
        .replace("vv", "w")
        .replace("rn", "m")
        .replace("0", "o")
        .replace("1", "l")
        .replace("|", "l")
        .replace("5", "s")
        .replace("&", "and")
        .filter { it.isLetterOrDigit() }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0.0 … 1.0 similarity after OCR normalisation. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
    }

    data class Match(val value: String, val score: Double)

    fun best(input: String, candidates: Collection<String>): Match? =
        candidates.filter { it.isNotBlank() }
            .map { Match(it, similarity(input, it)) }
            .maxByOrNull { it.score }

    private val STOP_WORDS = setOf("and", "of", "the", "in", "for", "to", "with")

    /** "Artificial Intelligence and Deep Learning" → "AIDL". */
    fun initials(name: String): String =
        name.split(' ', '-', '&', '/').filter { it.isNotBlank() && it.lowercase() !in STOP_WORDS }
            .joinToString("") { it.first().uppercase() }

    /**
     * Best known subject for an OCR'd / abbreviated course name. Handles exact matches,
     * initials ("SESD"), truncated names ("Software Engineering…") and typos ("A1gorithms").
     */
    fun bestCourse(raw: String, candidates: Collection<String>): Match? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        candidates.firstOrNull { it.equals(input, ignoreCase = true) }?.let { return Match(it, 1.0) }
        val compact = input.filter { it.isLetter() }
        if (compact.length in 2..6 && input.none { it == ' ' } && compact.all { it.isUpperCase() }) {
            candidates.filter { initials(it) == compact }.singleOrNull()?.let { return Match(it, 0.95) }
        }
        val n = normalize(input)
        if (n.length >= 6) {
            val prefixed = candidates.filter { normalize(it).startsWith(n) }
            if (prefixed.size == 1) return Match(prefixed.first(), 0.9)
        }
        return best(input, candidates)
    }

    /**
     * Groups near-identical spellings found in one import and maps every
     * variant to the most common (then longest) spelling.
     */
    fun clusterCanonical(values: List<String>, threshold: Double = 0.84): Map<String, String> {
        val distinct = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val ordered = distinct.keys.sortedWith(compareByDescending<String> { distinct[it] ?: 0 }.thenByDescending { it.length })
        val canonical = mutableListOf<String>()
        val result = mutableMapOf<String, String>()
        for (v in ordered) {
            val match = canonical.firstOrNull { similarity(it, v) >= threshold }
            if (match == null) {
                canonical += v
                result[v] = v
            } else {
                result[v] = match
            }
        }
        return result
    }
}
