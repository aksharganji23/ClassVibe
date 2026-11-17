package com.classvibe.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.settingsStore by preferencesDataStore(name = "app_settings")

enum class ThemeMode(val label: String) { UNSET("Not chosen"), LIGHT("Light"), DARK("Dark"), SYSTEM("Follow phone setting") }

enum class MessageStyle(val label: String, val description: String) {
    MIX("Classic mix", "Funny, sarcastic and motivational"),
    SAVAGE("Savage", "Maximum roast 🔥"),
    MOTIVATIONAL("Motivational", "Kind, encouraging nudges"),
    TENGLISH("Tenglish", "Telugu + English vibes"),
    HINGLISH("Hinglish", "Hindi + English vibes"),
    PLAIN("Plain", "Just the facts, no jokes")
}

/**
 * User-edited reminder schedule for one exam type.
 * milestones = "days before" reminders, dailyFinal = daily reminders in the last N days, weekly = every week.
 */
/** Classes attended / held before the student started marking in ClassVibe. */
data class AttendanceBaseline(val course: String, val attended: Int, val held: Int) {
    fun encode() = "$attended|$held|$course"

    companion object {
        fun decode(s: String): AttendanceBaseline? {
            val parts = s.split('|', limit = 3)
            if (parts.size != 3) return null
            val a = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            if (h < 0 || a < 0 || a > h || parts[2].isBlank()) return null
            return AttendanceBaseline(parts[2], a, h)
        }
    }
}

data class ExamSchedule(val milestones: List<Int>, val dailyFinal: Int, val weekly: Boolean) {
    fun encode(): String = milestones.joinToString(",") + ";" + dailyFinal + ";" + (if (weekly) 1 else 0)

    companion object {
        fun decode(s: String): ExamSchedule? {
            val parts = s.split(';')
            if (parts.size != 3) return null
            val ms = parts[0].split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..365 }.distinct().sortedDescending()
            return ExamSchedule(ms, parts[1].toIntOrNull()?.coerceIn(0, 30) ?: 0, parts[2] == "1")
        }

        /** "90, 60, 30 days" → [90, 60, 30] */
        fun parseDays(text: String): List<Int> =
            text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..365 }.distinct().sortedDescending()
    }
}

/** Everything the user can configure. Defaults are the out-of-the-box experience. */
data class AppPrefs(
    val themeMode: ThemeMode = ThemeMode.UNSET,
    // Class reminders
    val remindersEnabled: Boolean = true,
    val leadMinutes: Set<Int> = setOf(10),
    val classEndNudge: Boolean = true,
    val morningSummary: Boolean = true,
    val morningSummaryMinutes: Int = 8 * 60,
    val messageStyle: MessageStyle = MessageStyle.MIX,
    // Exams
    val examRemindersEnabled: Boolean = true,
    val examCountdownMinutes: Int = 8 * 60,
    val examEveningMinutes: Int = 19 * 60,
    val disabledExamTypes: Set<String> = emptySet(),
    /** Per exam type (ExamType.name) schedule overrides; missing = built-in default. */
    val examSchedules: Map<String, ExamSchedule> = emptyMap(),
    /** Attendance from before using ClassVibe, per subject: course → (attended, held). */
    val attendanceBaseline: Map<String, AttendanceBaseline> = emptyMap(),
    // Weekend prep
    val weekendPrep: Boolean = true,
    val weekendDays: Set<Int> = setOf(6, 7),
    val weekendMorningMinutes: Int = 10 * 60,
    val weekendEveningMinutes: Int = 18 * 60,
    val weekendLookaheadWeeks: Int = 1,
    val weekendNoExamMessage: Boolean = false,
    // Semester
    val semesterStartEpochDay: Long? = null,
    val semesterEndEpochDay: Long? = null,
    // Updates
    val updateChecks: Boolean = true,
    val betaUpdates: Boolean = false,
    val skippedVersion: String = "",
    val lastSeenVersionCode: Int = 0,
    // Learned data
    val customSubjects: Set<String> = emptySet(),
    val abbreviations: Map<String, String> = emptyMap()
) {
    val semesterStart: LocalDate? get() = semesterStartEpochDay?.let { LocalDate.ofEpochDay(it) }
    val semesterEnd: LocalDate? get() = semesterEndEpochDay?.let { LocalDate.ofEpochDay(it) }
}

