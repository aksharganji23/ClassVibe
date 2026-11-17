package com.classvibe.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.classvibe.app.BuildConfig
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.AppSettings
import com.classvibe.app.data.AttendanceRecord
import com.classvibe.app.data.AttendanceStatus
import com.classvibe.app.data.BackupManager
import com.classvibe.app.data.ClassCalendar
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.ClassVibeRepository
import com.classvibe.app.data.CustomMessage
import com.classvibe.app.data.Exam
import com.classvibe.app.data.Holiday
import com.classvibe.app.data.MessageStyle
import com.classvibe.app.data.ThemeMode
import com.classvibe.app.messages.MessageBank
import com.classvibe.app.notifications.NotificationHelper
import com.classvibe.app.notifications.ReminderContent
import com.classvibe.app.notifications.ReminderScheduler
import com.classvibe.app.parser.ClassDraft
import com.classvibe.app.parser.DayParser
import com.classvibe.app.parser.ExamCsvParser
import com.classvibe.app.update.UpdateChecker
import com.classvibe.app.update.UpdateInfo
import com.classvibe.app.update.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/** What to put in a share QR. Empty [days] = all days. examFilter = ALL, UPCOMING or an ExamType name. */
data class ShareSelection(
    val classes: Boolean = true,
    val days: Set<Int> = emptySet(),
    val exams: Boolean = true,
    val examFilter: String = "UPCOMING",
    val holidays: Boolean = true,
    val semester: Boolean = true,
    val subjects: Boolean = true,
    val attendance: Boolean = false
)

/** State of the QR scanner + merge preview. */
data class ScanUi(
    val message: String? = null,
    val progress: Pair<Int, Int>? = null,
    val payload: com.classvibe.app.data.SharePayload? = null,
    val plan: com.classvibe.app.data.MergePlan? = null,
    val busy: Boolean = false
)

