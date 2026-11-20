package com.classvibe.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AttendanceRecord
import com.classvibe.app.data.AttendanceStatus
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.ClassStatus
import com.classvibe.app.data.Exam
import com.classvibe.app.data.Holiday
import com.classvibe.app.notifications.ExamPlanner
import com.classvibe.app.notifications.ExamReminderKind
import com.classvibe.app.notifications.WeekendPlanner
import com.classvibe.app.parser.TimeUtils
import com.classvibe.app.ui.components.ClassCard
import com.classvibe.app.ui.components.EmptyState
import com.classvibe.app.ui.components.Pill
import com.classvibe.app.ui.components.WarningBanner
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.update.UpdateInfo
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private sealed interface TodayRow {
    data class Lesson(val c: ClassSchedule) : TodayRow
    data class Free(val from: Int, val to: Int) : TodayRow
}

@Composable
fun HomeScreen(
    classes: List<ClassSchedule>,
    exams: List<Exam>,
    attendance: List<AttendanceRecord>,
    holidays: List<Holiday>,
    prefs: AppPrefs,
    now: LocalDateTime,
    update: UpdateInfo?,
    downloadProgress: Int?,
    notificationsEnabled: Boolean,
    exactAlarmsAllowed: Boolean,
    onEnableNotifications: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onGoToImport: () -> Unit,
    onGoToExams: () -> Unit,
    onMark: (ClassSchedule, AttendanceStatus?) -> Unit,
    onAddExamFor: (ClassSchedule) -> Unit,
    onRemoveHoliday: (Holiday) -> Unit,
    onUpdate: (UpdateInfo) -> Unit,
    onSkipUpdate: (UpdateInfo) -> Unit
) {
    val date = now.toLocalDate()
    val nowMinutes = now.hour * 60 + now.minute
    val holiday = ClassCalendar.holidayOn(date, holidays)
    val todayMarks = attendance.filter { it.epochDay == date.toEpochDay() }.associateBy { it.classId }
    val todays = classes.filter { ClassCalendar.isScheduled(it, date, prefs) }.sortedBy { it.startMinutes }
    val activeToday = todays.filter { todayMarks[it.id]?.attendanceStatus != AttendanceStatus.CANCELLED }
    var hideUpdate by rememberSaveable { mutableStateOf(false) }

    // Classes with free periods (≥ 30 min) in between.
    val rows = buildList<TodayRow> {
        todays.forEachIndexed { i, c ->
            if (i > 0) {
                val prevEnd = todays[i - 1].endMinutes
                if (c.startMinutes - prevEnd >= 30) add(TodayRow.Free(prevEnd, c.startMinutes))
            }
            add(TodayRow.Lesson(c))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + com.classvibe.app.ui.components.LocalDockPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TodayHeader(now, activeToday, nowMinutes, holiday) }

        if (!notificationsEnabled) item {
            WarningBanner("Notifications are off, so ClassVibe can't remind you.", "Enable", onEnableNotifications)
        }
        if (!exactAlarmsAllowed) item {
            WarningBanner("Allow \"Alarms & reminders\" so reminders arrive exactly on time.", "Allow", onAllowExactAlarms)
        }
        if (holiday != null) item {
            WarningBanner("🏖️ ${holiday.label} – class reminders are paused today.", "Undo", { onRemoveHoliday(holiday) }, Cv.colors.accent)
        }
        if (update != null && !hideUpdate && update.tag != prefs.skippedVersion) item {
            UpdateCard(update, downloadProgress, onUpdate = { onUpdate(update) }, onLater = { hideUpdate = true }, onSkip = { onSkipUpdate(update) })
        }

        // Weekend prep card
        if (date.dayOfWeek.value in prefs.weekendDays && prefs.weekendPrep) {
            val weekendExams = exams.filter { WeekendPlanner.covers(it, date, prefs) }.sortedBy { it.epochDay }
            if (weekendExams.isNotEmpty()) item { WeekendCard(weekendExams, onGoToExams) }
        }

        // Nearest exam
        exams.filter { !it.date.isBefore(date) }.minByOrNull { it.epochDay * 10_000 + it.timeMinutes }?.let { exam ->
            val days = ChronoUnit.DAYS.between(date, exam.date)
            if (days <= 60) item { ExamCountdownCard(exam, days, onGoToExams) }
        }

        when {
            classes.isEmpty() -> item {
                EmptyState("📂", "No timetable yet", "Import a CSV, a screenshot or a photo of your timetable to get started.", "Import timetable", onGoToImport)
            }
            todays.isEmpty() -> item {
                EmptyState("🎉", "No classes today!", "Touch grass, nap responsibly, or check the weekly timetable.")
            }
            else -> items(rows, key = { r -> if (r is TodayRow.Lesson) "c${r.c.id}" else "f${(r as TodayRow.Free).from}" }) { row ->
                when (row) {
                    is TodayRow.Lesson -> {
                        val mark = todayMarks[row.c.id]?.attendanceStatus
                        ClassCard(
                            schedule = row.c,
                            status = if (holiday != null) ClassStatus.CANCELLED else ClassStatus.of(row.c, nowMinutes),
                            nowMinutes = nowMinutes,
                            attendance = mark,
                            onMark = { s -> onMark(row.c, s) },
                            onAddExam = { onAddExamFor(row.c) },
                            modifier = Modifier.animateItem()
                        )
                    }
                    is TodayRow.Free -> FreePeriod(row.from, row.to, nowMinutes, Modifier.animateItem())
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TodayHeader(now: LocalDateTime, todays: List<ClassSchedule>, nowMinutes: Int, holiday: Holiday?) {
    val dayName = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val date = now.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
    val current = todays.firstOrNull { ClassStatus.of(it, nowMinutes) == ClassStatus.IN_PROGRESS }
    val next = todays.firstOrNull { it.startMinutes > nowMinutes }
    val remaining = todays.count { it.endMinutes > nowMinutes }
    val subtitle = when {
        holiday != null -> "Holiday mode 🏖️"
        todays.isEmpty() -> "Free day ✨"
        current != null -> "Now: ${current.course} 🔥"
        next != null -> {
            val mins = next.startMinutes - nowMinutes
            val inText = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "$mins min"
            "Next: ${next.course} in $inText"
        }
        else -> "All done for today 🎉 Go recharge."
    }
    val brand = Cv.colors.brand
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = brand),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(brand, Color(0xFF1C77C9), Cv.colors.accent.copy(alpha = 0.55f))))
                .padding(20.dp)
        ) {
            Text("TODAY'S CLASSES", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(4.dp))
            Text(dayName, style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(date, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(14.dp))
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderPill("${todays.size} classes")
                HeaderPill("$remaining left")
            }
        }
    }
}

