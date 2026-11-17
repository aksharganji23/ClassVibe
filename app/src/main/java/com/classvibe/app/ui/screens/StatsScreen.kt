package com.classvibe.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.AttendanceRecord
import com.classvibe.app.data.AttendanceStats
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.CourseAttendance
import com.classvibe.app.parser.DayParser
import com.classvibe.app.ui.components.EmptyState
import com.classvibe.app.ui.components.StatTile
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.ui.theme.courseColor
import java.time.LocalDate

@Composable
fun StatsScreen(
    classes: List<ClassSchedule>,
    attendance: List<AttendanceRecord>,
    holidays: List<com.classvibe.app.data.Holiday>,
    prefs: com.classvibe.app.data.AppPrefs,
    today: LocalDate,
    openBunkPlanner: Boolean = false,
    onSetBaseline: (course: String, attended: Int, held: Int) -> Unit = { _, _, _ -> },
    onFixMark: (AttendanceRecord, com.classvibe.app.data.AttendanceStatus?) -> Unit = { _, _ -> }
) {
    var editing by remember { mutableStateOf<String?>(null) }
    if (classes.isEmpty() && attendance.isEmpty()) {
        EmptyState("📊", "No stats yet", "Import your timetable and mark attendance from the Today screen or from notifications.")
        return
    }
    val baseline = prefs.attendanceBaseline
    val overall = AttendanceStats.overall(attendance, baseline)
    // Every timetable subject gets a card (so you can enter your existing attendance before marking).
    val byCourse = run {
        val known = AttendanceStats.byCourse(attendance, baseline)
        val missing = classes.map { it.course }.distinct().filter { c -> known.none { it.course == c } }
            .map { CourseAttendance(it, 0, 0, 0) }
        known.filter { it.total > 0 } + (known.filter { it.total == 0 } + missing).sortedBy { it.course }
    }
    val streak = AttendanceStats.streakDays(attendance, today)
    val weeklyMinutes = classes.sumOf { it.endMinutes - it.startMinutes }
    val hoursByCourse = classes.groupBy { it.course }
        .mapValues { (_, list) -> list.sumOf { it.endMinutes - it.startMinutes } }
        .entries.sortedByDescending { it.value }
    val busiest = classes.groupBy { it.dayOfWeek }.maxByOrNull { (_, list) -> list.sumOf { it.endMinutes - it.startMinutes } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    if (overall.total == 0) "—" else "${overall.percent}%", "Overall attendance", Modifier.weight(1f),
                    color = if (overall.isLow) Cv.colors.danger else Cv.colors.success
                )
                StatTile("🔥 $streak", "Day streak", Modifier.weight(1f), color = Cv.colors.accent)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${classes.size}", "Classes / week", Modifier.weight(1f))
                StatTile("%.1f h".format(weeklyMinutes / 60.0), "Hours / week", Modifier.weight(1f))
                StatTile(busiest?.key?.let { DayParser.short(it) } ?: "—", "Busiest day", Modifier.weight(1f))
            }
        }

        item { BunkPlannerSection(classes, attendance, holidays, prefs, today, startExpanded = openBunkPlanner) }

        item { Text("Attendance by subject", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Already had classes before ClassVibe? Tap ✏️ on a subject to enter your current attendance. " +
                    "Mark new classes with ✅ / ❌ on Today or on the class-ending notification.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(byCourse, key = { it.course }) { c -> AttendanceCard(c, baseline[c.course]) { editing = c.course } }

        item { Spacer(Modifier.height(4.dp)); Text("Weekly hours per subject", style = MaterialTheme.typography.titleMedium) }
        val maxMinutes = hoursByCourse.maxOfOrNull { it.value } ?: 1
        items(hoursByCourse, key = { "h" + it.key }) { (course, minutes) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(course, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("%.1f h".format(minutes / 60.0), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(4.dp))
                val color = courseColor(course)
                LinearProgressIndicator(
                    progress = { minutes.toFloat() / maxMinutes },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = color, trackColor = color.copy(alpha = 0.12f)
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    editing?.let { course ->
        EditAttendanceDialog(
            course = course,
            base = baseline[course],
            marks = attendance.filter { it.course == course },
            onSave = { a, h -> onSetBaseline(course, a, h) },
            onFixMark = onFixMark,
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun EditAttendanceDialog(
    course: String,
    base: com.classvibe.app.data.AttendanceBaseline?,
    marks: List<AttendanceRecord>,
    onSave: (attended: Int, held: Int) -> Unit,
    onFixMark: (AttendanceRecord, com.classvibe.app.data.AttendanceStatus?) -> Unit,
    onDismiss: () -> Unit
) {
    var attended by remember { mutableStateOf(base?.attended?.toString() ?: "") }
    var held by remember { mutableStateOf(base?.held?.toString() ?: "") }
    val a = attended.toIntOrNull()
    val h = held.toIntOrNull()
    val error = when {
        attended.isBlank() && held.isBlank() -> null
        a == null || h == null -> "Enter both numbers"
        a > h -> "Attended can't be more than classes held"
        else -> null
    }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✏️ $course", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Attendance before ClassVibe", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "From your college portal or register. Classes you mark in ClassVibe are added on top.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            attended, { attended = it.filter(Char::isDigit).take(4) },
                            label = { Text("Attended") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            held, { held = it.filter(Char::isDigit).take(4) },
                            label = { Text("Classes held") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                    }
                    if (error != null) Text(error, color = Cv.colors.danger, style = MaterialTheme.typography.bodySmall)
                    else if (a != null && h != null && h > 0) Text(
                        "= ${(a * 100 / h)}% before ClassVibe", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (marks.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Marked in ClassVibe (tap to fix)", style = MaterialTheme.typography.titleSmall)
                    }
                    items(marks.sortedByDescending { it.epochDay }, key = { it.id }) { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(LocalDate.ofEpochDay(r.epochDay).format(fmt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            listOf(
                                com.classvibe.app.data.AttendanceStatus.ATTENDED,
                                com.classvibe.app.data.AttendanceStatus.MISSED,
                                com.classvibe.app.data.AttendanceStatus.CANCELLED
                            ).forEach { st ->
                                androidx.compose.material3.FilterChip(
                                    selected = r.attendanceStatus == st,
                                    onClick = { onFixMark(r, st) },
                                    label = { Text(st.emoji) },
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            androidx.compose.material3.TextButton(onClick = { onFixMark(r, null) }) { Text("🗑️") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = error == null,
                onClick = { onSave(a ?: 0, h ?: 0); onDismiss() }
            ) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AttendanceCard(c: CourseAttendance, base: com.classvibe.app.data.AttendanceBaseline?, onEdit: () -> Unit) {
    val cv = Cv.colors
    val color = if (c.isLow) cv.danger else if (c.percent < 85) cv.warning else cv.success
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.course, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(if (c.total == 0) "—" else "${c.percent}%", style = MaterialTheme.typography.headlineSmall, color = if (c.total == 0) MaterialTheme.colorScheme.onSurfaceVariant else color)
                androidx.compose.material3.IconButton(onClick = onEdit) {
                    androidx.compose.material3.Icon(Icons.Default.Edit, contentDescription = "Edit attendance for ${c.course}")
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (c.total == 0) 0f else c.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = color, trackColor = color.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(6.dp))
            if (c.total == 0) {
                Text(
                    "No classes yet – tap ✏️ to enter attendance you already have.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
            Text(
                "✅ ${c.attended} attended · ❌ ${c.missed} missed" + (if (c.cancelled > 0) " · 🚫 ${c.cancelled} cancelled" else "") +
                    (base?.let { " (incl. ${it.attended}/${it.held} from before)" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (c.isLow) "⚠️ Below 75%! Attend the next ${c.needToAttend} class(es) in a row to get back to 75%."
                else "👍 You can miss ${c.canMiss} more class(es) and stay at 75%+.",
                style = MaterialTheme.typography.bodySmall, color = if (c.isLow) cv.danger else MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }
    }
}