/** Shared state + actions for Today, Timetable, Exams, Stats and Settings. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    /** Fire-and-forget coroutine that returns Unit (so actions can be passed as function references). */
    private fun io(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch(context, block = block)
    }


    private val repo = ClassVibeRepository(app)
    private val context: Context get() = getApplication()

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val classes: StateFlow<List<ClassSchedule>> = repo.observeClasses().state(emptyList())
    val exams: StateFlow<List<Exam>> = repo.observeExams().state(emptyList())
    val attendance: StateFlow<List<AttendanceRecord>> = repo.observeAttendance().state(emptyList())
    val holidays: StateFlow<List<Holiday>> = repo.observeHolidays().state(emptyList())
    val customMessages: StateFlow<List<CustomMessage>> = repo.observeCustomMessages().state(emptyList())

    /** null until DataStore has loaded (so we never flash the wrong theme / onboarding). */
    val prefs: StateFlow<AppPrefs?> = AppSettings.flow(app).state<AppPrefs?>(null)

    val availableUpdate: StateFlow<UpdateInfo?> = AppSettings.rawFlow(app)
        .map { raw ->
            // Hide (and forget) an update that is already installed – e.g. right after updating.
            UpdateInfo.fromJson(raw[AppSettings.Keys.AVAILABLE_UPDATE])?.takeIf { UpdateChecker.isNewer(it.tag) }
                ?: run {
                    if (!raw[AppSettings.Keys.AVAILABLE_UPDATE].isNullOrEmpty()) {
                        viewModelScope.launch { AppSettings.update(app) { it[AppSettings.Keys.AVAILABLE_UPDATE] = "" } }
                    }
                    null
                }
        }
        .state<UpdateInfo?>(null)

    /** Ticks every 30 s so status badges and progress bars stay live. */
    val now: StateFlow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }.state(LocalDateTime.now())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _recentMessages = MutableStateFlow<List<MessageBank.Logged>>(emptyList())
    val recentMessages: StateFlow<List<MessageBank.Logged>> = _recentMessages.asStateFlow()

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    val messageCount: Int get() = MessageBank.count(context)

    private fun toast(text: String) { _messages.tryEmit(text) }

    private fun resync() = io(Dispatchers.IO) { ReminderScheduler.rescheduleAll(context) }

    // =============================================================== settings

    fun setTheme(mode: ThemeMode) = io { AppSettings.setTheme(context, mode) }

    fun toggleLead(lead: Int) = io {
        val current = AppSettings.get(context).leadMinutes
        val updated = if (lead in current) current - lead else current + lead
        AppSettings.setLeads(context, updated)
        resync()
    }

    private fun setBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean, reschedule: Boolean = true) =
        io {
            AppSettings.update(context) { it[key] = value }
            if (reschedule) resync()
        }

    private fun setInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int, reschedule: Boolean = true) =
        io {
            AppSettings.update(context) { it[key] = value }
            if (reschedule) resync()
        }

    fun setRemindersEnabled(v: Boolean) = setBool(AppSettings.Keys.REMINDERS, v)
    fun setClassEndNudge(v: Boolean) = setBool(AppSettings.Keys.CLASS_END, v)
    fun setMorningSummary(v: Boolean) = setBool(AppSettings.Keys.MORNING, v)
    fun setMorningTime(min: Int) = setInt(AppSettings.Keys.MORNING_TIME, min)
    fun setExamReminders(v: Boolean) = setBool(AppSettings.Keys.EXAMS, v)
    fun setExamCountdownTime(min: Int) = setInt(AppSettings.Keys.EXAM_COUNTDOWN_TIME, min)
    fun setExamEveningTime(min: Int) = setInt(AppSettings.Keys.EXAM_EVENING_TIME, min)
    fun setWeekendPrep(v: Boolean) = setBool(AppSettings.Keys.WEEKEND, v)
    fun setWeekendMorning(min: Int) = setInt(AppSettings.Keys.WEEKEND_MORNING, min)
    fun setWeekendEvening(min: Int) = setInt(AppSettings.Keys.WEEKEND_EVENING, min)
    fun setWeekendLookahead(weeks: Int) = setInt(AppSettings.Keys.WEEKEND_LOOKAHEAD, weeks.coerceIn(1, 2))
    fun setWeekendNoExamMessage(v: Boolean) = setBool(AppSettings.Keys.WEEKEND_NO_EXAM, v, reschedule = false)
    fun setBetaUpdates(v: Boolean) = setBool(AppSettings.Keys.BETA, v, reschedule = false)

    fun setUpdateChecks(v: Boolean) = io {
        AppSettings.update(context) { it[AppSettings.Keys.UPDATES] = v }
        if (v) UpdateWorker.schedule(context) else UpdateWorker.cancel(context)
    }

    fun setStyle(style: MessageStyle) = io {
        AppSettings.update(context) { it[AppSettings.Keys.STYLE] = style.name }
    }

    fun toggleExamType(typeName: String) = io {
        AppSettings.update(context) { p ->
            val cur = p[AppSettings.Keys.EXAM_DISABLED_TYPES] ?: emptySet()
            p[AppSettings.Keys.EXAM_DISABLED_TYPES] = if (typeName in cur) cur - typeName else cur + typeName
        }
        resync()
    }

    fun toggleWeekendDay(day: Int) = io {
        AppSettings.update(context) { p ->
            val cur = p[AppSettings.Keys.WEEKEND_DAYS] ?: setOf("6", "7")
            val key = day.toString()
            p[AppSettings.Keys.WEEKEND_DAYS] = if (key in cur) cur - key else cur + key
        }
        resync()
    }

    fun setSemesterStart(date: LocalDate?) = io {
        AppSettings.update(context) { p ->
            val day = date?.toEpochDay()
            if (day == null) p.remove(AppSettings.Keys.SEM_START)
            if (day != null) p[AppSettings.Keys.SEM_START] = day
        }
        resync()
    }

    fun setSemesterEnd(date: LocalDate?) = io {
        AppSettings.update(context) { p ->
            val day = date?.toEpochDay()
            if (day == null) p.remove(AppSettings.Keys.SEM_END)
            if (day != null) p[AppSettings.Keys.SEM_END] = day
        }
        resync()
    }

    fun markWhatsNewSeen() = io {
        AppSettings.update(context) { it[AppSettings.Keys.LAST_SEEN_VERSION] = BuildConfig.VERSION_CODE }
    }

    // =============================================================== timetable

    fun saveEdit(draft: ClassDraft) = io {
        val entity = draft.toEntityOrNull()
        if (entity == null) { toast("Please fix the highlighted fields ✏️"); return@io }
        repo.updateClass(entity)
        toast("Updated ${entity.course} ✅")
    }

    fun deleteClass(c: ClassSchedule) = io {
        repo.deleteClass(c)
        toast("Deleted ${c.course} 🗑️")
    }

    fun clearTimetable() = io { repo.clearTimetable(); toast("Timetable cleared") }

    /** Stats → ✏️ Edit: attendance from before using the app. held = 0 removes it. */
    fun setAttendanceBaseline(course: String, attended: Int, held: Int) = io {
        val b = if (held <= 0) null else com.classvibe.app.data.AttendanceBaseline(course, attended.coerceIn(0, held), held)
        AppSettings.setAttendanceBaseline(context, course, b)
        toast(if (b == null) "Starting numbers removed" else "Saved: ${b.attended}/${b.held} before ClassVibe 👍")
    }

    /** Fix a single mark: change ✅ ↔ ❌ (or 🚫), or remove it (status = null). */
    fun fixMark(record: com.classvibe.app.data.AttendanceRecord, status: AttendanceStatus?) = io {
        val dao = com.classvibe.app.data.AppDatabase.get(context).attendanceDao()
        if (status == null) dao.deleteById(record.id) else dao.upsert(record.copy(status = status.name))
    }

    fun mark(c: ClassSchedule, date: LocalDate, status: AttendanceStatus?) = io {
        repo.mark(c, date, status)
        when (status) {
            AttendanceStatus.CANCELLED -> toast("Marked as cancelled – no reminders for it today 🚫")
            AttendanceStatus.ATTENDED -> toast("Nice! Attendance marked ✅")
            AttendanceStatus.MISSED -> toast("Marked as missed. Next time! 💪")
            null -> toast("Mark cleared")
        }
    }

    // =============================================================== exams

    fun saveExam(exam: Exam, quiet: Boolean = false) = io {
        repo.saveExam(exam)
        AppSettings.addCustomSubjectIfNew(context, exam.course, classes.value.map { it.course })
        if (!quiet) toast("${exam.examType.emoji} ${exam.typeLabel} saved – reminders are set")
    }

    fun deleteExam(exam: Exam) = io { repo.deleteExam(exam); toast("Exam removed") }

    fun importExamsCsv(text: String) = io {
        val result = ExamCsvParser.parse(text)
        if (result.exams.isEmpty()) { toast(result.warnings.firstOrNull() ?: "No exams found in that file"); return@io }
        repo.saveExams(result.exams)
        toast("Imported ${result.exams.size} exams" + if (result.warnings.isNotEmpty()) " (${result.warnings.size} rows skipped)" else "")
    }

    fun addCustomSubject(subject: String) = io { AppSettings.addCustomSubject(context, subject) }

    // =============================================================== holidays

    fun addHoliday(start: LocalDate, end: LocalDate, label: String) = io {
        repo.addHoliday(start, end, label)
        toast("🏖️ Holiday added – class reminders paused")
    }

    fun pauseToday() = addHoliday(LocalDate.now(), LocalDate.now(), "Paused today")

    fun deleteHoliday(h: Holiday) = io { repo.deleteHoliday(h) }

    // =============================================================== messages

    fun refreshRecentMessages() = io { _recentMessages.value = MessageBank.recent(context) }

    fun rateMessage(id: String, rating: Int) = io {
        MessageBank.rate(context, id, rating)
        _recentMessages.value = MessageBank.recent(context)
    }

    fun addCustomMessage(category: String, text: String) = io {
        repo.addCustomMessage(category, text)
        toast("Added to your message mix 😄")
    }

    fun deleteCustomMessage(m: CustomMessage) = io { repo.deleteCustomMessage(m) }

    fun sendTestNotification() = io {
        if (!NotificationHelper.canPostNotifications(context)) { toast("Notifications are off – allow them in Settings 🔕"); return@io }
        val p = AppSettings.get(context)
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute
        val all = classes.value
        val target = all.firstOrNull { it.dayOfWeek == today.dayOfWeek.value && nowMin in it.startMinutes until it.endMinutes }
            ?: all.filter { it.dayOfWeek == today.dayOfWeek.value && it.startMinutes > nowMin }.minByOrNull { it.startMinutes }
            ?: all.minByOrNull { c ->
                val ahead = (c.dayOfWeek - today.dayOfWeek.value + 7) % 7
                (if (ahead == 0) 7 else ahead) * 1440 + c.startMinutes
            }
            ?: ClassSchedule(
                id = 9_998, dayOfWeek = today.dayOfWeek.value, startMinutes = 9 * 60 + 30, endMinutes = 10 * 60 + 30,
                course = "Artificial Intelligence and Deep Learning", classType = "Lecture",
                room = "1205-BL1-SF", instructor = "Ms. Jyoshmadevi Kusumba"
            )
        val lead = p.leadMinutes.filter { it > 0 }.minOrNull() ?: 10
        val todays = all.filter { it.dayOfWeek == today.dayOfWeek.value }.sortedBy { it.startMinutes }
        // Minutes until this class's next start (0 if it's on right now).
        val ahead = (target.dayOfWeek - today.dayOfWeek.value + 7) % 7
        val minutesLeft = when {
            ahead == 0 && nowMin in target.startMinutes until target.endMinutes -> 0
            ahead == 0 && target.startMinutes > nowMin -> target.startMinutes - nowMin
            else -> (if (ahead == 0) 7 else ahead) * 1440 + target.startMinutes - nowMin
        }
        val built = ReminderContent.classReminder(context, target, lead, today, p, todays, attendance.value, minutesLeft)
        val startAt = System.currentTimeMillis() + minutesLeft * 60_000L
        NotificationHelper.post(
            context, NotificationHelper.nextTestId(), NotificationHelper.CH_CLASSES,
            "🧪 Test • " + built.title, built.body, built.bigText,
            subText = if (minutesLeft > 0) "${target.classType} • ${target.startTimeText} • in ${ReminderContent.leftText(minutesLeft)}" else "${target.classType} • now",
            actions = com.classvibe.app.notifications.RateActionReceiver.actions(context, built.messageId),
            countdownTo = if (minutesLeft in 1..(24 * 60)) startAt else null,
            group = NotificationHelper.GROUP_CLASSES
        )
        toast("Test reminder sent for ${target.course} (${target.dayName} ${target.startTimeText}) 🔔")
    }

    // =============================================================== backup

    suspend fun exportBackup(): String = withContext(Dispatchers.IO) { BackupManager.export(context) }

    fun importBackup(json: String) = io {
        try {
            val summary = withContext(Dispatchers.IO) { BackupManager.import(context, json) }
            toast("✅ $summary")
        } catch (e: Exception) {
            toast("Couldn't restore: ${e.message}")
        }
    }

    /** Timetable as CSV (for sharing or QR). */
    fun timetableCsv(): String = buildString {
        append("Day,Start Time,End Time,Course,Class Type,Room,Instructor,Week\n")
        classes.value.forEach { c ->
            fun q(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
            append(listOf(
                DayParser.name(c.dayOfWeek), c.startTimeText, c.endTimeText, q(c.course), q(c.classType),
                q(c.room), q(c.instructor), ClassDraft.PARITY_LABELS[c.weekParity.coerceIn(0, 2)].substringBefore(' ')
            ).joinToString(",")).append('\n')
        }
    }

    fun wipeEverything() = io { repo.wipeAll(); toast("All data deleted") }

    // =============================================================== QR sharing

    fun sharePayload(sel: ShareSelection): com.classvibe.app.data.SharePayload {
        val p = prefs.value ?: AppPrefs()
        val today = LocalDate.now()
        val examList = if (!sel.exams) emptyList() else exams.value.filter { e ->
            when (sel.examFilter) {
                "ALL" -> true
                "UPCOMING" -> !e.date.isBefore(today)
                else -> e.type == sel.examFilter && !e.date.isBefore(today)
            }
        }
        val classList = if (!sel.classes) emptyList() else classes.value.filter { sel.days.isEmpty() || it.dayOfWeek in sel.days }
        val att = if (!sel.attendance) emptyList() else {
            val byId = classes.value.associateBy { it.id }
            attendance.value.mapNotNull { a ->
                val c = byId[a.classId] ?: return@mapNotNull null
                com.classvibe.app.data.SharedAttendance(c.dayOfWeek, c.startMinutes, c.course, a.epochDay, a.status)
            }
        }
        return com.classvibe.app.data.SharePayload(
            classes = classList,
            exams = examList,
            holidays = if (sel.holidays) holidays.value else emptyList(),
            semesterStart = if (sel.semester) p.semesterStartEpochDay else null,
            semesterEnd = if (sel.semester) p.semesterEndEpochDay else null,
            subjects = if (sel.subjects) p.customSubjects.toList() else emptyList(),
            attendance = att
        )
    }

    private val assembler = com.classvibe.app.data.ShareCodec.Assembler()
    private val _scan = MutableStateFlow(ScanUi())
    val scan: StateFlow<ScanUi> = _scan.asStateFlow()

    fun onScanned(raw: String) {
        when (val r = assembler.add(raw)) {
            is com.classvibe.app.data.ShareCodec.Scan.Complete -> {
                val p = prefs.value ?: AppPrefs()
                val plan = com.classvibe.app.data.ShareMerge.plan(classes.value, exams.value, holidays.value, p.customSubjects, p, r.payload)
                _scan.value = ScanUi(message = "✅ Scanned: ${r.payload.summary()}", payload = r.payload, plan = plan)
            }
            is com.classvibe.app.data.ShareCodec.Scan.Partial ->
                _scan.value = ScanUi(message = "📦 Part ${r.have} of ${r.total} scanned – scan the next one", progress = r.have to r.total)
            is com.classvibe.app.data.ShareCodec.Scan.Invalid -> _scan.value = _scan.value.copy(message = "⚠️ ${r.reason}", busy = false)
        }
    }

    fun scanWithCamera(activityContext: Context) = io {
        _scan.value = _scan.value.copy(busy = true, message = null)
        try {
            val raw = com.classvibe.app.qr.QrScanner.scanWithCamera(activityContext)
            if (raw != null) onScanned(raw) else _scan.value = _scan.value.copy(busy = false)
        } catch (e: Exception) {
            _scan.value = _scan.value.copy(busy = false, message = "⚠️ Camera scanner unavailable (needs Google Play services). Use \"Scan from image\" instead.")
        }
    }

    fun scanImage(uri: android.net.Uri) = io {
        _scan.value = _scan.value.copy(busy = true, message = null)
        try {
            val values = withContext(Dispatchers.IO) { com.classvibe.app.qr.QrScanner.scanImage(context, uri) }
            if (values.isEmpty()) _scan.value = _scan.value.copy(busy = false, message = "⚠️ No QR code found in that image.")
            values.forEach { onScanned(it) }
        } catch (e: Exception) {
            _scan.value = _scan.value.copy(busy = false, message = "⚠️ Couldn't read the image: ${e.message}")
        }
    }

    fun resetScan() { assembler.reset(); _scan.value = ScanUi() }

    fun applyScan(clashesToAdd: Set<Int>, includeAttendance: Boolean) = io {
        val plan = _scan.value.plan ?: return@io
        val extra = plan.clashes.filterIndexed { i, _ -> i in clashesToAdd }.map { it.first }
        repo.applyMerge(plan, extra, includeAttendance, LocalDate.now())
        resetScan()
        toast("✅ Added to your ClassVibe: ${plan.summary().removePrefix("Adds ")}")
    }

    // =============================================================== calendar export

    fun buildIcs(options: com.classvibe.app.data.CalendarExport.Options): String =
        com.classvibe.app.data.CalendarExport.build(classes.value, exams.value, holidays.value, prefs.value ?: AppPrefs(), options)

    // =============================================================== cloud backup + exam schedules

    private val _cloudBackup = MutableStateFlow(com.classvibe.app.backup.ClassVibeBackupAgent.isEnabled(app))
    val cloudBackup: StateFlow<Boolean> = _cloudBackup.asStateFlow()

    fun setCloudBackup(v: Boolean) {
        com.classvibe.app.backup.ClassVibeBackupAgent.setEnabled(context, v)
        _cloudBackup.value = v
    }

    fun setExamSchedule(typeName: String, schedule: com.classvibe.app.data.ExamSchedule?) = io {
        AppSettings.setExamSchedule(context, typeName, schedule)
        resync()
        toast(if (schedule == null) "Reset to the default schedule" else "Exam schedule updated 📝")
    }

    // =============================================================== updates

    fun checkForUpdates(manual: Boolean) = io {
        val p = AppSettings.get(context)
        if (!manual) {
            if (!p.updateChecks) return@io
            val last = AppSettings.raw(context)[AppSettings.Keys.LAST_UPDATE_CHECK] ?: 0L
            if (System.currentTimeMillis() - last < 10 * 60 * 1000) return@io // at most every 10 min on app open
        }
        if (manual) _updateStatus.value = "Checking…"
        val result = UpdateChecker.check(context, p.betaUpdates)
        if (manual) {
            _updateStatus.value = when (result) {
                is UpdateChecker.Result.Available -> "🚀 ${result.info.tag} is available!"
                UpdateChecker.Result.UpToDate -> "✅ You're on the latest version (${UpdateChecker.currentVersion})"
                is UpdateChecker.Result.Failed -> "⚠️ Couldn't check: ${result.reason}"
                UpdateChecker.Result.NotConfigured -> "ℹ️ Updates aren't configured in this build (see GUIDE.md)"
            }
        }
    }

    /** Download progress of an update: null = idle, 0..100 = downloading, 101 = verifying & installing. */
    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()
    private var pendingUpdate: UpdateInfo? = null

    /** Tap on "Update": download → verify → install (one tap after the first-time permission). */
    fun downloadUpdate(info: UpdateInfo) = io {
        if (!com.classvibe.app.update.UpdateInstaller.canInstall(context)) {
            pendingUpdate = info
            toast("Allow ClassVibe to install updates, then come back – the update starts by itself 👍")
            try { com.classvibe.app.update.UpdateInstaller.openInstallPermission(context) } catch (e: Exception) { }
            return@io
        }
        try {
            val id = UpdateChecker.download(context, info)
            toast("⬇️ Downloading ${info.apkName}…")
            trackDownload(id)
        } catch (e: Exception) {
            _downloadProgress.value = null
            toast("Download failed: ${e.message}")
        }
    }

    /** Called when the app comes back to the foreground (e.g. from the permission screen). */
    fun resumePendingUpdate() {
        val info = pendingUpdate ?: return
        if (com.classvibe.app.update.UpdateInstaller.canInstall(context)) {
            pendingUpdate = null
            downloadUpdate(info)
        }
    }

    private suspend fun trackDownload(id: Long) {
        val dm = context.getSystemService(android.app.DownloadManager::class.java)
        _downloadProgress.value = 0
        while (true) {
            kotlinx.coroutines.delay(500)
            val cursor = dm.query(android.app.DownloadManager.Query().setFilterById(id)) ?: break
            val row: Triple<Int, Long, Long>? = cursor.use {
                if (!it.moveToFirst()) null
                else Triple(
                    it.getInt(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)),
                    it.getLong(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    it.getLong(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }
            if (row == null) break
            val (status, done, total) = row
            when (status) {
                android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                    _downloadProgress.value = 101
                    kotlinx.coroutines.delay(15_000)
                    _downloadProgress.value = null
                    return
                }
                android.app.DownloadManager.STATUS_FAILED -> {
                    _downloadProgress.value = null
                    toast("Download failed – check your internet and try again")
                    return
                }
                else -> if (total > 0) _downloadProgress.value = ((done * 100) / total).toInt().coerceIn(0, 100)
            }
        }
        _downloadProgress.value = null
    }

    fun skipUpdate(info: UpdateInfo) = io {
        AppSettings.update(context) { it[AppSettings.Keys.SKIPPED] = info.tag }
    }

    fun openDownloads() = UpdateChecker.openDownloads(context)

    // =============================================================== helpers for UI

    fun isClassActiveToday(c: ClassSchedule, date: LocalDate, p: AppPrefs): Boolean =
        ClassCalendar.isScheduled(c, date, p)
}
