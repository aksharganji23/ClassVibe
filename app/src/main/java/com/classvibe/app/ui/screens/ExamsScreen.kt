package com.classvibe.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.Exam
import com.classvibe.app.data.ExamType
import com.classvibe.app.notifications.ExamPlanner
import com.classvibe.app.notifications.ExamReminderKind
import com.classvibe.app.parser.TimeUtils
import com.classvibe.app.ui.components.DATE_LABEL
import com.classvibe.app.ui.components.DateField
import com.classvibe.app.ui.components.DropdownTextField
import com.classvibe.app.ui.components.EmptyState
import com.classvibe.app.ui.components.Pill
import com.classvibe.app.ui.components.SelectField
import com.classvibe.app.ui.components.TimeField
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.ui.theme.courseColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val OTHER_SUBJECT = "➕ Other subject…"

/** What the add/edit exam form needs from the timetable. */
data class ExamFormData(
    val subjects: List<String>,
    val rooms: List<String>,
    val slots: List<Int>,
    val instructorFor: Map<String, String>
) {
    companion object {
        fun from(classes: List<ClassSchedule>, customSubjects: Set<String>): ExamFormData = ExamFormData(
            subjects = (classes.map { it.course } + customSubjects).map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.sorted(),
            rooms = classes.map { it.room }.filter { it.isNotBlank() }.distinct().sorted(),
            slots = classes.map { it.startMinutes }.distinct().sorted().ifEmpty { listOf(9 * 60 + 30, 10 * 60, 13 * 60 + 30) },
            instructorFor = classes.filter { it.instructor.isNotBlank() }.associate { it.course to it.instructor }
        )
    }
}

@Composable
fun ExamsScreen(
    exams: List<Exam>,
    classes: List<ClassSchedule>,
    prefs: AppPrefs,
    today: LocalDate,
    prefillCourse: String?,
    onPrefillConsumed: () -> Unit,
    onSave: (Exam, Boolean) -> Unit,
    onDelete: (Exam) -> Unit,
    onImportCsv: (String) -> Unit
) {
    val context = LocalContext.current
    val form = remember(classes, prefs.customSubjects) { ExamFormData.from(classes, prefs.customSubjects) }
    var editing by remember { mutableStateOf<Exam?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addCourse by remember { mutableStateOf<String?>(null) }
    var weekMode by remember { mutableStateOf(false) }
    var showPast by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(prefillCourse) {
        if (prefillCourse != null) {
            addCourse = prefillCourse
            adding = true
            onPrefillConsumed()
        }
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        onImportCsv(text)
    }

    val upcoming = exams.filter { !it.date.isBefore(today) }.sortedWith(compareBy({ it.epochDay }, { it.timeMinutes }))
    val past = exams.filter { it.date.isBefore(today) }.sortedByDescending { it.epochDay }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("📝 Exam planner", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (upcoming.isEmpty()) "Add your sem, mid, lab exams, slip tests and deadlines – ClassVibe plans the reminders."
                        else "${upcoming.size} upcoming • next: ${upcoming.first().course} " +
                            ExamPlanner.countdownText(ChronoUnit.DAYS.between(today, upcoming.first().date), ExamReminderKind.MILESTONE),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { addCourse = null; adding = true }) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add exam")
                        }
                        OutlinedButton(onClick = { weekMode = true }) { Text("Exam week") }
                    }
                    TextButton(onClick = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) }) {
                        Text("Import exams from CSV")
                    }
                }
            }
        }

        if (exams.isEmpty()) item {
            EmptyState("🎓", "No exams yet", "Tip: tap ⋮ on any class card → \"Add exam for this subject\" to pre-fill everything.")
        }

        items(upcoming, key = { it.id }) { e -> ExamCard(e, today, Modifier.animateItem()) { editing = e } }

        if (past.isNotEmpty()) {
            item {
                TextButton(onClick = { showPast = !showPast }) { Text(if (showPast) "Hide past exams" else "Show ${past.size} past exam(s)") }
            }
            if (showPast) items(past, key = { "p${it.id}" }) { e -> ExamCard(e, today, Modifier.animateItem()) { editing = e } }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    if (adding || editing != null) {
        ExamDialog(
            initial = editing,
            prefillCourse = addCourse,
            form = form,
            existing = exams,
            today = today,
            onDismiss = { adding = false; editing = null },
            onSave = { exam, another ->
                onSave(exam, false)
                if (!another) { adding = false; editing = null }
            },
            onDelete = editing?.let { e -> { onDelete(e); editing = null } }
        )
    }
    if (weekMode) {
        ExamWeekDialog(form, today, onDismiss = { weekMode = false }, onSaveAll = { list -> list.forEach { onSave(it, true) }; weekMode = false })
    }
}

