package com.classvibe.app.parser

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** What the app already knows; used to auto-correct OCR mistakes. */
data class KnownData(
    val courses: Set<String> = emptySet(),
    val instructors: Set<String> = emptySet(),
    val rooms: Set<String> = emptySet(),
    val slotTimes: Set<Int> = emptySet(),
    val abbreviations: Map<String, String> = emptyMap()
)

/** OCR output plus the processed images (same coordinates as each draft's [SourceRegion]). */
data class OcrOutput(val result: ParseResult, val displayImages: List<Bitmap>)

/**
 * High-accuracy, fully on-device timetable reader (ML Kit bundled model, no internet).
 *
 * Pipeline per image:
 *  1. Upscale small screenshots, build an enhanced high-contrast grayscale copy.
 *  2. Straighten tilted photos (angle measured from recognised text lines).
 *  3. Two OCR passes (enhanced + original) – the better one wins, disagreements get flagged.
 *  4. Detect table ruling lines → assign every *word* to its exact cell (no column bleeding);
 *     borderless tables use consistent whitespace gaps instead.
 *  5. Detect merged cells (missing vertical line inside a row) → 2-hour labs get the right end time.
 *  6. Interpret the table: header-mapped rows, days × time-slot grid, transposed grid, or row heuristic.
 *  7. Clean up: OCR digit fixes, learned abbreviations, fuzzy subject/instructor/room matching,
 *     spelling clustering, snapping times to known slots, confidence flags.
 */
object OcrTimetableParser {

    // ------------------------------------------------------------------ data structures

    private data class Word(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val height get() = max(1, bottom - top)
    }

    private class MCell {
        var text: String = ""
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var span = 1
        var covered = false
        val hasRect get() = left != Int.MAX_VALUE

        fun include(l: Int, t: Int, r: Int, b: Int) {
            left = min(left, l); top = min(top, t); right = max(right, r); bottom = max(bottom, b)
        }
    }

    private class Matrix(val rows: Int, val cols: Int) {
        val cells: Array<Array<MCell>> = Array(rows) { Array(cols) { MCell() } }
        fun text(r: Int, c: Int) = cells[r][c].text

        fun transposed(): Matrix {
            val m = Matrix(cols, rows)
            for (r in 0 until rows) for (c in 0 until cols) {
                val src = cells[r][c]
                val dst = m.cells[c][r]
                dst.text = if (src.covered) "" else src.text
                if (src.hasRect) dst.include(src.left, src.top, src.right, src.bottom)
            }
            return m
        }
    }

    private data class Fields(
        var day: Int? = null,
        val times: MutableList<Int> = mutableListOf(),
        var type: String? = null,
        var room: String? = null,
        var instructor: String? = null,
        val courseParts: MutableList<String> = mutableListOf()
    ) {
        fun merge(o: Fields) {
            if (day == null) day = o.day
            times += o.times
            if (type == null) type = o.type
            if (room == null) room = o.room
            if (instructor == null) instructor = o.instructor
            courseParts += o.courseParts
        }
        val course get() = courseParts.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
    }

    private data class Interpretation(val drafts: List<ClassDraft>, val layout: String, val headerSlots: List<Int>)

    // ------------------------------------------------------------------ regexes

