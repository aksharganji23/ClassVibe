package com.classvibe.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AttendanceRecord
import com.classvibe.app.data.BunkMath
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.Holiday
import com.classvibe.app.ui.components.DateField
import com.classvibe.app.ui.components.SectionCard
import com.classvibe.app.ui.components.SelectField
import com.classvibe.app.ui.theme.Cv
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OVERALL = "Overall (all subjects)"
private val TARGETS = listOf(60, 65, 70, 75, 80, 85, 90)

/** 🧮 Attendance calculator + 🛌 bunk planner (Stats tab). */
@Composable
fun BunkPlannerSection(
    classes: List<ClassSchedule>,
    attendance: List<AttendanceRecord>,
    holidays: List<Holiday>,
    prefs: AppPrefs,
    today: LocalDate,
    startExpanded: Boolean
) {
    val counts = remember(attendance) { BunkMath.countsFrom(attendance) }
    val marks = remember(attendance) { attendance.associateBy { "${it.classId}|${it.epochDay}" } }
    val isActive: (ClassSchedule, LocalDate) -> Boolean = { c, d ->
        ClassCalendar.isActive(c, d, prefs, holidays, marks["${c.id}|${d.toEpochDay()}"])
    }
    var target by rememberSaveable { mutableStateOf(75) }
    val cv = Cv.colors
    fun colorFor(level: BunkMath.Level) = when (level) {
        BunkMath.Level.SAFE -> cv.success
        BunkMath.Level.RISKY -> cv.warning
        BunkMath.Level.DANGER -> cv.danger
    }

    SectionCard("🧮 Bunk planner & attendance calculator", "Can I skip? How many can I skip?", initiallyExpanded = startExpanded) {
        SelectField(
            label = "🎯 Target attendance",
            value = "$target%",
            options = TARGETS.map { "$it%" },
            onSelect = { target = it.removeSuffix("%").toIntOrNull() ?: 75 },
            modifier = Modifier.fillMaxWidth()
        )

        // ---------------- Calculator
        Text("Calculator", style = MaterialTheme.typography.titleSmall)
        val subjects = listOf(OVERALL) + counts.keys.sorted()
        var subject by rememberSaveable { mutableStateOf(OVERALL) }
        val base = if (subject == OVERALL) BunkMath.Counts(counts.values.sumOf { it.attended }, counts.values.sumOf { it.total })
        else counts[subject] ?: BunkMath.Counts(0, 0)
        var attendedText by remember(subject, base) { mutableStateOf(base.attended.toString()) }
        var totalText by remember(subject, base) { mutableStateOf(base.total.toString()) }
        SelectField("Subject (pre-fills from your marks)", subject, subjects, { subject = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                attendedText, { attendedText = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Attended") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                totalText, { totalText = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Total held") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
            )
        }
        val att = attendedText.toIntOrNull() ?: 0
        val tot = maxOf(att, totalText.toIntOrNull() ?: 0)
        val pct = BunkMath.percent(att, tot)
        val level = BunkMath.level(pct, target)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colorFor(level).copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (tot == 0) "No classes counted yet" else "${level.emoji} %.1f%% attendance".format(Locale.ENGLISH, pct),
                    style = MaterialTheme.typography.titleLarge, color = colorFor(level)
                )
                if (tot > 0) {
                    val can = BunkMath.canBunk(att, tot, target)
                    val need = BunkMath.needToAttend(att, tot, target)
                    Text(
                        if (need > 0) "⚠️ Attend the next $need class(es) in a row to reach $target%."
                        else "✅ You can bunk $can more class(es) and stay at $target%+.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(BunkMath.verdict(level, att + tot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------------- Skip-N slider
        if (tot > 0) {
            var skip by remember(subject) { mutableFloatStateOf(1f) }
            val n = skip.toInt()
            val after = BunkMath.afterSkipping(att, tot, n)
            val afterLevel = BunkMath.level(after, target)
            Text(
                "If I skip the next $n ${if (subject == OVERALL) "class" else "${subject.take(24)} class"}${if (n == 1) "" else "es"} → ${afterLevel.emoji} %.1f%%".format(Locale.ENGLISH, after),
                style = MaterialTheme.typography.bodyMedium, color = colorFor(afterLevel)
            )
            Slider(value = skip, onValueChange = { skip = it }, valueRange = 0f..10f, steps = 9)
        }

        HorizontalDivider()

        // ---------------- Day planner
        Text("\"Can I skip this day?\"", style = MaterialTheme.typography.titleSmall)
        var day by rememberSaveable { mutableStateOf(today.plusDays(1).toEpochDay()) }
        val date = LocalDate.ofEpochDay(day)
        DateField("Day to skip", date, onPick = { day = it.toEpochDay() }, minDate = today, modifier = Modifier.fillMaxWidth())
        val plan = BunkMath.planDay(date, classes, counts, target, isActive)
        if (plan.impacts.isEmpty()) {
            Text("🎉 No classes on ${date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))} – nothing to skip!", style = MaterialTheme.typography.bodyMedium)
        } else {
            plan.impacts.forEach { im ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(im.level.emoji)
                    Spacer(Modifier.width(8.dp))
                    Text(im.course, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        "%.0f%% → %.0f%%".format(Locale.ENGLISH, im.before, im.after) + if (im.classes > 1) " (${im.classes})" else "",
                        style = MaterialTheme.typography.labelLarge, color = colorFor(im.level)
                    )
                }
            }
            Text(
                BunkMath.verdict(plan.worst, day.toInt()),
                style = MaterialTheme.typography.titleSmall, color = colorFor(plan.worst)
            )
        }

        // ---------------- Safe-bunk finder
        val safe = BunkMath.safeDays(today.plusDays(1), 14, classes, counts, target, isActive)
        Text("🛌 Safe days to skip (next 2 weeks)", style = MaterialTheme.typography.titleSmall)
        if (safe.isEmpty()) {
            Text("None right now – attend a few more classes first 💪", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                safe.forEach { p ->
                    AssistChip(
                        onClick = { day = p.date.toEpochDay() },
                        label = { Text("${p.worst.emoji} ${p.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))} · ${p.classCount}") }
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Counts only real classes (holidays, odd/even weeks and cancellations are skipped). Based on attendance you've marked in ClassVibe.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