@Composable
private fun ExamCard(exam: Exam, today: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val days = ChronoUnit.DAYS.between(today, exam.date)
    val color = when {
        days < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        days <= 3 -> Cv.colors.danger
        days <= 14 -> Cv.colors.accent
        else -> Cv.colors.brandText
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
                Text(
                    when { days < 0 -> "✓"; days == 0L -> "🍀"; else -> days.toString() },
                    style = MaterialTheme.typography.headlineSmall, color = color
                )
                Text(when { days < 0 -> "done"; days == 0L -> "today"; days == 1L -> "day"; else -> "days" }, style = MaterialTheme.typography.labelSmall, color = color)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Pill("${exam.examType.emoji} ${exam.typeLabel}", courseColor(exam.course))
                Spacer(Modifier.height(4.dp))
                Text(exam.course, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    exam.date.format(DATE_LABEL) + " · " + TimeUtils.formatFriendly(exam.timeMinutes) +
                        if (exam.room.isNotBlank()) " · ${exam.room}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (exam.notes.isNotBlank()) Text("📝 ${exam.notes}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ExamDialog(
    initial: Exam?,
    prefillCourse: String?,
    form: ExamFormData,
    existing: List<Exam>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Exam, Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    var subject by remember { mutableStateOf(initial?.course ?: prefillCourse ?: "") }
    var otherSubject by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initial?.examType ?: ExamType.MID) }
    var customType by remember { mutableStateOf(initial?.customType ?: "") }
    var date by remember { mutableStateOf(initial?.date) }
    var time by remember { mutableStateOf<Int?>(initial?.timeMinutes ?: 10 * 60) }
    var room by remember { mutableStateOf(initial?.room ?: "") }
    var instructor by remember { mutableStateOf(initial?.instructor ?: (prefillCourse?.let { form.instructorFor[it] } ?: "")) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }

    val duplicate = date != null && existing.any {
        it.id != initial?.id && it.course.equals(subject.trim(), ignoreCase = true) && it.examType == type && it.date == date
    }
    val valid = subject.isNotBlank() && date != null && time != null && (type != ExamType.OTHER || customType.isNotBlank())

    fun build(): Exam = Exam(
        id = initial?.id ?: 0,
        type = type.name,
        customType = if (type == ExamType.OTHER) customType.trim() else "",
        course = subject.trim(),
        epochDay = date!!.toEpochDay(),
        timeMinutes = time ?: (10 * 60),
        room = room.trim(),
        instructor = instructor.trim(),
        notes = notes.trim(),
        createdEpochDay = initial?.createdEpochDay ?: today.toEpochDay()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp),
        title = { Text(if (initial == null) "Add exam" else "Edit exam") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (otherSubject || (subject.isNotBlank() && form.subjects.none { it.equals(subject, true) } && initial != null)) {
                    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (form.subjects.isNotEmpty()) TextButton(onClick = { otherSubject = false; subject = "" }) { Text("Pick from timetable instead") }
                } else {
                    SelectField(
                        label = "📘 Subject",
                        value = subject,
                        options = form.subjects + OTHER_SUBJECT,
                        onSelect = { picked ->
                            if (picked == OTHER_SUBJECT) { otherSubject = true; subject = "" }
                            else {
                                subject = picked
                                form.instructorFor[picked]?.let { if (instructor.isBlank()) instructor = it }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SelectField(
                    label = "🏷️ Exam type",
                    value = "${type.emoji} ${type.label}",
                    options = ExamType.entries.map { "${it.emoji} ${it.label}" },
                    onSelect = { picked -> ExamType.entries.firstOrNull { "${it.emoji} ${it.label}" == picked }?.let { type = it } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (type == ExamType.OTHER) {
                    OutlinedTextField(customType, { customType = it }, label = { Text("Custom type (e.g. Internal practical)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text(scheduleHint(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DateField("📅 Date", date, onPick = { date = it }, minDate = if (initial == null) today else null, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("🕘 Time", time, form.slots, onChange = { time = it }, modifier = Modifier.weight(1f))
                    DropdownTextField("📍 Room", room, form.rooms + "TBA", { room = it }, modifier = Modifier.weight(1f))
                }
                DropdownTextField("👤 Instructor (optional)", instructor, form.instructorFor.values.distinct().sorted(), { instructor = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("📝 Syllabus / notes (optional)") }, modifier = Modifier.fillMaxWidth())
                if (duplicate) Text("⚠️ You already have this exam on that date.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete exam", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (initial == null) {
                    TextButton(enabled = valid, onClick = {
                        onSave(build(), true)
                        // Keep type + date, clear the subject for the next one.
                        subject = ""; room = ""; instructor = ""; notes = ""; otherSubject = false
                    }) { Text("Save & add another") }
                }
                TextButton(enabled = valid, onClick = { onSave(build(), false) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun scheduleHint(type: ExamType): String = when (type) {
    ExamType.SEMESTER -> "Reminders: every week + 90, 60, 45, 30, 20, 15, 10, 7… days before, daily in the final week, and exam morning."
    ExamType.MID -> "Reminders: every week + 30, 20, 15, 10, 7, 5, 3, 2, 1 days before, daily in the final 3 days, and exam morning."
    ExamType.LAB -> "Reminders: 7, 3 and 1 days before, the evening before, and exam morning."
    ExamType.SLIP_TEST -> "Reminders: morning and evening of the day before."
    ExamType.QUIZ, ExamType.VIVA -> "Reminders: the evening before and the morning of."
    ExamType.ASSIGNMENT -> "Reminders: 3 days, 1 day and 3 hours before the deadline."
    ExamType.OTHER -> "Reminders: 7, 3 and 1 days before, the evening before, and the morning of."
}

private data class WeekRow(val subject: String = "", val date: LocalDate? = null, val time: Int? = 10 * 60)

/** Add a whole mid/sem exam week at once: pick the type once, then one row per subject. */
@Composable
private fun ExamWeekDialog(form: ExamFormData, today: LocalDate, onDismiss: () -> Unit, onSaveAll: (List<Exam>) -> Unit) {
    var type by remember { mutableStateOf(ExamType.MID) }
    val rows = remember { mutableStateListOf(WeekRow(), WeekRow()) }
    val validRows = rows.filter { it.subject.isNotBlank() && it.date != null && it.time != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp),
        title = { Text("📚 Exam week") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    label = "Exam type (for all)",
                    value = "${type.emoji} ${type.label}",
                    options = ExamType.entries.filter { it != ExamType.OTHER }.map { "${it.emoji} ${it.label}" },
                    onSelect = { picked -> ExamType.entries.firstOrNull { "${it.emoji} ${it.label}" == picked }?.let { type = it } },
                    modifier = Modifier.fillMaxWidth()
                )
                rows.forEachIndexed { i, row ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Exam ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (rows.size > 1) IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove row") }
                    }
                    DropdownTextField("Subject", row.subject, form.subjects, { rows[i] = row.copy(subject = it) }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField("Date", row.date, onPick = { d -> rows[i] = rows[i].copy(date = d) }, minDate = today, modifier = Modifier.weight(1.3f))
                        TimeField("Time", row.time, form.slots, onChange = { t -> rows[i] = rows[i].copy(time = t) }, modifier = Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { rows.add(WeekRow(date = rows.lastOrNull()?.date?.plusDays(1), time = rows.lastOrNull()?.time)) }) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add subject")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = validRows.isNotEmpty(), onClick = {
                onSaveAll(validRows.map { r ->
                    Exam(
                        type = type.name, customType = "", course = r.subject.trim(), epochDay = r.date!!.toEpochDay(),
                        timeMinutes = r.time ?: 600, room = "", instructor = form.instructorFor[r.subject].orEmpty(),
                        notes = "", createdEpochDay = today.toEpochDay()
                    )
                })
            }) { Text("Save ${validRows.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
