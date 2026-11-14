package com.classvibe.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.classvibe.app.ui.screens.OnboardingScreen
import com.classvibe.app.ui.screens.SettingsScreen
import com.classvibe.app.ui.screens.StatsScreen
import com.classvibe.app.ui.screens.TimetableScreen
import com.classvibe.app.ui.theme.ClassVibeTheme
import com.classvibe.app.ui.theme.Cv
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val requestedTab = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab.intValue = intent?.getIntExtra(EXTRA_TAB, -1) ?: -1
        setContent { AppRoot(requestedTab) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.intValue = intent.getIntExtra(EXTRA_TAB, -1)
    }

    companion object {
        const val EXTRA_TAB = "open_tab"
    }
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    TODAY("Today", Icons.Default.Home),
    TIMETABLE("Timetable", Icons.Default.DateRange),
    EXAMS("Exams", Icons.Default.Edit),
    STATS("Stats", Icons.Default.Star),
    IMPORT("Import", Icons.Default.Add),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
private fun AppRoot(requestedTab: MutableIntState) {
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
            MainScaffold(vm, p, requestedTab)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScaffold(vm: AppViewModel, prefs: AppPrefs, requestedTab: MutableIntState) {
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

    val tabs = AppTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    var prefillExamCourse by remember { mutableStateOf<String?>(null) }
    var showWhatsNew by remember { mutableStateOf(prefs.lastSeenVersionCode < BuildConfig.VERSION_CODE) }

    fun goTo(tab: AppTab) { scope.launch { pagerState.animateScrollToPage(tab.ordinal) } }

    // Notification taps can ask for a specific tab.
    LaunchedEffect(requestedTab.intValue) {
        val t = requestedTab.intValue
        if (t in tabs.indices) pagerState.scrollToPage(t)
        requestedTab.intValue = -1
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
                    title = {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = brand, fontWeight = FontWeight.Black)) { append("ClassVibe") }
                                withStyle(SpanStyle(color = Cv.colors.accent)) { append(" ⚡") }
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    actions = {
                        IconButton(onClick = { vm.sendTestNotification() }) {
                            Icon(Icons.Default.Notifications, contentDescription = "Send a test reminder")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = brand,
                    indicator = { positions ->
                        if (positions.isNotEmpty()) {
                            // Underline follows the finger while swiping between pages.
                            val current = pagerState.currentPage.coerceIn(0, positions.lastIndex)
                            val fraction = pagerState.currentPageOffsetFraction
                            val target = (current + if (fraction > 0f) 1 else -1).coerceIn(0, positions.lastIndex)
                            val f = abs(fraction)
                            val left = lerp(positions[current].left, positions[target].left, f)
                            val width = lerp(positions[current].width, positions[target].width, f)
                            Box(
                                Modifier.fillMaxWidth().wrapContentSize(Alignment.BottomStart)
                                    .offset(x = left).width(width).height(3.dp)
                                    .background(brand, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = { Text(t.title) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            selectedContentColor = brand,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = importState.stage != ImportStage.CROP,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            when (tabs[page]) {
                AppTab.TODAY -> HomeScreen(
                    classes = classes, exams = exams, attendance = attendance, holidays = holidays, prefs = prefs, now = now,
                    update = update,
                    notificationsEnabled = notificationsOk, exactAlarmsAllowed = exactOk,
                    onEnableNotifications = openNotificationSettings, onAllowExactAlarms = openExactAlarmSettings,
                    onGoToImport = { goTo(AppTab.IMPORT) }, onGoToExams = { goTo(AppTab.EXAMS) },
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
                    onGoToImport = { goTo(AppTab.IMPORT) }
                )
                AppTab.EXAMS -> ExamsScreen(
                    exams = exams, classes = classes, prefs = prefs, today = now.toLocalDate(),
                    prefillCourse = prefillExamCourse,
                    onPrefillConsumed = { prefillExamCourse = null },
                    onSave = { e, quiet -> vm.saveExam(e, quiet) },
                    onDelete = { vm.deleteExam(it) },
                    onImportCsv = { vm.importExamsCsv(it) }
                )
                AppTab.STATS -> StatsScreen(classes = classes, attendance = attendance, today = now.toLocalDate())
                AppTab.IMPORT -> ImportScreen(
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
                    onSave = { importVm.save { goTo(AppTab.TODAY) } }
                )
                AppTab.SETTINGS -> SettingsScreen(
                    vm = vm, prefs = prefs, holidays = holidays, customMessages = customMessages,
                    recentMessages = recentMessages, updateStatus = updateStatus,
                    notificationsEnabled = notificationsOk, exactAlarmsAllowed = exactOk,
                    onEnableNotifications = openNotificationSettings, onAllowExactAlarms = openExactAlarmSettings
                )
            }
        }
    }

    if (showWhatsNew) {
        WhatsNewDialog(BuildConfig.VERSION_NAME, ReleaseNotes.CURRENT) {
            showWhatsNew = false
            vm.markWhatsNewSeen()
        }
    }
}