object AppSettings {

    /** Reminder lead-time options shown in Settings (minutes before class). */
    val LEAD_OPTIONS = listOf(0, 5, 10, 15, 20, 30, 45, 60)

    fun leadLabel(lead: Int): String = when (lead) {
        0 -> "At class start"
        60 -> "1 hour before"
        else -> "$lead minutes before"
    }

    object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val LEADS = stringSetPreferencesKey("reminder_leads")
        val CLASS_END = booleanPreferencesKey("class_end_nudge")
        val MORNING = booleanPreferencesKey("morning_summary")
        val MORNING_TIME = intPreferencesKey("morning_summary_minutes")
        val STYLE = stringPreferencesKey("message_style")
        val EXAMS = booleanPreferencesKey("exam_reminders")
        val EXAM_COUNTDOWN_TIME = intPreferencesKey("exam_countdown_minutes")
        val EXAM_EVENING_TIME = intPreferencesKey("exam_evening_minutes")
        val EXAM_DISABLED_TYPES = stringSetPreferencesKey("exam_disabled_types")
        val EXAM_SCHEDULES = stringSetPreferencesKey("exam_schedules") // entries "TYPE=90,60;7;1"
        val ATTENDANCE_BASELINE = stringSetPreferencesKey("attendance_baseline") // entries "attended|held|course"
        val CALENDAR_REMINDERS = booleanPreferencesKey("calendar_export_reminders")
        val WEEKEND = booleanPreferencesKey("weekend_prep")
        val WEEKEND_DAYS = stringSetPreferencesKey("weekend_days")
        val WEEKEND_MORNING = intPreferencesKey("weekend_morning_minutes")
        val WEEKEND_EVENING = intPreferencesKey("weekend_evening_minutes")
        val WEEKEND_LOOKAHEAD = intPreferencesKey("weekend_lookahead_weeks")
        val WEEKEND_NO_EXAM = booleanPreferencesKey("weekend_no_exam_message")
        val SEM_START = longPreferencesKey("semester_start")
        val SEM_END = longPreferencesKey("semester_end")
        val UPDATES = booleanPreferencesKey("update_checks")
        val BETA = booleanPreferencesKey("beta_updates")
        val SKIPPED = stringPreferencesKey("skipped_version")
        val LAST_SEEN_VERSION = intPreferencesKey("last_seen_version_code")
        val CUSTOM_SUBJECTS = stringSetPreferencesKey("custom_subjects")
        val ABBREVIATIONS = stringSetPreferencesKey("abbreviations")
        // Update state (not user-facing settings)
        val AVAILABLE_UPDATE = stringPreferencesKey("available_update_json")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val DOWNLOAD_ID = longPreferencesKey("update_download_id")
        val DOWNLOAD_TAG = stringPreferencesKey("update_download_tag")
    }

    private const val SEP = "\u001F"

    fun flow(context: Context): Flow<AppPrefs> = context.settingsStore.data.map { p -> fromPrefs(p) }

    suspend fun get(context: Context): AppPrefs = flow(context).first()

    suspend fun raw(context: Context): Preferences = context.settingsStore.data.first()

    fun rawFlow(context: Context): Flow<Preferences> = context.settingsStore.data

    suspend fun update(context: Context, block: (MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    private fun fromPrefs(p: Preferences) = AppPrefs(
        themeMode = p[Keys.THEME]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.UNSET,
        remindersEnabled = p[Keys.REMINDERS] ?: true,
        leadMinutes = p[Keys.LEADS]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(10),
        classEndNudge = p[Keys.CLASS_END] ?: true,
        morningSummary = p[Keys.MORNING] ?: true,
        morningSummaryMinutes = p[Keys.MORNING_TIME] ?: (8 * 60),
        messageStyle = p[Keys.STYLE]?.let { v -> MessageStyle.entries.firstOrNull { it.name == v } } ?: MessageStyle.MIX,
        examRemindersEnabled = p[Keys.EXAMS] ?: true,
        examCountdownMinutes = p[Keys.EXAM_COUNTDOWN_TIME] ?: (8 * 60),
        examEveningMinutes = p[Keys.EXAM_EVENING_TIME] ?: (19 * 60),
        disabledExamTypes = p[Keys.EXAM_DISABLED_TYPES] ?: emptySet(),
        examSchedules = (p[Keys.EXAM_SCHEDULES] ?: emptySet()).mapNotNull { entry ->
            val type = entry.substringBefore('=', "")
            ExamSchedule.decode(entry.substringAfter('=', ""))?.let { type to it }
        }.toMap(),
        attendanceBaseline = (p[Keys.ATTENDANCE_BASELINE] ?: emptySet()).mapNotNull { AttendanceBaseline.decode(it) }
            .associateBy { it.course },
        weekendPrep = p[Keys.WEEKEND] ?: true,
        weekendDays = p[Keys.WEEKEND_DAYS]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(6, 7),
        weekendMorningMinutes = p[Keys.WEEKEND_MORNING] ?: (10 * 60),
        weekendEveningMinutes = p[Keys.WEEKEND_EVENING] ?: (18 * 60),
        weekendLookaheadWeeks = (p[Keys.WEEKEND_LOOKAHEAD] ?: 1).coerceIn(1, 2),
        weekendNoExamMessage = p[Keys.WEEKEND_NO_EXAM] ?: false,
        semesterStartEpochDay = p[Keys.SEM_START],
        semesterEndEpochDay = p[Keys.SEM_END],
        updateChecks = p[Keys.UPDATES] ?: true,
        betaUpdates = p[Keys.BETA] ?: false,
        skippedVersion = p[Keys.SKIPPED].orEmpty(),
        lastSeenVersionCode = p[Keys.LAST_SEEN_VERSION] ?: 0,
        customSubjects = p[Keys.CUSTOM_SUBJECTS] ?: emptySet(),
        abbreviations = (p[Keys.ABBREVIATIONS] ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split(SEP)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    )

    // ---------- Convenience setters ----------

    suspend fun setTheme(context: Context, mode: ThemeMode) = update(context) { it[Keys.THEME] = mode.name }

    suspend fun setLeads(context: Context, leads: Set<Int>) =
        update(context) { it[Keys.LEADS] = leads.map { l -> l.toString() }.toSet() }

    /** null schedule = reset that exam type to the built-in default. */
    suspend fun setExamSchedule(context: Context, typeName: String, schedule: ExamSchedule?) = update(context) { p ->
        val others = (p[Keys.EXAM_SCHEDULES] ?: emptySet()).filterNot { it.startsWith("$typeName=") }.toSet()
        p[Keys.EXAM_SCHEDULES] = if (schedule == null) others else others + "$typeName=${schedule.encode()}"
    }

    /** null = remove the starting numbers for that subject. */
    suspend fun setAttendanceBaseline(context: Context, course: String, baseline: AttendanceBaseline?) = update(context) { p ->
        val others = (p[Keys.ATTENDANCE_BASELINE] ?: emptySet())
            .filterNot { AttendanceBaseline.decode(it)?.course.equals(course, ignoreCase = true) }.toSet()
        p[Keys.ATTENDANCE_BASELINE] = if (baseline == null || baseline.held == 0) others else others + baseline.encode()
    }

    suspend fun addCustomSubject(context: Context, subject: String) {
        val clean = subject.trim()
        if (clean.isEmpty()) return
        update(context) { it[Keys.CUSTOM_SUBJECTS] = (it[Keys.CUSTOM_SUBJECTS] ?: emptySet()) + clean }
    }

    /** Remembers subjects typed for exams that aren't in the timetable, for the dropdown. */
    suspend fun addCustomSubjectIfNew(context: Context, subject: String, timetableCourses: List<String>) {
        if (timetableCourses.none { it.equals(subject.trim(), ignoreCase = true) }) addCustomSubject(context, subject)
    }

    suspend fun learnAbbreviation(context: Context, short: String, full: String) {
        val key = short.trim().lowercase()
        if (key.isEmpty() || full.isBlank() || key == full.trim().lowercase()) return
        update(context) { p ->
            val current = (p[Keys.ABBREVIATIONS] ?: emptySet()).filterNot { it.startsWith(key + SEP) }
            p[Keys.ABBREVIATIONS] = (current + (key + SEP + full.trim())).toSet()
        }
    }
}
