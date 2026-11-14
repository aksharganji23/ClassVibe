package com.classvibe.app.data

import com.classvibe.app.parser.FuzzyMatcher
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.random.Random

/** Attendance mark shared by QR, matched to the receiver's class by day + start time + subject. */
data class SharedAttendance(val day: Int, val start: Int, val course: String, val epochDay: Long, val status: String)

/** What a QR code carries. Every list is optional – the sender chooses what to include. */
data class SharePayload(
    val classes: List<ClassSchedule> = emptyList(),
    val exams: List<Exam> = emptyList(),
    val holidays: List<Holiday> = emptyList(),
    val semesterStart: Long? = null,
    val semesterEnd: Long? = null,
    val subjects: List<String> = emptyList(),
    val attendance: List<SharedAttendance> = emptyList()
) {
    val isEmpty: Boolean
        get() = classes.isEmpty() && exams.isEmpty() && holidays.isEmpty() && semesterStart == null &&
            semesterEnd == null && subjects.isEmpty() && attendance.isEmpty()

    fun summary(): String = listOfNotNull(
        classes.size.takeIf { it > 0 }?.let { "$it classes" },
        exams.size.takeIf { it > 0 }?.let { "$it exams" },
        holidays.size.takeIf { it > 0 }?.let { "$it holidays" },
        if (semesterStart != null || semesterEnd != null) "semester dates" else null,
        subjects.size.takeIf { it > 0 }?.let { "$it subjects" },
        attendance.size.takeIf { it > 0 }?.let { "$it attendance marks" }
    ).joinToString(", ").ifEmpty { "nothing" }
}

/**
 * Compact, offline QR format:
 *   single:  CV1:<base64url(deflate(text))>
 *   parts:   CV1P:<id>:<index>/<count>:<chunk>
 * The text is tab-separated lines: C(lasses) E(xams) H(olidays) S(emester) U(subjects) A(ttendance).
 */
object ShareCodec {

    const val SINGLE = "CV1:"
    const val PART = "CV1P:"
    const val CHUNK = 650

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun toText(p: SharePayload): String = buildString {
        p.classes.forEach { c ->
            append(listOf("C", c.dayOfWeek, c.startMinutes, c.endMinutes, clean(c.course), clean(c.classType), clean(c.room), clean(c.instructor), c.weekParity).joinToString("\t")).append('\n')
        }
        p.exams.forEach { e ->
            append(listOf("E", e.type, clean(e.customType), clean(e.course), e.epochDay, e.timeMinutes, clean(e.room), clean(e.instructor), clean(e.notes)).joinToString("\t")).append('\n')
        }
        p.holidays.forEach { h -> append(listOf("H", h.startEpochDay, h.endEpochDay, clean(h.label)).joinToString("\t")).append('\n') }
        if (p.semesterStart != null || p.semesterEnd != null) append("S\t${p.semesterStart ?: ""}\t${p.semesterEnd ?: ""}\n")
        p.subjects.forEach { append("U\t").append(clean(it)).append('\n') }
        p.attendance.forEach { a -> append(listOf("A", a.day, a.start, clean(a.course), a.epochDay, a.status).joinToString("\t")).append('\n') }
    }

