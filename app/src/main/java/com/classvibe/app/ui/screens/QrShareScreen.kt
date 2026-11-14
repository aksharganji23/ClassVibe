package com.classvibe.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.ExamType
import com.classvibe.app.data.ShareCodec
import com.classvibe.app.parser.DayParser
import com.classvibe.app.qr.FunnyQr
import com.classvibe.app.ui.AppViewModel
import com.classvibe.app.ui.ScanUi
import com.classvibe.app.ui.ShareSelection
import com.classvibe.app.ui.components.SectionCard
import com.classvibe.app.ui.components.SelectField
import com.classvibe.app.util.ShareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val OWN_CAPTION = "✏️ Write my own…"

/** 🔳 QR sharing: choose what to share, funny QR design, scan + merge. */
@Composable
fun QrShareScreen(vm: AppViewModel, scan: ScanUi, classes: List<ClassSchedule>) {
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = share, 1 = scan
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("📤 Share my data") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("📥 Scan a friend's QR") })
        }
        if (mode == 0) ShareSection(vm, classes) else ScanSection(vm, scan)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ShareSection(vm: AppViewModel, classes: List<ClassSchedule>) {
    val context = LocalContext.current
    var sel by remember { mutableStateOf(ShareSelection()) }
    var theme by rememberSaveable { mutableStateOf(FunnyQr.Theme.CLASSIC) }
    var caption by rememberSaveable { mutableStateOf(FunnyQr.CAPTIONS.first()) }
    var ownCaption by rememberSaveable { mutableStateOf(false) }
    var part by remember { mutableIntStateOf(0) }
    LaunchedEffect(sel) { part = 0 }

    SectionCard("✅ What to share", "Pick exactly what goes into the QR code", initiallyExpanded = true) {
        CheckRow("🗓️ Timetable", sel.classes) { sel = sel.copy(classes = it) }
        if (sel.classes) {
            val days = classes.map { it.dayOfWeek }.distinct().sorted()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = sel.days.isEmpty(), onClick = { sel = sel.copy(days = emptySet()) }, label = { Text("All days") })
                days.forEach { d ->
                    FilterChip(
                        selected = d in sel.days,
                        onClick = { sel = sel.copy(days = if (d in sel.days) sel.days - d else sel.days + d) },
                        label = { Text(DayParser.short(d)) }
                    )
                }
            }
        }
        CheckRow("📝 Exams", sel.exams) { sel = sel.copy(exams = it) }
        if (sel.exams) {
            val options = listOf("UPCOMING" to "Upcoming exams", "ALL" to "All exams (incl. past)") +
                ExamType.entries.map { it.name to "Only ${it.emoji} ${it.label}" }
            SelectField(
                "Which exams", options.first { it.first == sel.examFilter }.second, options.map { it.second },
                { picked -> sel = sel.copy(examFilter = options.first { it.second == picked }.first) }, Modifier.fillMaxWidth()
            )
        }
        CheckRow("🏖️ Holidays", sel.holidays) { sel = sel.copy(holidays = it) }
        CheckRow("🏫 Semester dates", sel.semester) { sel = sel.copy(semester = it) }
        CheckRow("📘 My extra subjects", sel.subjects) { sel = sel.copy(subjects = it) }
        CheckRow("📊 Attendance marks (personal!)", sel.attendance) { sel = sel.copy(attendance = it) }
    }

    SectionCard("🎨 QR style", "${theme.label} • funny caption", initiallyExpanded = true) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FunnyQr.Theme.entries.forEach { t -> FilterChip(selected = theme == t, onClick = { theme = t }, label = { Text(t.label) }) }
        }
        SelectField(
            "Caption", if (ownCaption) OWN_CAPTION else caption, FunnyQr.CAPTIONS + OWN_CAPTION,
            { picked -> if (picked == OWN_CAPTION) ownCaption = true else { ownCaption = false; caption = picked } }, Modifier.fillMaxWidth()
        )
        if (ownCaption) {
            OutlinedTextField(caption, { caption = it.take(48) }, label = { Text("Your caption") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }

    val payload = remember(sel, classes) { vm.sharePayload(sel) }
    val parts = remember(payload) { if (payload.isEmpty) emptyList() else ShareCodec.parts(payload) }
    val images by produceState<List<Bitmap>>(emptyList(), parts, theme, caption) {
        value = withContext(Dispatchers.Default) {
            parts.mapIndexed { i, text ->
                FunnyQr.render(text, theme, caption, payload.summary(), if (parts.size > 1) "Part ${i + 1} of ${parts.size}" else null)
            }
        }
    }

    if (payload.isEmpty) {
        Text("Nothing selected yet – tick at least one item above.", color = MaterialTheme.colorScheme.error)
        return
    }
    Text("Contains: ${payload.summary()}" + if (parts.size > 1) " • ${parts.size} QR codes (scan all, any order)" else "", style = MaterialTheme.typography.bodyMedium)
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        val img = images.getOrNull(part.coerceIn(0, maxOf(0, images.size - 1)))
        Box(Modifier.fillMaxWidth().aspectRatio(1080f / 1400f), contentAlignment = Alignment.Center) {
            if (img == null) CircularProgressIndicator()
            else Image(img.asImageBitmap(), contentDescription = "Your ClassVibe QR code", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
        }
    }
    if (parts.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = part > 0, onClick = { part = (part - 1).coerceAtLeast(0) }) { Text("← Previous") }
            Text("Part ${part + 1} / ${parts.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(enabled = part < parts.size - 1, onClick = { part = (part + 1).coerceAtMost(parts.size - 1) }) { Text("Next →") }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val img = images.getOrNull(part)
        if (uri != null && img != null) context.contentResolver.openOutputStream(uri)?.use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = images.isNotEmpty(), onClick = {
            ShareFiles.sharePngs(context, images, "Scan with ClassVibe → Settings → QR sharing ⚡")
        }, modifier = Modifier.weight(1f)) { Text(if (images.size > 1) "📤 Share all ${images.size}" else "📤 Share image") }
        OutlinedButton(enabled = images.isNotEmpty(), onClick = { saver.launch("classvibe_qr_${part + 1}.png") }, modifier = Modifier.weight(1f)) { Text("💾 Save image") }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ScanSection(vm: AppViewModel, scan: ScanUi) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.scanImage(it) } }
    LaunchedEffect(Unit) { if (scan.plan == null && scan.progress == null) vm.resetScan() }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📥 Import from a friend", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing you already have will change – new classes, exams and holidays are added, duplicates are skipped and clashes are shown to you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { vm.scanWithCamera(context) }, enabled = !scan.busy, modifier = Modifier.fillMaxWidth()) { Text("📷 Scan with camera") }
            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !scan.busy, modifier = Modifier.fillMaxWidth()) { Text("🖼️ Scan from a screenshot / photo") }
            if (scan.busy) CircularProgressIndicator()
            scan.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    val plan = scan.plan ?: return
    val payload = scan.payload
    var addClash by remember(plan) { mutableStateOf(setOf<Int>()) }
    var withAttendance by remember(plan) { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(plan.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (plan.newClasses.isNotEmpty()) {
                Text("➕ New classes:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                plan.newClasses.take(12).forEach { c ->
                    Text("• ${DayParser.short(c.dayOfWeek)} ${c.startTimeText} ${c.course}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (plan.newClasses.size > 12) Text("…and ${plan.newClasses.size - 12} more", style = MaterialTheme.typography.bodySmall)
            }
            if (plan.clashes.isNotEmpty()) {
                HorizontalDivider()
                Text("⚠️ Clashes (your class stays – tick to add theirs too):", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                plan.clashes.forEachIndexed { i, (incoming, existing) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = i in addClash, onCheckedChange = { addClash = if (it) addClash + i else addClash - i })
                        Text(
                            "${DayParser.short(incoming.dayOfWeek)} ${incoming.startTimeText}: ${incoming.course} (you have ${existing.course})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            if (payload != null && payload.attendance.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withAttendance, onCheckedChange = { withAttendance = it })
                    Text("Also import ${payload.attendance.size} attendance marks (only for matching classes)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.applyScan(addClash, withAttendance) }) { Text("➕ Add to my ClassVibe") }
                TextButton(onClick = { vm.resetScan() }) { Text("Cancel") }
            }
        }
    }
}
