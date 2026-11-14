package com.classvibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.parser.FuzzyMatcher
import com.classvibe.app.ui.theme.courseColor

/**
 * Subject chooser with colour dots (same colours as the timetable), a search box,
 * and "use as new subject" for anything not in the list.
 */
@Composable
fun SubjectPicker(
    label: String,
    value: String,
    subjects: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    notInTimetable: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Choose a subject") },
            singleLine = true,
            leadingIcon = if (value.isNotBlank()) ({ Box(Modifier.size(12.dp).clip(CircleShape).background(courseColor(value))) }) else null,
            supportingText = if (notInTimetable && value.isNotBlank()) ({ Text("Not in your timetable") }) else null,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val filtered = if (query.isBlank()) subjects else subjects
            .map { it to maxOf(if (it.contains(query.trim(), ignoreCase = true)) 1.0 else 0.0, FuzzyMatcher.similarity(it, query), if (FuzzyMatcher.initials(it).equals(query.trim(), true)) 1.0 else 0.0) }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
        val exact = subjects.any { it.equals(query.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("📘 Choose subject") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search or type a new subject…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (query.isNotBlank() && !exact) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(query.trim()); open = false }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) { Text("➕ Use “${query.trim()}” as a new subject", style = MaterialTheme.typography.bodyLarge) }
                                HorizontalDivider()
                            }
                        }
                        items(filtered) { s ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(s); open = false }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(courseColor(s)))
                                Spacer(Modifier.width(12.dp))
                                Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (filtered.isEmpty() && query.isBlank()) {
                            item { Text("No subjects yet – type one above.", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
        )
    }
}
