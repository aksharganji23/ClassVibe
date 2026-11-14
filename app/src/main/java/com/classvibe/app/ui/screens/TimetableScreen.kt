package com.classvibe.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.ClassStatus
import com.classvibe.app.parser.ClassDraft
import com.classvibe.app.parser.DayParser
import com.classvibe.app.ui.components.ClassCard
import com.classvibe.app.ui.components.ClassEditorFields
import com.classvibe.app.ui.components.EmptyState
import com.classvibe.app.ui.components.Pill
import com.classvibe.app.ui.components.WeeklyGrid
import com.classvibe.app.ui.theme.Cv
import java.time.LocalDateTime

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimetableScreen(
    allClasses: List<ClassSchedule>,
    prefs: AppPrefs,
    now: LocalDateTime,
    isVisible: Boolean,
    onSaveEdit: (ClassDraft) -> Unit,
    onDelete: (ClassSchedule) -> Unit,
    onAddExamFor: (ClassSchedule) -> Unit,
    onGoToImport: () -> Unit
) {
    val today = now.dayOfWeek.value
    val nowMinutes = now.hour * 60 + now.minute
    var selectedDay by rememberSaveable { mutableIntStateOf(0) } // 0 = all days
    var gridView by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<ClassSchedule, ClassDraft>?>(null) }
    val listState = rememberLazyListState()

    if (allClasses.isEmpty()) {
        EmptyState("🗓️", "Your week is empty", "Import a timetable to see it here.", "Import timetable", onGoToImport)
        return
    }

    val byDay = allClasses
        .filter { selectedDay == 0 || it.dayOfWeek == selectedDay }
        .groupBy { it.dayOfWeek }
        .toSortedMap()
    val daysWithClasses = allClasses.map { it.dayOfWeek }.distinct().sorted()

    // Auto-scroll to today's schedule whenever the tab becomes visible.
    LaunchedEffect(isVisible, selectedDay, gridView) {
        if (!isVisible || gridView || selectedDay != 0) return@LaunchedEffect
        var index = 0
        for ((day, list) in byDay) {
            if (day >= today) break
            index += 1 + list.size
        }
        if (byDay.keys.any { it >= today }) listState.animateScrollToItem(index)
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item { androidx.compose.material3.AssistChip(onClick = onGoToImport, label = { Text("＋ Import") }) }
            item { FilterChip(selected = !gridView, onClick = { gridView = false }, label = { Text("☰ List") }) }
            item { FilterChip(selected = gridView, onClick = { gridView = true }, label = { Text("▦ Grid") }) }
            if (!gridView) {
                item { Spacer(Modifier.width(8.dp)) }
                item { FilterChip(selected = selectedDay == 0, onClick = { selectedDay = 0 }, label = { Text("All") }) }
                items(daysWithClasses) { day ->
                    FilterChip(
                        selected = selectedDay == day,
                        onClick = { selectedDay = day },
                        label = { Text(DayParser.short(day) + if (day == today) " •" else "") }
                    )
                }
            }
        }

        if (gridView) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                WeeklyGrid(allClasses, today, nowMinutes, onClick = { c -> editing = c to ClassDraft.fromEntity(c) })
                Text(
                    "Tip: tap a class to edit it. Swipe the grid sideways to see more days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                byDay.forEach { (day, list) ->
                    stickyHeader(key = "header_$day") {
                        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(DayParser.name(day), style = MaterialTheme.typography.titleLarge,
                                    color = if (day == today) Cv.colors.brandText else MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${list.size} class${if (list.size == 1) "" else "es"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (day == today) {
                                    Spacer(Modifier.width(10.dp))
                                    Pill("TODAY", Cv.colors.accent)
                                }
                            }
                        }
                    }
                    items(list.sortedBy { it.startMinutes }, key = { it.id }) { c ->
                        val isToday = day == today && ClassCalendar.isScheduled(c, now.toLocalDate(), prefs)
                        ClassCard(
                            schedule = c,
                            status = if (isToday) ClassStatus.of(c, nowMinutes) else null,
                            nowMinutes = nowMinutes,
                            compact = true,
                            modifier = Modifier.animateItem(),
                            onAddExam = { onAddExamFor(c) },
                            onClick = { editing = c to ClassDraft.fromEntity(c) }
                        )
                    }
                }
                item {
                    Text(
                        "Tip: tap any class to edit or delete it. Use ⋮ to add an exam for that subject.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    editing?.let { (original, draft) ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit class") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ClassEditorFields(
                        draft = draft,
                        onChange = { editing = original to it },
                        courseSuggestions = allClasses.map { it.course }.distinct().sorted(),
                        roomSuggestions = allClasses.map { it.room }.filter { it.isNotBlank() }.distinct().sorted(),
                        instructorSuggestions = allClasses.map { it.instructor }.filter { it.isNotBlank() }.distinct().sorted()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = draft.isValid, onClick = { onSaveEdit(draft); editing = null }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onDelete(original); editing = null }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        )
    }
}