    private val TIME_REGEX = Regex("""\b(\d{1,2}[:.]\d{2})\s*([AaPp]\.?\s?[Mm]\.?)?""")
    private val OCR_TIME_TOKEN = Regex("""(?<![\w])[0-9OoIl|SB]{1,2}[:.;][0-9OoIlSB]{2}(?![\w])""")
    private val TYPE_REGEX = Regex("""\b(lecture|lec|laboratory|lab|tutorial|tut|practical|seminar)\b\.?""", RegexOption.IGNORE_CASE)
    private val INSTRUCTOR_REGEX = Regex("""\b(?:Mr|Ms|Mrs|Dr|Prof)\.?\s+[A-Za-z][A-Za-z.]*(?:\s+[A-Z][A-Za-z.]*)*""")
    private val ROOM_REGEX = Regex("""\bRoom\s*[:#]?\s*([A-Za-z0-9_\-]+)|\b([0-9OoIl]{3,4}[-_][A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    private val SKIP_REGEX = Regex("""^(lunch|lunch break|break|short break|free|recess|library|sports|-+|—+|x)$""", RegexOption.IGNORE_CASE)
    private val HEADER_WORDS = Regex("""\b(day|days|start time|end time|course|subject|class type|room|instructor|faculty|time|timing|period|slot)\b""", RegexOption.IGNORE_CASE)

    private val headerAliases: Map<String, String> = mapOf(
        "day" to "day", "days" to "day", "weekday" to "day",
        "starttime" to "start", "start" to "start", "from" to "start",
        "endtime" to "end", "end" to "end", "to" to "end",
        "time" to "time", "timing" to "time", "timings" to "time", "slot" to "time", "period" to "time",
        "course" to "course", "subject" to "course", "coursename" to "course", "subjectname" to "course",
        "classtype" to "type", "type" to "type", "sessiontype" to "type",
        "room" to "room", "roomno" to "room", "venue" to "room", "location" to "room", "classroom" to "room",
        "instructor" to "instructor", "faculty" to "instructor", "teacher" to "instructor",
        "professor" to "instructor", "lecturer" to "instructor", "facultyname" to "instructor",
        "week" to "week", "weeks" to "week"
    )

    // ------------------------------------------------------------------ public API

    suspend fun parseImages(images: List<Bitmap>, known: KnownData): OcrOutput = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val all = mutableListOf<ClassDraft>()
            val warnings = mutableListOf<String>()
            val raw = StringBuilder()
            val display = mutableListOf<Bitmap>()
            val layouts = mutableListOf<String>()
            images.forEachIndexed { index, image ->
                val single = parseOne(recognizer, image, index, known)
                display += single.display
                all += single.drafts
                raw.append(if (images.size > 1) "— Image ${index + 1} —\n" else "").append(single.raw).append('\n')
                single.layout?.let { layouts += it }
            }
            // Merge rows from several screenshots (overlapping scroll captures).
            val merged = all.groupBy { it.dedupeKey() }.values.map { group ->
                group.minByOrNull { it.confidence.ordinal * 10 + it.flags.size }!!
            }
            val refined = refine(merged, known)
            if (refined.isEmpty()) {
                warnings += "Couldn't detect any classes. Try cropping to just the table, or check the scanned text below."
            } else {
                warnings += "Scanned on-device. Rows marked 🟡 or 🔴 need a quick look."
                if (layouts.isNotEmpty()) warnings += "Detected layout: ${layouts.distinct().joinToString()}."
                if (images.size > 1) warnings += "Merged ${images.size} screenshots and removed duplicates."
            }
            OcrOutput(
                ParseResult(
                    drafts = refined.sortedWith(compareBy({ DayParser.parse(it.day) ?: 9 }, { TimeUtils.parse(it.startTime) ?: 0 })),
                    warnings = warnings,
                    source = if (images.size > 1) "${images.size} images" else "Image",
                    rawText = raw.toString().trim()
                ),
                display
            )
        } finally {
            recognizer.close()
        }
    }

    // ------------------------------------------------------------------ per image

    private data class Single(val drafts: List<ClassDraft>, val display: Bitmap, val raw: String, val layout: String?)

    private suspend fun recognize(recognizer: TextRecognizer, bitmap: Bitmap): Text =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

    private suspend fun parseOne(recognizer: TextRecognizer, original: Bitmap, imageIndex: Int, known: KnownData): Single {
        var color = ImagePreprocessor.upscaleIfSmall(original)
        var enhanced = ImagePreprocessor.enhance(color)
        var textA = recognize(recognizer, enhanced)

        // Straighten tilted photos.
        val angle = skewAngle(textA)
        if (abs(angle) in 0.7..15.0) {
            color = ImagePreprocessor.rotate(color, -angle.toFloat())
            enhanced = ImagePreprocessor.enhance(color)
            textA = recognize(recognizer, enhanced)
        }
        val textB = recognize(recognizer, color)
        val raw = textA.text

        // Screenshot of CSV text?
        if (looksLikeCsv(raw)) {
            val csv = CsvTimetableParser.parse(raw)
            if (csv.drafts.isNotEmpty()) return Single(csv.drafts, color, raw, "CSV text")
        }

        val detector = TableDetector(enhanced)
        val grid = detector.detect()
        val interpA = interpret(buildMatrix(words(textA), grid, detector), imageIndex)
        val interpB = interpret(buildMatrix(words(textB), grid, detector), imageIndex)

        val scoreA = score(interpA.drafts)
        val scoreB = score(interpB.drafts)
        val (best, other) = if (scoreB > scoreA) interpB to interpA else interpA to interpB

        // Cross-check the two reads.
        val otherByKey = other.drafts.associateBy { "${DayParser.parse(it.day)}|${TimeUtils.parse(it.startTime)}" }
        val checked = best.drafts.map { d ->
            val o = otherByKey["${DayParser.parse(d.day)}|${TimeUtils.parse(d.startTime)}"]
            if (o != null && o.course.isNotBlank() && d.course.isNotBlank() &&
                FuzzyMatcher.similarity(o.course, d.course) < 0.8
            ) {
                d.copy(flags = d.flags + "Two scans disagree: “${d.course}” vs “${o.course}”")
            } else d
        }
        val layoutName = if (grid != null) "${best.layout} (bordered table)" else best.layout
        return Single(checked, color, raw, layoutName.takeIf { checked.isNotEmpty() })
    }

    private fun score(drafts: List<ClassDraft>): Int = drafts.sumOf { d -> (if (d.isValid) 4 else 0) - d.flags.size }

    private fun skewAngle(text: Text): Double {
        val angles = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val p = line.cornerPoints ?: return@mapNotNull null
            if (p.size < 2) return@mapNotNull null
            val dx = (p[1].x - p[0].x).toDouble()
            val dy = (p[1].y - p[0].y).toDouble()
            val box = line.boundingBox ?: return@mapNotNull null
            if (box.width() < box.height() * 3) return@mapNotNull null
            Math.toDegrees(atan2(dy, dx))
        }.sorted()
        if (angles.size < 3) return 0.0
        return angles[angles.size / 2]
    }

    private fun looksLikeCsv(raw: String): Boolean {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val commaLines = lines.count { line -> line.count { it == ',' } >= 4 }
        return commaLines >= (lines.size / 2).coerceAtLeast(1)
    }

    private fun words(text: Text): List<Word> = text.textBlocks.flatMap { b -> b.lines }.flatMap { line ->
        line.elements.mapNotNull { e ->
            val r = e.boundingBox ?: return@mapNotNull null
            Word(e.text, r.left, r.top, r.right, r.bottom)
        }
    }

    // ------------------------------------------------------------------ table building

    private fun buildMatrix(words: List<Word>, grid: TableDetector.Grid?, detector: TableDetector): Matrix =
        if (grid != null) fromGrid(words, grid, detector) else fromGaps(words)

    private fun fromGrid(words: List<Word>, grid: TableDetector.Grid, detector: TableDetector): Matrix {
        val xs = grid.xs
        val ys = grid.ys
        val m = Matrix(ys.size - 1, xs.size - 1)
        val buckets = Array(m.rows) { Array(m.cols) { mutableListOf<Word>() } }
        for (w in words) {
            val r = (0 until m.rows).firstOrNull { w.cy in ys[it]..ys[it + 1] } ?: continue
            val c = (0 until m.cols).firstOrNull { w.cx in xs[it]..xs[it + 1] } ?: continue
            buckets[r][c] += w
        }
        for (r in 0 until m.rows) for (c in 0 until m.cols) {
            val cell = m.cells[r][c]
            cell.include(xs[c], ys[r], xs[c + 1], ys[r + 1])
            cell.text = joinWords(buckets[r][c])
        }
        // Merged cells: no vertical ruling line inside this row between two columns.
        for (r in 0 until m.rows) {
            var c = 0
            while (c < m.cols) {
                val anchor = m.cells[r][c]
                var next = c + 1
                while (next < m.cols && !detector.hasVerticalLine(xs[next], ys[r] + 3, ys[r + 1] - 3)) {
                    val covered = m.cells[r][next]
                    anchor.text = (anchor.text + " " + covered.text).trim()
                    anchor.include(covered.left, covered.top, covered.right, covered.bottom)
                    anchor.span++
                    covered.covered = true
                    covered.text = ""
                    next++
                }
                c = next
            }
        }
        return m
    }

    /** Borderless tables: rows by vertical clustering, columns by gaps no word ever crosses. */
    private fun fromGaps(words: List<Word>): Matrix {
        if (words.isEmpty()) return Matrix(0, 0)
        val heights = words.map { it.height }.sorted()
        val medianH = heights[heights.size / 2]
        // Rows
        val sorted = words.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<Word>>()
        var current = mutableListOf<Word>()
        var avg = 0.0
        for (w in sorted) {
            if (current.isEmpty() || abs(w.cy - avg) <= medianH * 0.6) {
                current += w; avg = current.map { it.cy }.average()
            } else {
                rows += current; current = mutableListOf(w); avg = w.cy.toDouble()
            }
        }
        if (current.isNotEmpty()) rows += current
        // Column separators: x positions never covered by any word, wide enough.
        val minX = words.minOf { it.left }
        val maxX = words.maxOf { it.right }
        val coverage = BooleanArray(maxX - minX + 1)
        for (w in words) for (x in w.left..w.right) coverage[x - minX] = true
        val gapMin = max(12, (medianH * 1.2).toInt())
        val separators = mutableListOf<Int>()
        var gapStart = -1
        for (i in coverage.indices) {
            if (!coverage[i] && gapStart < 0) gapStart = i
            if (coverage[i] && gapStart >= 0) {
                if (i - gapStart >= gapMin) separators += minX + (gapStart + i) / 2
                gapStart = -1
            }
        }
        val bounds = listOf(minX - 1) + separators + listOf(maxX + 1)
        val m = Matrix(rows.size, bounds.size - 1)
        rows.forEachIndexed { r, rowWords ->
            for (c in 0 until m.cols) {
                val inCol = rowWords.filter { it.cx in bounds[c]..bounds[c + 1] }
                val cell = m.cells[r][c]
                cell.text = joinWords(inCol)
                inCol.forEach { cell.include(it.left, it.top, it.right, it.bottom) }
            }
        }
        return m
    }

    /** Reading order inside a cell; joins wrapped lines ("Software Engineering / and System Design"). */
    private fun joinWords(ws: List<Word>): String {
        if (ws.isEmpty()) return ""
        val medianH = ws.map { it.height }.sorted()[ws.size / 2]
        val lines = mutableListOf<MutableList<Word>>()
        for (w in ws.sortedBy { it.cy }) {
            val line = lines.lastOrNull()
            if (line != null && abs(line.map { it.cy }.average() - w.cy) <= medianH * 0.6) line += w else lines += mutableListOf(w)
        }
        val sb = StringBuilder()
        for (line in lines) {
            val text = line.sortedBy { it.left }.joinToString(" ") { it.text }
            if (sb.isEmpty()) sb.append(text)
            else if (sb.endsWith("-")) { sb.setLength(sb.length - 1); sb.append(text) }
            else sb.append(' ').append(text)
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ interpretation

    private fun interpret(m: Matrix, imageIndex: Int): Interpretation {
        if (m.rows == 0 || m.cols == 0) return Interpretation(emptyList(), "none", emptyList())
        headerMapped(m, imageIndex)?.let { if (it.drafts.any { d -> d.isValid }) return it }
        val candidates = listOfNotNull(
            gridLayout(m, imageIndex, "day × time grid"),
            gridLayout(m.transposed(), imageIndex, "time × day grid"),
            rowHeuristic(m, imageIndex)
        )
        return candidates.maxByOrNull { score(it.drafts) } ?: Interpretation(emptyList(), "none", emptyList())
    }

    private fun region(imageIndex: Int, cells: List<MCell>): SourceRegion? {
        val withRect = cells.filter { it.hasRect }
        if (withRect.isEmpty()) return null
        return SourceRegion(
            imageIndex,
            withRect.minOf { it.left }, withRect.minOf { it.top },
            withRect.maxOf { it.right }, withRect.maxOf { it.bottom }
        )
    }

    /** Table whose header row names the columns (Day | Start | End | Course | …). */
    private fun headerMapped(m: Matrix, imageIndex: Int): Interpretation? {
        for (h in 0 until min(4, m.rows)) {
            val map = mutableMapOf<String, Int>()
            for (c in 0 until m.cols) {
                val key = m.text(h, c).lowercase().filter { it.isLetter() }
                headerAliases[key]?.let { if (it !in map) map[it] = c }
            }
            if (map.size < 3 || "course" !in map) continue

            var lastDay: Int? = null
            val drafts = mutableListOf<ClassDraft>()
            for (r in h + 1 until m.rows) {
                fun col(name: String): String {
                    val c = map[name] ?: return ""
                    // A merged cell to the left may cover this column.
                    var k = c
                    while (k > 0 && m.cells[r][k].covered) k--
                    return m.text(r, k)
                }
                val rowCells = (0 until m.cols).map { m.cells[r][it] }
                if (rowCells.all { it.text.isBlank() }) continue
                val dayText = col("day")
                val day = DayParser.parse(dayText) ?: DayParser.find(dayText) ?: lastDay
                if (dayText.isNotBlank()) lastDay = day
                var startText = col("start")
                var endText = col("end")
                if (startText.isBlank() && endText.isBlank()) {
                    val times = extractTimes(col("time"))
                    startText = times.getOrNull(0)?.let { TimeUtils.format(it) }.orEmpty()
                    endText = times.getOrNull(1)?.let { TimeUtils.format(it) }.orEmpty()
                }
                val course = cleanLeftover(col("course"))
                if (course.isBlank() && startText.isBlank()) continue
                val start = TimeUtils.parse(startText)
                val end = TimeUtils.parse(endText)
                drafts += ClassDraft(
                    day = day?.let { DayParser.name(it) } ?: dayText,
                    startTime = start?.let { TimeUtils.format(it) } ?: startText,
                    endTime = end?.let { TimeUtils.format(it) } ?: endText,
                    course = course,
                    rawCourse = course,
                    classType = normalizeType(col("type")) ?: "Lecture",
                    room = fixRoom(col("room")),
                    instructor = col("instructor"),
                    weekParity = ClassDraft.parityFrom(col("week")),
                    region = region(imageIndex, rowCells)
                )
            }
            return Interpretation(drafts, "row table with headers", emptyList())
        }
        return null
    }

    private data class Slot(val start: Int, val end: Int)

    /** Days down the first column, time slots across the header row. */
    private fun gridLayout(m: Matrix, imageIndex: Int, name: String): Interpretation? {
        if (m.cols < 2) return null
        val headerRow = (0 until min(5, m.rows)).firstOrNull { r ->
            (1 until m.cols).count { c -> extractTimes(m.text(r, c)).isNotEmpty() } >= 2 &&
                (0 until m.cols).none { c -> DayParser.find(m.text(r, c)) != null }
        } ?: return null

        val rawSlots = (0 until m.cols).map { c ->
            val t = extractTimes(m.text(headerRow, c))
            if (t.isEmpty()) null else t
        }
        val slots = rawSlots.mapIndexed { c, t ->
            if (t == null) null else {
                val start = t[0]
                val end = t.getOrNull(1)
                    ?: (c + 1 until m.cols).firstNotNullOfOrNull { rawSlots[it]?.get(0) }
                    ?: (start + 60)
                val (s, e) = TimeUtils.fixRange(start, end)
                Slot(s, e)
            }
        }
        if (slots.count { it != null } < 2) return null

        // Group rows by day; rows without a day continue the previous day (wrapped text).
        val groups = mutableListOf<Pair<Int, MutableList<Int>>>()
        for (r in headerRow + 1 until m.rows) {
            val firstText = m.text(r, 0)
            val day = DayParser.parse(firstText) ?: DayParser.find(firstText)
            if (day != null) groups += day to mutableListOf(r)
            else groups.lastOrNull()?.second?.add(r)
        }
        if (groups.isEmpty()) return null

        val drafts = mutableListOf<ClassDraft>()
        for ((day, rowIdx) in groups) {
            for (c in 1 until m.cols) {
                val anchorCells = rowIdx.map { m.cells[it][c] }.filter { !it.covered }
                val text = anchorCells.joinToString(" ") { it.text }.trim()
                if (text.isBlank() || SKIP_REGEX.matches(text.trim())) continue
                val span = anchorCells.maxOfOrNull { it.span } ?: 1
                val startSlot = slots[c] ?: continue
                val endSlot = slots.getOrNull(c + span - 1) ?: startSlot
                val f = extract(text)
                if (f.course.isBlank()) continue
                drafts += ClassDraft(
                    day = DayParser.name(day),
                    startTime = TimeUtils.format(startSlot.start),
                    endTime = TimeUtils.format(max(endSlot.end, startSlot.end)),
                    course = f.course,
                    rawCourse = f.course,
                    classType = f.type ?: if (span > 1) "Lab" else "Lecture",
                    room = f.room.orEmpty(),
                    instructor = f.instructor.orEmpty(),
                    region = region(imageIndex, anchorCells)
                )
            }
        }
        return Interpretation(drafts, name, slots.filterNotNull().flatMap { listOf(it.start, it.end) })
    }

    /** One class per row, columns unknown: pull recognisable pieces out of every cell. */
    private fun rowHeuristic(m: Matrix, imageIndex: Int): Interpretation {
        var lastDay: Int? = null
        val drafts = mutableListOf<ClassDraft>()
        for (r in 0 until m.rows) {
            val cells = (0 until m.cols).map { m.cells[r][it] }.filter { !it.covered && it.text.isNotBlank() }
            if (cells.isEmpty()) continue
            val f = Fields()
            cells.forEach { f.merge(extract(it.text)) }
            if (f.day != null) lastDay = f.day
            if (f.times.isEmpty() || f.course.isBlank()) continue
            val start = f.times[0]
            val (s, e) = TimeUtils.fixRange(start, f.times.getOrNull(1) ?: (start + 60))
            drafts += ClassDraft(
                day = (f.day ?: lastDay)?.let { DayParser.name(it) }.orEmpty(),
                startTime = TimeUtils.format(s),
                endTime = TimeUtils.format(e),
                course = f.course,
                rawCourse = f.course,
                classType = f.type ?: "Lecture",
                room = f.room.orEmpty(),
                instructor = f.instructor.orEmpty(),
                flags = if (f.times.size < 2) listOf("End time guessed (+1 hour)") else emptyList(),
                region = region(imageIndex, cells)
            )
        }
        return Interpretation(drafts, "one class per row", emptyList())
    }

    // ------------------------------------------------------------------ field extraction

    private fun fixOcrTimes(text: String): String =
        OCR_TIME_TOKEN.replace(text) { mr -> if (mr.value.any { it.isDigit() }) TimeUtils.fixOcrDigits(mr.value) else mr.value }

    private fun extractTimes(text: String): List<Int> =
        TIME_REGEX.findAll(fixOcrTimes(text)).mapNotNull { TimeUtils.parse(it.value.replace(" ", "")) }.toList()

    private fun fixRoom(raw: String): String {
        val t = raw.trim().removePrefix("Room").removePrefix("room").trim(' ', ':', '#')
        val m = Regex("""^([0-9OoIl]{3,4})([-_].*)?$""").find(t) ?: return t
        val digits = m.groupValues[1].map {
            when (it) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; else -> it }
        }.joinToString("")
        return digits + m.groupValues[2]
    }

    private fun normalizeType(raw: String): String? {
        val m = TYPE_REGEX.find(raw) ?: return null
        return when (m.groupValues[1].lowercase()) {
            "lab", "laboratory", "practical" -> "Lab"
            "tutorial", "tut" -> "Tutorial"
            "seminar" -> "Seminar"
            else -> "Lecture"
        }
    }

    private fun cleanLeftover(t: String): String = t
        .replace(Regex("""[|•;()\[\]]"""), " ")
        .replace(Regex("""(?<=\s|^)(-|–|—|to)(?=\s|$)""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '.', ',')

