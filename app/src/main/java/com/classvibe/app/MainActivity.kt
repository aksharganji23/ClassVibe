package com.classvibe.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classvibe.app.data.AppPrefs
import com.classvibe.app.data.ThemeMode
import com.classvibe.app.notifications.NotificationHelper
import com.classvibe.app.notifications.ReminderScheduler
import com.classvibe.app.ui.AppViewModel
import com.classvibe.app.ui.ImportStage
import com.classvibe.app.ui.ImportViewModel
import com.classvibe.app.ui.ReleaseNotes
import com.classvibe.app.ui.components.WhatsNewDialog
import com.classvibe.app.ui.screens.ExamsScreen
import com.classvibe.app.ui.screens.HomeScreen
import com.classvibe.app.ui.screens.ImportScreen
import com.classvibe.app.ui.screens.QrShareScreen
import com.classvibe.app.ui.screens.OnboardingScreen
import com.classvibe.app.ui.screens.SettingsScreen
import com.classvibe.app.ui.screens.StatsScreen
import com.classvibe.app.ui.screens.TimetableScreen
import com.classvibe.app.ui.theme.ClassVibeTheme
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.util.AppShortcuts
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.classvibe.app.ui.components.DOCK_SPACE
import com.classvibe.app.ui.components.DockItem
import com.classvibe.app.ui.components.FloatingDock
import com.classvibe.app.ui.components.LocalDockPadding
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val requestedTab = mutableIntStateOf(-1)
    private val requestedAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        setContent { AppRoot(requestedTab, requestedAction) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        intent ?: return
        requestedTab.intValue = intent.getIntExtra(EXTRA_TAB, -1)
        requestedAction.value = intent.getStringExtra(EXTRA_ACTION)
    }

    companion object {
        const val EXTRA_TAB = "open_tab"
        const val EXTRA_ACTION = "shortcut_action"
    }
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    TODAY("Today", Icons.Default.Home),
    TIMETABLE("Timetable", Icons.Default.DateRange),
    EXAMS("Exams", Icons.Default.Edit),
    STATS("Stats", Icons.Default.Star),
    TRAINING("Training", Icons.Default.Build)
}

/** Full-screen pages opened on top of the tabs (back arrow / back gesture closes them). */
private enum class Overlay(val title: String) { SETTINGS("Settings"), IMPORT("Import timetable"), QR("QR sharing") }