@Composable
private fun HeaderPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun FreePeriod(from: Int, to: Int, nowMinutes: Int, modifier: Modifier = Modifier) {
    val length = to - from
    val label = if (length >= 60) "${length / 60}h ${if (length % 60 > 0) "${length % 60}m" else ""}".trim() else "$length min"
    val active = nowMinutes in from until to
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (active) Cv.colors.accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("☕", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (active) "Free period – now!" else "Free period · $label", style = MaterialTheme.typography.titleSmall)
            Text(
                "${TimeUtils.format(from)} – ${TimeUtils.format(to)} · library, lunch or a quick revision?",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExamCountdownCard(exam: Exam, days: Long, onOpen: () -> Unit) {
    val urgent = days <= 3
    val color = if (urgent) Cv.colors.danger else Cv.colors.accent
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Text(if (days <= 0) "🍀" else days.toString(), style = MaterialTheme.typography.headlineMedium, color = color)
                Text(if (days <= 0) "today" else if (days == 1L) "day" else "days", style = MaterialTheme.typography.labelSmall, color = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Pill("${exam.examType.emoji} ${exam.typeLabel}", color)
                Spacer(Modifier.height(4.dp))
                Text(exam.course, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    exam.date.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + " · " + TimeUtils.formatFriendly(exam.timeMinutes) +
                        " · " + ExamPlanner.countdownText(days, ExamReminderKind.MILESTONE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WeekendCard(exams: List<Exam>, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("🗓️ This weekend's prep", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            exams.take(6).forEach { e ->
                Text(
                    "${e.examType.emoji} ${e.course} — ${e.typeLabel.lowercase()}, ${e.date.format(DateTimeFormatter.ofPattern("EEE d MMM"))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("Two focused sessions this weekend = a calm week 💪", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun UpdateCard(info: UpdateInfo, progress: Int?, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("🚀 Update available: ${info.tag}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (info.title.isNotBlank() && info.title != info.tag) Text(info.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (info.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(com.classvibe.app.update.UpdateChecker.plainNotes(info.notes).take(400), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            if (progress != null) {
                Text(
                    if (progress > 100) "🔐 Verifying & installing… Android will ask you to tap Update" else "⬇️ Downloading ${info.apkName} – $progress%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(6.dp))
                if (progress > 100) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                else androidx.compose.material3.LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onUpdate) { Text("Update") }
                OutlinedButton(onClick = onLater) { Text("Later") }
                TextButton(onClick = onSkip) { Text("Skip version") }
            }
        }
    }
}