    /** Pulls day, times, type, instructor and room out of a fragment; the rest is the course name. */
    private fun extract(input: String): Fields {
        val f = Fields()
        var t = fixOcrTimes(input)

        DayParser.FIND_REGEX.find(t)?.let { m ->
            f.day = DayParser.find(m.value)
            t = t.removeRange(m.range)
        }
        TIME_REGEX.findAll(t).toList().forEach { m ->
            TimeUtils.parse(m.value.replace(" ", ""))?.let { f.times += it }
        }
        t = TIME_REGEX.replace(t, " ")
        INSTRUCTOR_REGEX.find(t)?.let { m ->
            f.instructor = m.value.trim()
            t = t.removeRange(m.range)
        }
        TYPE_REGEX.find(t)?.let { m ->
            f.type = normalizeType(m.value)
            t = t.removeRange(m.range)
        }
        ROOM_REGEX.find(t)?.let { m ->
            f.room = fixRoom(m.groupValues[1].ifEmpty { m.groupValues[2] })
            t = t.removeRange(m.range)
        }
        val leftover = cleanLeftover(t)
        if (leftover.isNotBlank() && !SKIP_REGEX.matches(leftover) && HEADER_WORDS.replace(leftover, "").isNotBlank()) {
            f.courseParts += leftover
        }
        return f
    }

