package com.classvibe.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.parser.ClassDraft
import com.classvibe.app.parser.Confidence
import com.classvibe.app.parser.CsvTimetableParser
import com.classvibe.app.parser.ExamCsvParser
import com.classvibe.app.parser.SourceRegion
import com.classvibe.app.ui.ImportStage
import com.classvibe.app.ui.ImportUiState
import com.classvibe.app.ui.components.ClassEditorFields
import com.classvibe.app.ui.components.TypeTag
import com.classvibe.app.ui.components.WeeklyGrid
import com.classvibe.app.ui.theme.Cv
import kotlin.math.max
import kotlin.math.min

private val CSV_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "application/csv", "text/plain",
    "application/vnd.ms-excel", "application/octet-stream"
)
private val IMAGE_TYPES = arrayOf("image/png", "image/jpeg", "image/webp")

@Composable
fun ImportScreen(
    state: ImportUiState,
    knownCourses: List<String>,
    knownRooms: List<String>,
    knownInstructors: List<String>,
    onUris: (List<Uri>) -> Unit,
    newCameraUri: () -> Uri,
    onCameraResult: (Boolean) -> Unit,
    onPasteText: (String) -> Unit,
    onLoadSample: () -> Unit,
    onReplacePending: (Int, Bitmap) -> Unit,
    onNextCrop: () -> Unit,
    onUpdateDraft: (ClassDraft) -> Unit,
    onConfirmDraft: (Long) -> Unit,
    onRemoveDraft: (Long) -> Unit,
    onAddDraft: () -> Unit,
    onReplaceChange: (Boolean) -> Unit,
    onToggleGrid: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onUris(listOf(it)) } }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onUris(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> onCameraResult(ok) }
    val templateSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(CsvTimetableParser.TEMPLATE.toByteArray()) } }
    }
    val examTemplateSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(ExamCsvParser.TEMPLATE.toByteArray()) } }
    }

    Box(Modifier.fillMaxSize()) {
        when (state.stage) {
            ImportStage.PICK -> PickerContent(
                error = state.error,
                onCsv = { csvPicker.launch(CSV_TYPES) },
                onImages = { imagePicker.launch(IMAGE_TYPES) },
                onCamera = { camera.launch(newCameraUri()) },
                onPaste = { onPasteText(clipboard.getText()?.text.orEmpty()) },
                onSample = onLoadSample,
                onManual = onAddDraft,
                onTemplate = { templateSaver.launch("classvibe_timetable_template.csv") },
                onExamTemplate = { examTemplateSaver.launch("classvibe_exams_template.csv") }
            )
            ImportStage.CROP -> {
                val img = state.pendingImages.getOrNull(state.cropIndex)
                if (img != null) {
                    CropScreen(
                        bitmap = img, index = state.cropIndex, total = state.pendingImages.size,
                        onReplace = { onReplacePending(state.cropIndex, it) },
                        onNext = onNextCrop,
                        onCancel = onDiscard
                    )
                }
            }
            ImportStage.PARSING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cv.colors.brandText)
                    Spacer(Modifier.height(16.dp))
                    Text("Reading your timetable… 🔍", style = MaterialTheme.typography.titleMedium)
                    Text("Enhancing image, detecting the table, double-checking every cell.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("100% on your phone – nothing is uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ImportStage.REVIEW -> ReviewContent(
                state, knownCourses, knownRooms, knownInstructors,
                onPickAnother = { imagePicker.launch(IMAGE_TYPES) },
                onUpdateDraft, onConfirmDraft, onRemoveDraft, onAddDraft, onReplaceChange, onToggleGrid, onDiscard, onSave
            )
        }
    }
}

@Composable
private fun PickerContent(
    error: String?,
    onCsv: () -> Unit,
    onImages: () -> Unit,
    onCamera: () -> Unit,
    onPaste: () -> Unit,
    onSample: () -> Unit,
    onManual: () -> Unit,
    onTemplate: () -> Unit,
    onExamTemplate: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📥", style = MaterialTheme.typography.displayMedium)
                Text("Import your timetable", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Screenshots and photos are read on-device, cleaned up and double-checked. You review every row before saving.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = onImages, modifier = Modifier.fillMaxWidth()) { Text("🖼️ Screenshots / images (PNG, JPEG)") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("📸 Take a photo of a printed timetable") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCsv, modifier = Modifier.fillMaxWidth()) { Text("📄 CSV file (100% exact)") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPaste) { Text("📋 Paste CSV") }
                    TextButton(onClick = onSample) { Text("✨ Sample") }
                    TextButton(onClick = onManual) { Text("✏️ Manual") }
                }
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp)) {
                Text("📸 For the most accurate screenshot import", style = MaterialTheme.typography.titleMedium, color = Cv.colors.brandText)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "Crop to just the table (you'll get a crop step).",
                    "Long timetable? Pick several screenshots – overlaps are merged.",
                    "Photos: straight-on, bright light, no shadows. Tilt is fixed automatically.",
                    "Rows marked 🟡 need a quick look, 🔴 must be fixed. Tap 📷 to compare with the image.",
                    "Corrections you make are remembered for next time (abbreviations, spellings)."
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp)) {
                Text("CSV format", style = MaterialTheme.typography.titleMedium, color = Cv.colors.brandText)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Day,Start Time,End Time,Course,Class Type,Room,Instructor,Week\nMonday,9:30,10:30,Algorithms,Lecture,1205-BL1-SF,Dr. Sharmila Banu,Every",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onTemplate) { Text("⬇️ Timetable template") }
                    TextButton(onClick = onExamTemplate) { Text("⬇️ Exams template") }
                }
            }
        }
    }
}

