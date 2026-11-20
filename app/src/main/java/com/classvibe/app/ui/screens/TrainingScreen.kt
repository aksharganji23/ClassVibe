package com.classvibe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.DefaultTraining
import com.classvibe.app.data.TrainingDay
import com.classvibe.app.parser.TimeUtils
import com.classvibe.app.ui.components.DateField
import com.classvibe.app.ui.components.LocalDockPadding
import com.classvibe.app.ui.components.TimeField
import com.classvibe.app.ui.theme.Cv
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

/** 🎯 Placement training: all days, the next one highlighted, attendance and reminders. */
@Composable
fun TrainingScreen(
    days: List<TrainingDay>,
    now: LocalDateTime,
    onSave: (TrainingDay) -> Unit,
    onDelete: (TrainingDay) -> Unit,
    onMark: (TrainingDay, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val today = now.toLocalDate()
    val next = DefaultTraining.next(days, now)
    val done = days.count { !it.endsAt.isAfter(now) }
    val attended = days.count { it.status == "ATTENDED" }
    val missed = days.count { it.status == "MISSED" }
    var editing by remember { mutableStateOf<TrainingDay?>(null) }
    var adding by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Jump to the next day so it's always in view.
    LaunchedEffect(next?.id) {
        val idx = days.indexOfFirst { it.id == next?.id }
        if (idx > 1) listState.scrollToItem(idx + 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalDockPadding.current),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ------------------------------------------------ summary / next day
        item {
            val brand = Cv.colors.brandText
            Card(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(brand, Color(0xFF6A3DE8), Cv.colors.accent.copy(alpha = 0.6f))))
                        .padding(20.dp)
                ) {
                    Text("🎯 PLACEMENT TRAINING", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    if (next == null) {
                        Text("Training complete 🎉", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("All ${days.size} days are done. Go get that offer!", color = Color.White.copy(alpha = 0.9f))
                    } else {
                        val running = !now.isBefore(next.startsAt)
                        val inDays = ChronoUnit.DAYS.between(today, next.date)
                        Text(
                            if (running) "${next.label} is on now" else "Next: ${next.label}",
                            color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                        )
                        Text(
                            next.date.format(DAY_FMT) + " • " + TimeUtils.formatFriendly(next.startMinutes) + " – " + TimeUtils.formatFriendly(next.endMinutes),
                            color = Color.White.copy(alpha = 0.92f)
                        )
                        Text(
                            when {
                                running -> "⏳ Ends in " + friendly(Duration.between(now, next.endsAt).toMinutes())
                                inDays == 0L -> "⏰ Today – starts in " + friendly(Duration.between(now, next.startsAt).toMinutes())
                                inDays == 1L -> "📅 Tomorrow"
                                else -> "📅 In $inDays days"
                            },
                            color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (days.isEmpty()) 0f else done / days.size.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color.White, trackColor = Color.White.copy(alpha = 0.25f)
                    )
                    Text(
                        "$done of ${days.size} days done" + (if (attended + missed > 0) " • ✅ $attended attended • ❌ $missed missed" else ""),
                        color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { adding = true }) { Text("＋ Add day") }
                OutlinedButton(onClick = onOpenSettings) { Text("⚙️ Times & reminders") }
            }
        }

        // ------------------------------------------------ all days
        items(days, key = { it.id }) { d ->
            TrainingDayCard(d, isNext = d.id == next?.id, now = now, onClick = { editing = d }, onMark = { st -> onMark(d, st) })
        }
        if (days.isEmpty()) item {
            Text("No training days yet – tap ＋ Add day, or restore the default dates in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (adding || editing != null) {
        val base = editing
        TrainingDayDialog(
            day = base ?: TrainingDay(
                dayNumber = (days.maxOfOrNull { it.dayNumber } ?: 0) + 1,
                epochDay = today.toEpochDay(),
                startMinutes = days.lastOrNull()?.startMinutes ?: DefaultTraining.DEFAULT_START,
                endMinutes = days.lastOrNull()?.endMinutes ?: DefaultTraining.DEFAULT_END
            ),
            isNew = base == null,
            onSave = { onSave(it); adding = false; editing = null },
            onDelete = { base?.let(onDelete); editing = null },
            onDismiss = { adding = false; editing = null }
        )
    }
}

private fun friendly(minutes: Long): String = when {
    minutes < 60 -> "${minutes.coerceAtLeast(0)} min"
    minutes < 24 * 60 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "${minutes / (24 * 60)} days"
}

@Composable
private fun TrainingDayCard(d: TrainingDay, isNext: Boolean, now: LocalDateTime, onClick: () -> Unit, onMark: (String) -> Unit) {
    val over = !d.endsAt.isAfter(now)
    val running = !now.isBefore(d.startsAt) && !over
    val brand = Cv.colors.brandText
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isNext) brand.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isNext) Modifier.border(2.dp, brand, RoundedCornerShape(20.dp)) else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isNext || running) brand else if (over) MaterialTheme.colorScheme.surfaceContainerHighest else brand.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${d.dayNumber}", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = if (isNext || running) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.label, style = MaterialTheme.typography.titleMedium,
                    color = if (over && !isNext) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    d.date.format(DAY_FMT) + " • " + TimeUtils.formatFriendly(d.startMinutes) + " – " + TimeUtils.formatFriendly(d.endMinutes),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (d.venue.isNotBlank()) Text("📍 ${d.venue}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (over || running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        FilterChip(selected = d.status == "ATTENDED", onClick = { onMark("ATTENDED") }, label = { Text("✅ Attended") })
                        FilterChip(selected = d.status == "MISSED", onClick = { onMark("MISSED") }, label = { Text("❌ Missed") })
                    }
                }
            }
            Text(
                when {
                    running -> "🔴 Now"
                    isNext -> "⭐ Next"
                    over && d.status == "ATTENDED" -> "✅"
                    over && d.status == "MISSED" -> "❌"
                    over -> "Done"
                    else -> ""
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (isNext || running) brand else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrainingDayDialog(day: TrainingDay, isNew: Boolean, onSave: (TrainingDay) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(day.dayNumber.toString()) }
    var date by remember { mutableStateOf(day.date) }
    var start by remember { mutableStateOf<Int?>(day.startMinutes) }
    var end by remember { mutableStateOf<Int?>(day.endMinutes) }
    var title by remember { mutableStateOf(day.title) }
    var venue by remember { mutableStateOf(day.venue) }
    val slots = (7 * 60..19 * 60 step 30).toList()
    val valid = number.toIntOrNull() != null && start != null && end != null && end!! > start!!
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add training day" else "Edit Day ${day.dayNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(number, { number = it.filter(Char::isDigit).take(3) }, label = { Text("Day number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("Date", date, { date = it }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("Starts", start, slots, { start = it }, Modifier.weight(1f))
                    TimeField("Ends", end, slots, { end = it }, Modifier.weight(1f))
                }
                OutlinedTextField(title, { title = it }, label = { Text("Topic (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(venue, { venue = it }, label = { Text("Venue (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (start != null && end != null && end!! <= start!!) Text("End time must be after the start time", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(day.copy(dayNumber = number.toInt(), epochDay = date.toEpochDay(), startMinutes = start!!, endMinutes = end!!, title = title.trim(), venue = venue.trim()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