    // ------------------------------------------------------------------ clean-up / auto-correction

    private val PREFIX = Regex("""^(mr|ms|mrs|dr|prof)\.?\s*""", RegexOption.IGNORE_CASE)

    private fun normalizeInstructor(s: String): String {
        val m = PREFIX.find(s) ?: return s.trim()
        val p = m.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }
        return "$p. " + s.substring(m.range.last + 1).trim()
    }

    /** Applies learned abbreviations, fuzzy corrections, spelling clusters and slot snapping. */
    fun refine(drafts: List<ClassDraft>, known: KnownData): List<ClassDraft> {
        if (drafts.isEmpty()) return drafts
        val knownCourses = known.courses
        val abbr = known.abbreviations

        // 1) Courses: abbreviations → known subjects → clusters inside this import.
        val step1 = drafts.map { d ->
            val raw = d.course.trim()
            val key = raw.lowercase()
            val flags = d.flags.toMutableList()
            var course = raw
            val learned = abbr[key] ?: abbr.entries.firstOrNull { FuzzyMatcher.normalize(it.key) == FuzzyMatcher.normalize(raw) }?.value
            if (learned != null) {
                course = learned
            } else if (knownCourses.isNotEmpty() && knownCourses.none { it.equals(raw, ignoreCase = true) }) {
                val best = FuzzyMatcher.bestCourse(raw, knownCourses)
                if (best != null && best.score >= 0.78) {
                    course = best.value
                    if (best.score < 0.97) flags += "Auto-corrected from “$raw”"
                } else if (raw.isNotBlank()) {
                    flags += "New subject – check the spelling"
                }
            }
            d.copy(course = course, rawCourse = d.rawCourse.ifBlank { raw }, flags = flags)
        }
        val canonical = FuzzyMatcher.clusterCanonical(step1.map { it.course })
        val step2 = step1.map { d ->
            val c = canonical[d.course] ?: d.course
            if (c != d.course) d.copy(course = c, flags = d.flags + "Spelling unified from “${d.course}”") else d
        }

        // 2) Instructors and rooms.
        val instructorCanon = FuzzyMatcher.clusterCanonical(step2.map { normalizeInstructor(it.instructor) }, 0.85)
        val step3 = step2.map { d ->
            var instructor = normalizeInstructor(d.instructor)
            instructor = instructorCanon[instructor] ?: instructor
            if (instructor.isNotBlank() && known.instructors.isNotEmpty()) {
                FuzzyMatcher.best(instructor, known.instructors)?.takeIf { it.score >= 0.85 }?.let { instructor = it.value }
            }
            var room = fixRoom(d.room)
            if (room.isNotBlank() && known.rooms.isNotEmpty()) {
                FuzzyMatcher.best(room, known.rooms)?.takeIf { it.score >= 0.85 }?.let { room = it.value }
            }
            d.copy(instructor = instructor, room = room)
        }

        // 3) Snap times to the college's real slot times.
        val parsedStarts = step3.mapNotNull { TimeUtils.parse(it.startTime) }
        val parsedEnds = step3.mapNotNull { TimeUtils.parse(it.endTime) }
        val frequent = (parsedStarts + parsedEnds).groupingBy { it }.eachCount().filter { it.value >= 2 }.keys
        val slots = known.slotTimes + frequent
        return step3.map { d ->
            val s = TimeUtils.parse(d.startTime)
            val e = TimeUtils.parse(d.endTime)
            if (s == null || e == null || slots.isEmpty()) return@map d
            val s2 = TimeUtils.snap(s, slots)
            val e2 = TimeUtils.snap(e, slots)
            val flags = d.flags.toMutableList()
            if (s2 != s || e2 != e) flags += "Time adjusted to slot (was ${TimeUtils.format(s)}–${TimeUtils.format(e)})"
            d.copy(startTime = TimeUtils.format(s2), endTime = TimeUtils.format(e2), flags = flags)
        }
    }
}