@Composable
private fun AppRoot(requestedTab: MutableIntState, requestedAction: MutableState<String?>) {
    val vm: AppViewModel = viewModel()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val p = prefs
    if (p == null) {
        // DataStore still loading – plain background avoids a theme flash.
        Box(Modifier.fillMaxSize())
        return
    }
    ClassVibeTheme(p.themeMode) {
        if (p.themeMode == ThemeMode.UNSET) {
            OnboardingScreen(onDone = { vm.setTheme(it) })
        } else {
            MainScaffold(vm, p, requestedTab, requestedAction)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScaffold(vm: AppViewModel, prefs: AppPrefs, requestedTab: MutableIntState, requestedAction: MutableState<String?>) {
    val importVm: ImportViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val classes by vm.classes.collectAsStateWithLifecycle()
    val exams by vm.exams.collectAsStateWithLifecycle()
    val attendance by vm.attendance.collectAsStateWithLifecycle()
    val holidays by vm.holidays.collectAsStateWithLifecycle()
    val customMessages by vm.customMessages.collectAsStateWithLifecycle()
    val recentMessages by vm.recentMessages.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val update by vm.availableUpdate.collectAsStateWithLifecycle()
    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
    val importState by importVm.state.collectAsStateWithLifecycle()
    val scanState by vm.scan.collectAsStateWithLifecycle()
    val cloudBackup by vm.cloudBackup.collectAsStateWithLifecycle()
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var showNextClass by remember { mutableStateOf(false) }
    var openBunk by remember { mutableStateOf(false) }
    // Floating dock hides while scrolling down and comes back when scrolling up.
    var dockVisible by remember { mutableStateOf(true) }
    val dockScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // The dock stays put (no hide-on-scroll).
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    BackHandler(enabled = overlay != null) {
        if (overlay == Overlay.IMPORT && importState.stage != ImportStage.PICK) importVm.discard() else overlay = null
    }

    // The Training tab only appears for students who opted in.
    val tabs = if (prefs.trainingEnabled) AppTab.entries else AppTab.entries.filter { it != AppTab.TRAINING }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    LaunchedEffect(pagerState.currentPage, overlay) { dockVisible = true }
    var prefillExamCourse by remember { mutableStateOf<String?>(null) }
    var showWhatsNew by remember { mutableStateOf(prefs.lastSeenVersionCode < BuildConfig.VERSION_CODE) }

    fun goTo(tab: AppTab) { scope.launch { pagerState.animateScrollToPage(tab.ordinal) } }

    // Notification taps can ask for a specific tab.
    LaunchedEffect(requestedTab.intValue) {
        val t = requestedTab.intValue
        if (t in tabs.indices) { overlay = null; pagerState.scrollToPage(t) }
        requestedTab.intValue = -1
    }
    // App-icon shortcuts.
    LaunchedEffect(requestedAction.value) {
        when (requestedAction.value) {
            AppShortcuts.ACTION_NEXT_CLASS -> { overlay = null; pagerState.scrollToPage(AppTab.TODAY.ordinal); showNextClass = true }
            AppShortcuts.ACTION_TODAY -> { overlay = null; pagerState.scrollToPage(AppTab.TODAY.ordinal) }
            AppShortcuts.ACTION_ADD_EXAM -> { overlay = null; prefillExamCourse = ""; pagerState.scrollToPage(AppTab.EXAMS.ordinal) }
            AppShortcuts.ACTION_BUNK -> { overlay = null; openBunk = true; pagerState.scrollToPage(AppTab.STATS.ordinal) }
        }
        requestedAction.value = null
    }

    // --- Permissions (re-checked on every resume) ---
    var notificationsOk by remember { mutableStateOf(NotificationHelper.canPostNotifications(context)) }
    var exactOk by remember { mutableStateOf(ReminderScheduler.canScheduleExact(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOk = NotificationHelper.canPostNotifications(context)
        val nowExact = ReminderScheduler.canScheduleExact(context)
        if (nowExact && !exactOk) ReminderScheduler.enqueueImmediateResync(context)
        exactOk = nowExact
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOk = granted && NotificationHelper.canPostNotifications(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsOk) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.checkForUpdates(manual = false)
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { importVm.messages.collect { snackbar.showSnackbar(it) } }

    val openNotificationSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsOk) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val openExactAlarmSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val brand = Cv.colors.brandText
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (overlay != null) {
                            IconButton(onClick = {
                                if (overlay == Overlay.IMPORT && importState.stage != ImportStage.PICK) importVm.discard() else overlay = null
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        }
                    },
                    title = {
                        val o = overlay
                        if (o != null) {
                            Text(o.title, style = MaterialTheme.typography.titleLarge)
                        } else {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = brand, fontWeight = FontWeight.Black)) { append("ClassVibe") }
                                    withStyle(SpanStyle(color = Cv.colors.accent)) { append(" ⚡") }
                                },
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    },
                    actions = {
                        if (overlay == null) {
                            // Test what you're looking at: Today/Timetable → next class, Exams → next exam,
                            // Stats → class-ending (attendance), Training → next training day.
                            IconButton(onClick = {
                                val kind = when (tabs.getOrNull(pagerState.currentPage)) {
                                    AppTab.EXAMS -> com.classvibe.app.ui.AppViewModel.TestKind.EXAM
                                    AppTab.STATS -> com.classvibe.app.ui.AppViewModel.TestKind.CLASS_END
                                    AppTab.TRAINING -> com.classvibe.app.ui.AppViewModel.TestKind.TRAINING
                                    else -> com.classvibe.app.ui.AppViewModel.TestKind.CLASS
                                }
                                vm.sendTestNotification(kind)
                            }) {
                                Icon(Icons.Default.Notifications, contentDescription = "Send a test notification for this tab")
                            }
                            IconButton(onClick = { overlay = Overlay.SETTINGS }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (overlay) {
                Overlay.SETTINGS -> SettingsScreen(
                    vm = vm, prefs = prefs, holidays = holidays, customMessages = customMessages,
                    recentMessages = recentMessages, updateStatus = updateStatus,
                    notificationsEnabled = notificationsOk, exactAlarmsAllowed = exactOk,
                    onEnableNotifications = openNotificationSettings, onAllowExactAlarms = openExactAlarmSettings,
                    cloudBackup = cloudBackup,
                    onOpenQr = { overlay = Overlay.QR }
                )
                Overlay.QR -> QrShareScreen(vm, scanState, classes)
                Overlay.IMPORT -> ImportScreen(
                    state = importState,
                    knownCourses = classes.map { it.course }.distinct(),
                    knownRooms = classes.map { it.room }.distinct(),
                    knownInstructors = classes.map { it.instructor }.distinct(),
                    onUris = { importVm.importUris(it) },
                    newCameraUri = { importVm.newCameraUri() },
                    onCameraResult = { importVm.onCameraResult(it) },
                    onPasteText = { importVm.importText(it) },
                    onLoadSample = { importVm.loadSample() },
                    onReplacePending = { i, b -> importVm.replacePending(i, b) },
                    onNextCrop = { importVm.nextCrop() },
                    onUpdateDraft = { importVm.updateDraft(it) },
                    onConfirmDraft = { importVm.confirmDraft(it) },
                    onRemoveDraft = { importVm.removeDraft(it) },
                    onAddDraft = { importVm.addEmptyDraft() },
                    onReplaceChange = { importVm.setReplaceExisting(it) },
                    onToggleGrid = { importVm.toggleGrid() },
                    onDiscard = { importVm.discard() },
                    onSave = { importVm.save { overlay = null; goTo(AppTab.TODAY) } }
                )
                null -> Box(Modifier.fillMaxSize().nestedScroll(dockScroll)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalDockPadding provides DOCK_SPACE) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    // Premium page transition: neighbouring pages fade and shrink slightly while swiping.
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val o = abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                            alpha = 1f - 0.35f * o
                            val sc = 1f - 0.06f * o
                            scaleX = sc
                            scaleY = sc
                        }
                    ) {
                    when (tabs[page]) {
                        AppTab.TODAY -> HomeScreen(
                            trainingNext = if (prefs.trainingEnabled) com.classvibe.app.data.DefaultTraining.next(training, now) else null,
                            trainingTotal = training.size,
                            onOpenTraining = { goTo(AppTab.TRAINING) },
                            onOpenDownloads = { vm.openDownloads() },
                            classes = classes, exams = exams, attendance = attendance, holidays = holidays, prefs = prefs, now = now,
                            update = update, downloadProgress = downloadProgress,
                            notificationsEnabled = notificationsOk, exactAlarmsAllowed = exactOk,
                            onEnableNotifications = openNotificationSettings, onAllowExactAlarms = openExactAlarmSettings,
                            onGoToImport = { overlay = Overlay.IMPORT }, onGoToExams = { goTo(AppTab.EXAMS) },
                            onMark = { c, s -> vm.mark(c, now.toLocalDate(), s) },
                            onAddExamFor = { c -> prefillExamCourse = c.course; goTo(AppTab.EXAMS) },
                            onRemoveHoliday = { vm.deleteHoliday(it) },
                            onUpdate = { vm.downloadUpdate(it) },
                            onSkipUpdate = { vm.skipUpdate(it) }
                        )
                        AppTab.TIMETABLE -> TimetableScreen(
                            allClasses = classes, prefs = prefs, now = now,
                            isVisible = pagerState.settledPage == AppTab.TIMETABLE.ordinal,
                            onSaveEdit = { vm.saveEdit(it) },
                            onDelete = { vm.deleteClass(it) },
                            onAddExamFor = { c -> prefillExamCourse = c.course; goTo(AppTab.EXAMS) },
                            onGoToImport = { overlay = Overlay.IMPORT }
                        )
                        AppTab.EXAMS -> ExamsScreen(
                            exams = exams, classes = classes, prefs = prefs, today = now.toLocalDate(),
                            prefillCourse = prefillExamCourse,
                            onPrefillConsumed = { prefillExamCourse = null },
                            onSave = { e, quiet -> vm.saveExam(e, quiet) },
                            onDelete = { vm.deleteExam(it) },
                            onImportCsv = { vm.importExamsCsv(it) }
                        )
                        AppTab.STATS -> StatsScreen(
                            classes = classes, attendance = attendance, holidays = holidays, prefs = prefs,
                            today = now.toLocalDate(), openBunkPlanner = openBunk,
                            onSetBaseline = { c, a, h -> vm.setAttendanceBaseline(c, a, h) },
                            onFixMark = { r, st -> vm.fixMark(r, st) }
                        )
                        AppTab.TRAINING -> com.classvibe.app.ui.screens.TrainingScreen(
                            days = training, now = now,
                            onSave = { vm.saveTrainingDay(it) },
                            onDelete = { vm.deleteTrainingDay(it) },
                            onMark = { d, st -> vm.markTraining(d, st) },
                            onOpenSettings = { overlay = Overlay.SETTINGS }
                        )
                    }
                    }
                    }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = dockVisible,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 300f)) { it * 2 } +
                            androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { it * 2 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
                    ) {
                        FloatingDock(
                            items = tabs.map { DockItem(it.title, it.icon) },
                            position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                            selected = pagerState.targetPage,
                            transparency = prefs.dockTransparency,
                            onSelect = { i ->
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        i, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.88f, stiffness = 380f)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Asked once: is this student in placement training?
    if (!prefs.trainingAsked && !showWhatsNew && overlay == null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("🎯 Placement training?") },
            text = {
                Text(
                    "Are you attending placement training? ClassVibe will add the training calendar " +
                        "(${com.classvibe.app.data.DefaultTraining.DATES.size} days), show a Training tab and remind you the evening before " +
                        "and before each session.\n\nYou can change this any time in ⚙️ Settings."
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.setTrainingEnabled(true) }) { Text("Yes, I'm in training") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.dismissTrainingQuestion() }) { Text("No") } }
        )
    }

    if (showNextClass) NextClassDialog(classes, attendance, holidays, prefs, now) { showNextClass = false }

    if (showWhatsNew) {
        WhatsNewDialog(BuildConfig.VERSION_NAME, ReleaseNotes.CURRENT) {
            showWhatsNew = false
            vm.markWhatsNewSeen()
        }
    }
}

/** "🔔 Next class" app shortcut. */
@Composable
private fun NextClassDialog(
    classes: List<com.classvibe.app.data.ClassSchedule>,
    attendance: List<com.classvibe.app.data.AttendanceRecord>,
    holidays: List<com.classvibe.app.data.Holiday>,
    prefs: AppPrefs,
    now: java.time.LocalDateTime,
    onDismiss: () -> Unit
) {
    val marks = attendance.associateBy { "${it.classId}|${it.epochDay}" }
    val next = (0L..7L).asSequence().flatMap { offset ->
        val date = now.toLocalDate().plusDays(offset)
        classes.filter { c ->
            com.classvibe.app.data.ClassCalendar.isActive(c, date, prefs, holidays, marks["${c.id}|${date.toEpochDay()}"]) &&
                (offset > 0 || c.endMinutes > now.hour * 60 + now.minute)
        }.sortedBy { it.startMinutes }.map { date to it }.asSequence()
    }.firstOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔔 Next class") },
        text = {
            if (next == null) Text("No upcoming classes this week 🎉")
            else {
                val (date, c) = next
                val minutes = now.hour * 60 + now.minute
                val whenText = when {
                    date == now.toLocalDate() && minutes in c.startMinutes until c.endMinutes -> "Happening now – ${c.endMinutes - minutes} min left"
                    date == now.toLocalDate() -> "Today at ${c.startTimeText} (in ${(c.startMinutes - minutes) / 60}h ${(c.startMinutes - minutes) % 60}m)"
                    date == now.toLocalDate().plusDays(1) -> "Tomorrow at ${c.startTimeText}"
                    else -> "${c.dayName} at ${c.startTimeText}"
                }
                Column {
                    Text(c.course, style = MaterialTheme.typography.titleLarge)
                    Text(whenText, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(c.classType, c.room.ifBlank { null }?.let { "📍 $it" }, c.instructor.ifBlank { null }?.let { "👤 $it" })
                            .filterNotNull().joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
