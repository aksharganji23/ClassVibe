package com.classvibe.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.BuildConfig
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AppSettings
import com.classvibe.app.data.CustomMessage
import com.classvibe.app.data.ExamType
import com.classvibe.app.data.Holiday
import com.classvibe.app.data.MessageStyle
import com.classvibe.app.data.ThemeMode
import com.classvibe.app.messages.MessageBank
import com.classvibe.app.parser.DayParser
import com.classvibe.app.parser.TimeUtils
import com.classvibe.app.ui.AppViewModel
import com.classvibe.app.ui.ReleaseNotes
import com.classvibe.app.ui.components.ConfirmDialog
import com.classvibe.app.ui.components.DATE_LABEL
import com.classvibe.app.ui.components.DateField
import com.classvibe.app.ui.components.SectionCard
import com.classvibe.app.ui.components.SelectField
import com.classvibe.app.ui.components.SwitchRow
import com.classvibe.app.ui.components.WhatsNewDialog
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.update.UpdateChecker
import com.classvibe.app.util.ShareFiles
import kotlinx.coroutines.launch
import java.time.LocalDate

private val TIME_OPTIONS: List<Int> = (10..45).map { it * 30 } // 05:00 … 22:30
private fun timeLabel(m: Int) = TimeUtils.format(m) + "  (" + TimeUtils.formatFriendly(m) + ")"