@Composable
private fun ReviewContent(
    state: ImportUiState,
    knownCourses: List<String>,
    knownRooms: List<String>,
    knownInstructors: List<String>,
    onPickAnother: () -> Unit,
    onUpdate: (ClassDraft) -> Unit,
    onConfirm: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAdd: () -> Unit,
    onReplaceChange: (Boolean) -> Unit,
    onToggleGrid: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit
) {
    var regionToShow by remember { mutableStateOf<SourceRegion?>(null) }
    val courseOptions = (knownCourses + state.drafts.map { it.course }).filter { it.isNotBlank() }.distinct().sorted()
    val roomOptions = (knownRooms + state.drafts.map { it.room }).filter { it.isNotBlank() }.distinct().sorted()
    val instructorOptions = (knownInstructors + state.drafts.map { it.instructor }).filter { it.isNotBlank() }.distinct().sorted()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SummaryCard(state, onPickAnother, onToggleGrid) }
            if (state.showGrid) item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Preview – compare with your original timetable", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp))
                        WeeklyGrid(
                            classes = state.drafts.mapIndexedNotNull { i, d -> d.toEntityOrNull()?.copy(id = i.toLong() + 1) },
                            today = 0, nowMinutes = 0
                        )
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Replace existing timetable", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.replaceExisting) "Old classes will be removed" else "New classes will be added to your current ones",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.replaceExisting, onCheckedChange = onReplaceChange)
                    }
                }
            }
            items(state.drafts, key = { it.key }) { draft ->
                DraftCard(
                    draft = draft,
                    canShowImage = draft.region != null && state.displayImages.isNotEmpty(),
                    courseOptions = courseOptions, roomOptions = roomOptions, instructorOptions = instructorOptions,
                    onUpdate = onUpdate, onConfirm = onConfirm, onRemove = onRemove,
                    onShowImage = { regionToShow = draft.region },
                    modifier = Modifier.animateItem()
                )
            }
            item {
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Add a class")
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Discard") }
                Button(onClick = onSave, enabled = state.drafts.isNotEmpty(), modifier = Modifier.weight(1.4f)) {
                    Text(if (state.invalidCount > 0) "Fix ${state.invalidCount} red row(s)" else "Save ${state.drafts.size} classes")
                }
            }
        }
    }

    regionToShow?.let { region ->
        val image = state.displayImages.getOrNull(region.imageIndex)
        AlertDialog(
            onDismissRequest = { regionToShow = null },
            title = { Text("📷 From your image") },
            text = {
                if (image == null) Text("Image not available.")
                else {
                    val pad = 24
                    val l = max(0, region.left - pad)
                    val t = max(0, region.top - pad)
                    val r = min(image.width, region.right + pad)
                    val b = min(image.height, region.bottom + pad)
                    val crop = remember(region) {
                        if (r - l > 4 && b - t > 4) Bitmap.createBitmap(image, l, t, r - l, b - t) else image
                    }
                    Image(
                        crop.asImageBitmap(), contentDescription = "Original cell",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
            },
            confirmButton = { TextButton(onClick = { regionToShow = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun SummaryCard(state: ImportUiState, onPickAnother: () -> Unit, onToggleGrid: () -> Unit) {
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val green = state.drafts.count { it.confidence == Confidence.HIGH }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Text("Review before saving", style = MaterialTheme.typography.titleLarge)
            Text(
                "${state.drafts.size} classes found • ${state.source}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "🟢 $green look right   🟡 ${state.checkCount} to check   🔴 ${state.invalidCount} to fix",
                style = MaterialTheme.typography.labelLarge
            )
            state.error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            state.warnings.forEach { w -> Text("• $w", style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = onToggleGrid) { Text(if (state.showGrid) "Hide grid" else "▦ Grid preview") }
                if (state.displayImages.isNotEmpty()) TextButton(onClick = onPickAnother) { Text("Other images") }
                if (state.rawText != null) TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide text" else "Scanned text") }
            }
            if (showRaw && state.rawText != null) {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(state.rawText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DraftCard(
    draft: ClassDraft,
    canShowImage: Boolean,
    courseOptions: List<String>,
    roomOptions: List<String>,
    instructorOptions: List<String>,
    onUpdate: (ClassDraft) -> Unit,
    onConfirm: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onShowImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(draft.key) { mutableStateOf(draft.confidence != Confidence.HIGH) }
    val cv = Cv.colors
    val dotColor = when (draft.confidence) {
        Confidence.HIGH -> cv.success
        Confidence.MEDIUM -> cv.warning
        Confidence.LOW -> cv.danger
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(dotColor))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(draft.course.ifBlank { "Untitled class" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${draft.day.ifBlank { "?" }} • ${draft.startTime.ifBlank { "?" }}–${draft.endTime.ifBlank { "?" }}" +
                            if (draft.room.isNotBlank()) " • ${draft.room}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draft.isValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                TypeTag(draft.classType)
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (expanded) "Collapse" else "Edit")
            }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    if (draft.flags.isNotEmpty()) {
                        draft.flags.forEach { f -> Text("🟡 $f", style = MaterialTheme.typography.bodySmall, color = cv.warning) }
                        Spacer(Modifier.height(6.dp))
                    }
                    ClassEditorFields(
                        draft = draft, onChange = onUpdate,
                        courseSuggestions = courseOptions, roomSuggestions = roomOptions, instructorSuggestions = instructorOptions
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (canShowImage) TextButton(onClick = onShowImage) { Text("📷 See in image") }
                        if (draft.confidence == Confidence.MEDIUM) TextButton(onClick = { onConfirm(draft.key) }) { Text("✓ Looks right") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onRemove(draft.key) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