    fun fromText(text: String): SharePayload {
        val classes = mutableListOf<ClassSchedule>()
        val exams = mutableListOf<Exam>()
        val holidays = mutableListOf<Holiday>()
        val subjects = mutableListOf<String>()
        val attendance = mutableListOf<SharedAttendance>()
        var semStart: Long? = null
        var semEnd: Long? = null
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "C" -> classes += ClassSchedule(0, f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4], f[5], f[6], f[7], f.getOrNull(8)?.toIntOrNull() ?: 0)
                    "E" -> exams += Exam(0, f[1], f[2], f[3], f[4].toLong(), f[5].toInt(), f[6], f[7], f.getOrNull(8).orEmpty(), f[4].toLong())
                    "H" -> holidays += Holiday(0, f[1].toLong(), f[2].toLong(), f[3])
                    "S" -> { semStart = f.getOrNull(1)?.toLongOrNull(); semEnd = f.getOrNull(2)?.toLongOrNull() }
                    "U" -> subjects += f[1]
                    "A" -> attendance += SharedAttendance(f[1].toInt(), f[2].toInt(), f[3], f[4].toLong(), f[5])
                }
            } catch (e: Exception) {
                // Skip a damaged line instead of failing the whole import.
            }
        }
        return SharePayload(classes, exams, holidays, semStart, semEnd, subjects, attendance)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(bytes); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        var guard = 0
        while (!i.finished() && guard++ < 10_000) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
        }
        i.end()
        return out.toByteArray()
    }

    fun encode(p: SharePayload): String =
        SINGLE + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(toText(p).toByteArray(Charsets.UTF_8)))

    fun decode(encoded: String): SharePayload {
        require(encoded.startsWith(SINGLE)) { "Not a ClassVibe QR code." }
        val bytes = Base64.getUrlDecoder().decode(encoded.removePrefix(SINGLE).trim())
        return fromText(String(inflate(bytes), Charsets.UTF_8))
    }

    /** One QR string, or several numbered parts when the data is big. */
    fun parts(p: SharePayload, chunk: Int = CHUNK): List<String> {
        val full = encode(p)
        if (full.length <= chunk) return listOf(full)
        val body = full.removePrefix(SINGLE)
        val pieces = body.chunked(chunk)
        val id = (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789"[Random.nextInt(31)] }.joinToString("")
        return pieces.mapIndexed { i, piece -> "$PART$id:${i + 1}/${pieces.size}:$piece" }
    }

    sealed class Scan {
        data class Complete(val payload: SharePayload) : Scan()
        data class Partial(val have: Int, val total: Int) : Scan()
        data class Invalid(val reason: String) : Scan()
    }

    /** Collects scanned parts in any order and decodes once everything is there. */
    class Assembler {
        private var id: String? = null
        private var total = 0
        private val pieces = mutableMapOf<Int, String>()

        fun reset() { id = null; total = 0; pieces.clear() }

        fun add(raw: String): Scan {
            val text = raw.trim()
            return try {
                when {
                    text.startsWith(SINGLE) -> Scan.Complete(decode(text)).also { reset() }
                    text.startsWith(PART) -> {
                        val rest = text.removePrefix(PART)
                        val pid = rest.substringBefore(':')
                        val counter = rest.substringAfter(':').substringBefore(':')
                        val chunk = rest.substringAfter(':').substringAfter(':')
                        val index = counter.substringBefore('/').toInt()
                        val count = counter.substringAfter('/').toInt()
                        if (id != pid) { reset(); id = pid; total = count }
                        pieces[index] = chunk
                        if (pieces.size == total) {
                            val joined = (1..total).joinToString("") { pieces[it] ?: "" }
                            Scan.Complete(decode(SINGLE + joined)).also { reset() }
                        } else Scan.Partial(pieces.size, total)
                    }
                    else -> Scan.Invalid("This QR code isn't from ClassVibe.")
                }
            } catch (e: Exception) {
                Scan.Invalid("Couldn't read this QR code: ${e.message}")
            }
        }
    }
}

