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
    openBunkPlanner: Boolean = false
) {
    if (classes.isEmpty() && attendance.isEmpty()) {
        EmptyState("📊", "No stats yet", "Import your timetable and mark attendance from the Today screen or from notifications.")
        return
    }
    val overall = AttendanceStats.overall(attendance)
    val byCourse = AttendanceStats.byCourse(attendance)
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
        if (byCourse.isEmpty()) {
            item {
                Text(
                    "No attendance marked yet. Tap ✅ Attended / ❌ Missed on Today's class cards or on the class-ending notification.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(byCourse, key = { it.course }) { c -> AttendanceCard(c) }
        }

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
}

@Composable
private fun AttendanceCard(c: CourseAttendance) {
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
                Text("${c.percent}%", style = MaterialTheme.typography.headlineSmall, color = color)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { c.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                color = color, trackColor = color.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "✅ ${c.attended} attended · ❌ ${c.missed} missed" + if (c.cancelled > 0) " · 🚫 ${c.cancelled} cancelled" else "",
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