@Composable
private fun TimeSelect(label: String, minutes: Int, onPick: (Int) -> Unit) {
    SelectField(
        label = label,
        value = timeLabel(minutes),
        options = TIME_OPTIONS.map { timeLabel(it) },
        onSelect = { picked -> TIME_OPTIONS.firstOrNull { timeLabel(it) == picked }?.let(onPick) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    prefs: AppPrefs,
    holidays: List<Holiday>,
    customMessages: List<CustomMessage>,
    recentMessages: List<MessageBank.Logged>,
    updateStatus: String?,
    notificationsEnabled: Boolean,
    exactAlarmsAllowed: Boolean,
    onEnableNotifications: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    cloudBackup: Boolean,
    onOpenQr: () -> Unit
) {
    val context = LocalContext.current
    var showCalendar by remember { mutableStateOf(false) }
    var editSchedule by remember { mutableStateOf<ExamType?>(null) }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.exportBackup()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        vm.importBackup(text)
    }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(vm.timetableCsv().toByteArray()) }
    }

    LaunchedEffect(Unit) { vm.refreshRecentMessages() }

    // Accordion: only one section open at a time.
    val accordion = rememberSaveable { mutableStateOf<String?>("🔔 Class reminders") }
    androidx.compose.runtime.CompositionLocalProvider(com.classvibe.app.ui.components.LocalAccordion provides accordion) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------ Class reminders
        item {
            SectionCard("🔔 Class reminders", "When and how you get reminded", initiallyExpanded = true) {
                SwitchRow("Class reminders", "Turn every class reminder on or off", prefs.remindersEnabled, vm::setRemindersEnabled)
                Text("Remind me (pick as many as you like):", style = MaterialTheme.typography.labelLarge)
                AppSettings.LEAD_OPTIONS.forEach { lead ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = prefs.remindersEnabled) { vm.toggleLead(lead) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = lead in prefs.leadMinutes, onCheckedChange = { vm.toggleLead(lead) }, enabled = prefs.remindersEnabled)
                        Text(AppSettings.leadLabel(lead), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val summary = prefs.leadMinutes.sortedDescending().joinToString(", ") { if (it == 0) "at start" else "$it min" }
                Text(
                    if (prefs.leadMinutes.isEmpty()) "⚠️ No reminder times selected – you won't get class reminders."
                    else "You'll get ${prefs.leadMinutes.size} reminder(s) per class: $summary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prefs.leadMinutes.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                SwitchRow("Class-ending nudge", "5 min before a class ends: what's next, other-block alert, attendance buttons", prefs.classEndNudge, vm::setClassEndNudge)
                SwitchRow("Morning summary", "Today's classes + exam countdowns", prefs.morningSummary, vm::setMorningSummary)
                if (prefs.morningSummary) TimeSelect("Morning summary time", prefs.morningSummaryMinutes, vm::setMorningTime)
                OutlinedButton(onClick = { vm.sendTestNotification(com.classvibe.app.ui.AppViewModel.TestKind.CLASS) }) { Text("🔔 Send a test class reminder") }
                Text(
                    "Tip: the 🔔 at the top tests whatever tab you're on – next class, next exam, attendance or training.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------ Message style
        item {
            SectionCard("💬 Messages", "${vm.messageCount}+ messages • style, ratings, your own lines") {
                Text("Message style", style = MaterialTheme.typography.labelLarge)
                MessageStyle.entries.forEach { style ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.setStyle(style) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = prefs.messageStyle == style, onClick = { vm.setStyle(style) })
                        Column {
                            Text(style.label, style = MaterialTheme.typography.bodyLarge)
                            Text(style.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Text("Rate recent messages (👎 = show less)", style = MaterialTheme.typography.labelLarge)
                if (recentMessages.isEmpty()) {
                    Text("Nothing yet – ratings appear after your first reminders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                recentMessages.take(8).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.rateMessage(m.id, if (m.rating == 1) 0 else 1) }) { Text(if (m.rating == 1) "👍✓" else "👍") }
                        TextButton(onClick = { vm.rateMessage(m.id, if (m.rating == -1) 0 else -1) }) { Text(if (m.rating == -1) "👎✓" else "👎") }
                    }
                }
                HorizontalDivider()
                CustomMessageEditor(customMessages, onAdd = vm::addCustomMessage, onDelete = vm::deleteCustomMessage)
            }
        }

        // ------------------------------------------------ Exams
        item {
            SectionCard("📝 Exam reminders", "Countdown schedules per exam type") {
                SwitchRow("Exam reminders", null, prefs.examRemindersEnabled, vm::setExamReminders)
                Text("Remind me about:", style = MaterialTheme.typography.labelLarge)
                ExamType.entries.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.toggleExamType(type.name) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = type.name !in prefs.disabledExamTypes, onCheckedChange = { vm.toggleExamType(type.name) })
                        Column(Modifier.weight(1f)) {
                            Text("${type.emoji} ${type.label}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                scheduleSummary(type, prefs),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { editSchedule = type }) { Text("Edit") }
                    }
                }
                TimeSelect("Countdown reminders at", prefs.examCountdownMinutes, vm::setExamCountdownTime)
                TimeSelect("\"Evening before\" reminders at", prefs.examEveningMinutes, vm::setExamEveningTime)
            }
        }

        // ------------------------------------------------ Weekend prep
        item {
            SectionCard("🗓️ Weekend prep", "Saturday/Sunday summaries of next week's exams") {
                SwitchRow("Weekend prep reminders", null, prefs.weekendPrep, vm::setWeekendPrep)
                Text("My weekend days", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..7).toList()) { d ->
                        FilterChip(selected = d in prefs.weekendDays, onClick = { vm.toggleWeekendDay(d) }, label = { Text(DayParser.short(d)) })
                    }
                }
                TimeSelect("Morning reminder", prefs.weekendMorningMinutes, vm::setWeekendMorning)
                TimeSelect("Evening reminder", prefs.weekendEveningMinutes, vm::setWeekendEvening)
                SelectField(
                    label = "Look ahead",
                    value = if (prefs.weekendLookaheadWeeks == 2) "2 weeks" else "1 week (+ sem/mid exams 2 weeks out)",
                    options = listOf("1 week (+ sem/mid exams 2 weeks out)", "2 weeks"),
                    onSelect = { vm.setWeekendLookahead(if (it == "2 weeks") 2 else 1) },
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchRow("\"No exams next week\" message", "Send a chill message when nothing is due", prefs.weekendNoExamMessage, vm::setWeekendNoExamMessage)
            }
        }

        // ------------------------------------------------ Semester & holidays
        item {
            SectionCard("🏫 Semester & holidays", "Odd/even weeks, semester dates, breaks") {
                Text("Semester dates (used for odd/even weeks; no class reminders outside them).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DateField("Semester starts", prefs.semesterStart, onPick = { vm.setSemesterStart(it) }, modifier = Modifier.weight(1f))
                    if (prefs.semesterStart != null) TextButton(onClick = { vm.setSemesterStart(null) }) { Text("Clear") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DateField("Semester ends", prefs.semesterEnd, onPick = { vm.setSemesterEnd(it) }, modifier = Modifier.weight(1f))
                    if (prefs.semesterEnd != null) TextButton(onClick = { vm.setSemesterEnd(null) }) { Text("Clear") }
                }
                HorizontalDivider()
                Text("Holidays & breaks", style = MaterialTheme.typography.labelLarge)
                holidays.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("🏖️ ${h.label}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                LocalDate.ofEpochDay(h.startEpochDay).format(DATE_LABEL) +
                                    if (h.endEpochDay != h.startEpochDay) " → " + LocalDate.ofEpochDay(h.endEpochDay).format(DATE_LABEL) else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.deleteHoliday(h) }) { Icon(Icons.Default.Delete, "Remove holiday") }
                    }
                }
                HolidayAdder(onAdd = vm::addHoliday)
                OutlinedButton(onClick = { vm.pauseToday() }) { Text("⏸️ Pause class reminders today") }
            }
        }

        // ------------------------------------------------ Placement training
        item {
            val trainingDays by vm.training.collectAsState()
            var confirmReset by remember { mutableStateOf(false) }
            SectionCard("🎯 Placement training", if (prefs.trainingEnabled) "On • ${trainingDays.size} days" else "Off") {
                SwitchRow(
                    "I'm in placement training",
                    "Adds the training calendar, a Training tab in the dock and reminders",
                    prefs.trainingEnabled, vm::setTrainingEnabled
                )
                if (prefs.trainingEnabled) {
                    val first = trainingDays.firstOrNull()
                    var start by remember(first?.startMinutes) { mutableStateOf<Int?>(first?.startMinutes ?: com.classvibe.app.data.DefaultTraining.DEFAULT_START) }
                    var end by remember(first?.endMinutes) { mutableStateOf<Int?>(first?.endMinutes ?: com.classvibe.app.data.DefaultTraining.DEFAULT_END) }
                    val slots = (7 * 60..19 * 60 step 30).toList()
                    Text("Training time (all days)", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.classvibe.app.ui.components.TimeField("Starts", start, slots, { start = it }, Modifier.weight(1f))
                        com.classvibe.app.ui.components.TimeField("Ends", end, slots, { end = it }, Modifier.weight(1f))
                    }
                    OutlinedButton(
                        enabled = start != null && end != null && end!! > start!!,
                        onClick = { vm.setTrainingTimes(start!!, end!!) }
                    ) { Text("Apply to all days") }
                    HorizontalDivider()
                    val eveningOptions = listOf(-1) + (17 * 60..22 * 60 step 30).toList()
                    SelectField(
                        "Reminder the evening before",
                        if (prefs.trainingEveningMinutes < 0) "Off" else com.classvibe.app.parser.TimeUtils.formatFriendly(prefs.trainingEveningMinutes),
                        eveningOptions.map { if (it < 0) "Off" else com.classvibe.app.parser.TimeUtils.formatFriendly(it) },
                        { picked ->
                            val v = eveningOptions.firstOrNull { (if (it < 0) "Off" else com.classvibe.app.parser.TimeUtils.formatFriendly(it)) == picked } ?: -1
                            vm.setTrainingReminders(v, prefs.trainingLeadMinutes)
                        },
                        Modifier.fillMaxWidth()
                    )
                    val leadOptions = listOf(0, 15, 30, 45, 60, 90, 120)
                    SelectField(
                        "Reminder before it starts",
                        if (prefs.trainingLeadMinutes <= 0) "Off" else "${prefs.trainingLeadMinutes} min before",
                        leadOptions.map { if (it == 0) "Off" else "$it min before" },
                        { picked -> vm.setTrainingReminders(prefs.trainingEveningMinutes, picked.substringBefore(' ').toIntOrNull() ?: 0) },
                        Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { vm.sendTestNotification(com.classvibe.app.ui.AppViewModel.TestKind.TRAINING) }) { Text("🔔 Send a test training reminder") }
                    OutlinedButton(onClick = { confirmReset = true }) { Text("↺ Restore the default training dates") }
                }
            }
            if (confirmReset) ConfirmDialog(
                "Restore default dates?",
                "Your training days (and their ✅/❌ marks) are replaced by the default ${com.classvibe.app.data.DefaultTraining.DATES.size}-day calendar.",
                "Restore", vm::resetTrainingDefaults, { confirmReset = false }
            )
        }

        // ------------------------------------------------ Appearance
        item {
            SectionCard("🎨 Appearance", prefs.themeMode.label) {
                listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.setTheme(mode) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = prefs.themeMode == mode, onClick = { vm.setTheme(mode) })
                        Text(
                            when (mode) { ThemeMode.LIGHT -> "☀️ Light"; ThemeMode.DARK -> "🌙 Dark"; else -> "📱 Follow phone setting" },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                HorizontalDivider()
                // Live preview while sliding; saved when you let go.
                var dock by remember(prefs.dockTransparency) { mutableStateOf(prefs.dockTransparency.toFloat()) }
                Text("Dock transparency: ${dock.toInt()}%", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "0% = solid • 85% = very see-through glass",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Slider(
                    value = dock, onValueChange = { dock = it }, valueRange = 0f..85f, steps = 16,
                    onValueChangeFinished = { vm.setDockTransparency(dock.toInt()) }
                )
                com.classvibe.app.ui.components.DockPreview(dock.toInt())
            }
        }

        // ------------------------------------------------ Permissions
        item {
            SectionCard("🛡️ Permissions", if (notificationsEnabled && exactAlarmsAllowed) "All good ✅" else "Action needed ⚠️") {
                PermissionRow("Notifications", notificationsEnabled, onEnableNotifications)
                PermissionRow("Exact alarms (on-time reminders)", exactAlarmsAllowed, onAllowExactAlarms)
                Text(
                    "Reminders missing on Xiaomi / Oppo / Vivo / Realme / OnePlus / Samsung? Set ClassVibe's battery usage to \"Unrestricted\".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open app settings (battery)") }
            }
        }

        // ------------------------------------------------ Backup
        item {
            SectionCard("💾 Backup & sharing", "Everything stays on your phone unless you export it") {
                Button(onClick = onOpenQr, modifier = Modifier.fillMaxWidth()) { Text("🔳 QR sharing (share / scan)") }
                OutlinedButton(onClick = { showCalendar = true }, modifier = Modifier.fillMaxWidth()) { Text("📆 Export to calendar (.ics)") }
                HorizontalDivider()
                Button(onClick = { backupSaver.launch("classvibe_backup_${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("⬆️ Export full backup (JSON)") }
                OutlinedButton(onClick = { backupPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("⬇️ Restore from backup") }
                OutlinedButton(onClick = { csvSaver.launch("classvibe_timetable.csv") }, modifier = Modifier.fillMaxWidth()) { Text("📄 Export timetable CSV") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "My ClassVibe timetable")
                        .putExtra(Intent.EXTRA_TEXT, vm.timetableCsv())
                    context.startActivity(Intent.createChooser(send, "Share timetable").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) { Text("📤 Share timetable") }
                HorizontalDivider()
                SwitchRow(
                    "Phone cloud backup",
                    "Let Android back up ClassVibe data to your Google account (restored when you set up a new phone)",
                    cloudBackup, vm::setCloudBackup
                )
            }
        }

        // ------------------------------------------------ Updates & data
        item {
            SectionCard("⬆️ Updates & data", "Version ${BuildConfig.VERSION_NAME}") {
                SwitchRow("Check for updates", "On app open + 3× a day. Only contacts GitHub.", prefs.updateChecks, vm::setUpdateChecks)
                SwitchRow("Beta updates", "Get pre-release versions early", prefs.betaUpdates, vm::setBetaUpdates)
                Button(onClick = { vm.checkForUpdates(manual = true) }) { Text("Check for updates now") }
                updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!UpdateChecker.isConfigured) {
                    Text("ℹ️ This build has no GitHub repo configured (debug build or local build). See GUIDE.md.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear timetable", color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { confirmWipe = true }) { Text("Delete ALL data", color = MaterialTheme.colorScheme.error) }
            }
        }

        // ------------------------------------------------ About
        item {
            SectionCard("ℹ️ About", "ClassVibe ${BuildConfig.VERSION_NAME}") {
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " • debug build" else ""}", style = MaterialTheme.typography.bodyMedium)
                Text("Updates from: github.com/${BuildConfig.GITHUB_REPO}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showWhatsNew = true }) { Text("🎉 What's new") }
                    OutlinedButton(onClick = { showPrivacy = true }) { Text("🔒 Privacy") }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
    }

    if (confirmClear) ConfirmDialog("Clear timetable?", "All classes and their reminders will be removed. Exams and attendance stay.", "Clear", vm::clearTimetable, { confirmClear = false }, destructive = true)
    if (confirmWipe) ConfirmDialog("Delete everything?", "Timetable, exams, attendance, holidays and custom messages will be permanently deleted. Export a backup first!", "Delete all", vm::wipeEverything, { confirmWipe = false }, destructive = true)
    if (showWhatsNew) WhatsNewDialog(BuildConfig.VERSION_NAME, ReleaseNotes.CURRENT) { showWhatsNew = false }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("🔒 Your privacy") },
            text = {
                Text(
                    "• ClassVibe has no accounts, ads, analytics or trackers.\n" +
                        "• Your timetable, exams, attendance and settings are stored only on this phone.\n" +
                        "• Screenshots and photos are read on-device by ML Kit; images are never uploaded.\n" +
                        "• The only network request is the optional update check to api.github.com (no personal data sent). You can switch it off.\n" +
                        "• Files are only shared when you tap Export / Share.\n" +
                        "• No access to contacts, location, SMS, storage or camera (photos go through your camera app).",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Got it") } }
        )
    }
    if (showCalendar) CalendarExportDialog(vm, prefs) { showCalendar = false }
    editSchedule?.let { type -> ExamScheduleDialog(type, prefs, onSave = { vm.setExamSchedule(type.name, it) }) { editSchedule = null } }
}

private fun scheduleSummary(type: ExamType, prefs: AppPrefs): String {
    val s = com.classvibe.app.notifications.ExamPlanner.scheduleFor(type, prefs)
    val parts = listOfNotNull(
        if (s.weekly) "weekly" else null,
        s.milestones.takeIf { it.isNotEmpty() }?.joinToString("/", postfix = " days before"),
        if (s.dailyFinal > 0) "daily in last ${s.dailyFinal}" else null
    )
    return (parts.joinToString(" • ").ifEmpty { "no countdown" }) + " " + com.classvibe.app.notifications.ExamPlanner.fixedExtras(type) +
        if (prefs.examSchedules.containsKey(type.name)) " (custom)" else ""
}

@Composable
private fun ExamScheduleDialog(type: ExamType, prefs: AppPrefs, onSave: (com.classvibe.app.data.ExamSchedule?) -> Unit, onDismiss: () -> Unit) {
    val current = com.classvibe.app.notifications.ExamPlanner.scheduleFor(type, prefs)
    var days by remember { mutableStateOf(current.milestones.joinToString(", ")) }
    var daily by remember { mutableStateOf(current.dailyFinal) }
    var weekly by remember { mutableStateOf(current.weekly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${type.emoji} ${type.label} reminders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    days, { days = it }, label = { Text("Days before (comma-separated)") },
                    placeholder = { Text("90, 60, 30, 15, 7, 3, 1") }, modifier = Modifier.fillMaxWidth()
                )
                SelectField(
                    "Daily reminders in the final…", if (daily == 0) "Off" else "$daily days",
                    listOf("Off") + (1..14).map { "$it days" },
                    { picked -> daily = picked.substringBefore(' ').toIntOrNull() ?: 0 }, Modifier.fillMaxWidth()
                )
                SwitchRow("Weekly reminder", "Every week from the day you add the exam", weekly, { weekly = it })
                Text(
                    "Always included: " + com.classvibe.app.notifications.ExamPlanner.fixedExtras(type).removePrefix("+ "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(com.classvibe.app.data.ExamSchedule(com.classvibe.app.data.ExamSchedule.parseDays(days), daily, weekly))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null); onDismiss() }) { Text("Reset to default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun CalendarExportDialog(vm: AppViewModel, prefs: AppPrefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var opts by remember { mutableStateOf(com.classvibe.app.data.CalendarExport.Options()) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(vm.buildIcs(opts).toByteArray()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📆 Export to calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow("🗓️ Classes", "Weekly events" + if (prefs.semesterEnd != null) " until the semester ends" else " for 26 weeks", opts.classes, { opts = opts.copy(classes = it) })
                SwitchRow("📝 Exams", null, opts.exams, { opts = opts.copy(exams = it) })
                SwitchRow("🏖️ Holidays", null, opts.holidays, { opts = opts.copy(holidays = it) })
                SwitchRow("⏰ Add calendar reminders", "10 min before classes • 1 day and 1 hour before exams", opts.reminders, { opts = opts.copy(reminders = it) })
                Text(
                    "Samsung Calendar, Outlook and most calendar apps open the file directly. Google Calendar: save the file, then import it at calendar.google.com → Settings → Import.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { ShareFiles.openOrShare(context, "classvibe.ics", "text/calendar", vm.buildIcs(opts)); onDismiss() }) { Text("Open / share") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { saver.launch("classvibe.ics") }) { Text("Save file") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun PermissionRow(name: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️")
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Fix") }
    }
}

@Composable
private fun HolidayAdder(onAdd: (LocalDate, LocalDate, String) -> Unit) {
    var start by remember { mutableStateOf<LocalDate?>(null) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    var label by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(label, { label = it }, label = { Text("Name (e.g. Sankranti break)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("From", start, onPick = { start = it; if (end == null) end = it }, modifier = Modifier.weight(1f))
            DateField("To", end, onPick = { end = it }, minDate = start, modifier = Modifier.weight(1f))
        }
        Button(enabled = start != null, onClick = {
            val s = start ?: return@Button
            onAdd(s, end ?: s, label.ifBlank { "Holiday" })
            start = null; end = null; label = ""
        }) { Text("Add holiday") }
    }
}

@Composable
private fun CustomMessageEditor(messages: List<CustomMessage>, onAdd: (String, String) -> Unit, onDelete: (CustomMessage) -> Unit) {
    val categories = listOf("CLASS" to "Class reminders", "EXAM" to "Exam reminders", "WEEKEND" to "Weekend prep", "SUMMARY" to "Morning summary")
    var category by remember { mutableStateOf("CLASS") }
    var text by remember { mutableStateOf("") }
    Text("Your own messages", style = MaterialTheme.typography.labelLarge)
    Text(
        "Placeholders: {course} {room} {instructor} {start_time} {when} {class_type} • exams: {exam} {countdown} {date}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SelectField("Used for", categories.first { it.first == category }.second, categories.map { it.second },
        onSelect = { picked -> category = categories.first { it.second == picked }.first }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(text, { text = it }, label = { Text("e.g. 🏃 {course} in {when}, Room {room}!") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = text.isNotBlank(), onClick = { onAdd(category, text); text = "" }) { Text("Add message") }
    messages.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[${m.category.lowercase()}] ${m.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { onDelete(m) }) { Icon(Icons.Default.Delete, "Delete message", tint = Cv.colors.danger) }
        }
    }
}