/** How an incoming QR payload merges into local data. Nothing that already exists is changed. */
data class MergePlan(
    val newClasses: List<ClassSchedule>,
    val duplicateClasses: Int,
    val clashes: List<Pair<ClassSchedule, ClassSchedule>>, // incoming to existing
    val newExams: List<Exam>,
    val duplicateExams: Int,
    val newHolidays: List<Holiday>,
    val duplicateHolidays: Int,
    val setSemesterStart: Long?,
    val setSemesterEnd: Long?,
    val newSubjects: List<String>,
    val attendance: List<SharedAttendance>
) {
    val duplicates: Int get() = duplicateClasses + duplicateExams + duplicateHolidays

    fun summary(): String {
        val added = listOfNotNull(
            newClasses.size.takeIf { it > 0 }?.let { "$it classes" },
            newExams.size.takeIf { it > 0 }?.let { "$it exams" },
            newHolidays.size.takeIf { it > 0 }?.let { "$it holidays" },
            newSubjects.size.takeIf { it > 0 }?.let { "$it subjects" }
        ).joinToString(", ").ifEmpty { "nothing new" }
        val extra = listOfNotNull(
            duplicates.takeIf { it > 0 }?.let { "skipped $it duplicates" },
            clashes.size.takeIf { it > 0 }?.let { "$it clash${if (it == 1) "" else "es"}" }
        )
        return "Adds $added" + if (extra.isNotEmpty()) " • " + extra.joinToString(" • ") else ""
    }
}

object ShareMerge {

    private fun key(day: Int, start: Int, course: String) = "$day|$start|${FuzzyMatcher.normalize(course)}"

    fun plan(
        existingClasses: List<ClassSchedule>,
        existingExams: List<Exam>,
        existingHolidays: List<Holiday>,
        existingSubjects: Collection<String>,
        prefs: AppPrefs,
        incoming: SharePayload
    ): MergePlan {
        val existingKeys = existingClasses.map { key(it.dayOfWeek, it.startMinutes, it.course) }.toSet()
        val seen = mutableSetOf<String>()
        val newClasses = mutableListOf<ClassSchedule>()
        val clashes = mutableListOf<Pair<ClassSchedule, ClassSchedule>>()
        var dupClasses = 0
        for (c in incoming.classes) {
            val k = key(c.dayOfWeek, c.startMinutes, c.course)
            if (k in existingKeys || !seen.add(k)) { dupClasses++; continue }
            val clash = existingClasses.firstOrNull {
                it.dayOfWeek == c.dayOfWeek && it.startMinutes < c.endMinutes && c.startMinutes < it.endMinutes &&
                    (it.weekParity == 0 || c.weekParity == 0 || it.weekParity == c.weekParity)
            }
            if (clash != null) clashes += c to clash else newClasses += c
        }

        fun examKey(e: Exam) = "${e.course.trim().lowercase()}|${e.type}|${e.epochDay}"
        val examKeys = existingExams.map { examKey(it) }.toMutableSet()
        val newExams = incoming.exams.filter { examKeys.add(examKey(it)) }

        val holidayKeys = existingHolidays.map { "${it.startEpochDay}|${it.endEpochDay}" }.toMutableSet()
        val newHolidays = incoming.holidays.filter { holidayKeys.add("${it.startEpochDay}|${it.endEpochDay}") }

        val known = (existingSubjects + existingClasses.map { it.course } + newClasses.map { it.course }).map { it.trim().lowercase() }.toMutableSet()
        val newSubjects = incoming.subjects.map { it.trim() }.filter { it.isNotEmpty() && known.add(it.lowercase()) }

        return MergePlan(
            newClasses = newClasses,
            duplicateClasses = dupClasses,
            clashes = clashes,
            newExams = newExams,
            duplicateExams = incoming.exams.size - newExams.size,
            newHolidays = newHolidays,
            duplicateHolidays = incoming.holidays.size - newHolidays.size,
            setSemesterStart = incoming.semesterStart.takeIf { prefs.semesterStartEpochDay == null },
            setSemesterEnd = incoming.semesterEnd.takeIf { prefs.semesterEndEpochDay == null },
            newSubjects = newSubjects,
            attendance = incoming.attendance
        )
    }

    /** Matches a shared attendance mark to the receiver's own class (same day, start time and subject). */
    fun matchClass(a: SharedAttendance, classes: List<ClassSchedule>): ClassSchedule? =
        classes.firstOrNull { it.dayOfWeek == a.day && it.startMinutes == a.start && key(it.dayOfWeek, it.startMinutes, it.course) == key(a.day, a.start, a.course) }
}
