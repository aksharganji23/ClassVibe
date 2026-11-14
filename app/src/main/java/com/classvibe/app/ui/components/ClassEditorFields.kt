package com.classvibe.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.classvibe.app.parser.ClassDraft
import com.classvibe.app.parser.DayParser

val CLASS_TYPES = listOf("Lecture", "Lab", "Tutorial", "Seminar")

/** Editable form for a class: used by the import preview and the edit dialog. */
@Composable
fun ClassEditorFields(
    draft: ClassDraft,
    onChange: (ClassDraft) -> Unit,
    modifier: Modifier = Modifier,
    courseSuggestions: List<String> = emptyList(),
    roomSuggestions: List<String> = emptyList(),
    instructorSuggestions: List<String> = emptyList()
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (courseSuggestions.isEmpty()) {
            OutlinedTextField(
                value = draft.course,
                onValueChange = { onChange(draft.copy(course = it)) },
                label = { Text("Course") },
                isError = draft.courseError() != null,
                supportingText = draft.courseError()?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            DropdownTextField(
                label = "Course",
                value = draft.course,
                options = courseSuggestions,
                onValueChange = { onChange(draft.copy(course = it)) },
                error = draft.courseError(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownTextField(
                label = "Day",
                value = draft.day,
                options = DayParser.ALL_NAMES,
                onValueChange = { onChange(draft.copy(day = it)) },
                error = draft.dayError(),
                modifier = Modifier.weight(1f)
            )
            DropdownTextField(
                label = "Class type",
                value = draft.classType,
                options = CLASS_TYPES,
                onValueChange = { onChange(draft.copy(classType = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.startTime,
                onValueChange = { onChange(draft.copy(startTime = it)) },
                label = { Text("Start") },
                placeholder = { Text("09:30") },
                singleLine = true,
                isError = draft.startError() != null,
                supportingText = draft.startError()?.let { { Text(it) } },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = draft.endTime,
                onValueChange = { onChange(draft.copy(endTime = it)) },
                label = { Text("End") },
                placeholder = { Text("10:30") },
                singleLine = true,
                isError = draft.endError() != null,
                supportingText = draft.endError()?.let { { Text(it) } },
                modifier = Modifier.weight(1f)
            )
        }
        DropdownTextField(
            label = "Room",
            value = draft.room,
            options = roomSuggestions,
            onValueChange = { onChange(draft.copy(room = it)) },
            placeholder = "1205-BL1-SF",
            modifier = Modifier.fillMaxWidth()
        )
        DropdownTextField(
            label = "Instructor",
            value = draft.instructor,
            options = instructorSuggestions,
            onValueChange = { onChange(draft.copy(instructor = it)) },
            modifier = Modifier.fillMaxWidth()
        )
        SelectField(
            label = "Repeats",
            value = ClassDraft.PARITY_LABELS[draft.weekParity.coerceIn(0, 2)],
            options = ClassDraft.PARITY_LABELS,
            onSelect = { onChange(draft.copy(weekParity = ClassDraft.PARITY_LABELS.indexOf(it).coerceAtLeast(0))) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
