package com.classvibe.app.data

import android.content.Context
import com.classvibe.app.notifications.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full backup as a single JSON file: timetable, exams, attendance, holidays,
 * custom messages and the main settings. Nothing leaves the phone unless the
 * user saves / shares the file.
 */
object BackupManager {

    private const val FORMAT = "classvibe-backup"
    private const val FORMAT_VERSION = 1

    suspend fun export(context: Context): String {
        val db = AppDatabase.get(context)
        val prefs = AppSettings.get(context)
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("classes", JSONArray().apply {
            db.scheduleDao().getAll().forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("dayOfWeek", c.dayOfWeek); put("startMinutes", c.startMinutes)
                    put("endMinutes", c.endMinutes); put("course", c.course); put("classType", c.classType)
                    put("room", c.room); put("instructor", c.instructor); put("weekParity", c.weekParity)
                })
            }
        })
        root.put("exams", JSONArray().apply {
            db.examDao().getAll().forEach { e ->
                put(JSONObject().apply {
                    put("type", e.type); put("customType", e.customType); put("course", e.course)
                    put("epochDay", e.epochDay); put("timeMinutes", e.timeMinutes); put("room", e.room)
                    put("instructor", e.instructor); put("notes", e.notes); put("createdEpochDay", e.createdEpochDay)
                })
            }
        })
        root.put("attendance", JSONArray().apply {
            db.attendanceDao().getAll().forEach { a ->
                put(JSONObject().apply {
                    put("classId", a.classId); put("course", a.course); put("epochDay", a.epochDay); put("status", a.status)
                })
            }
        })
        root.put("holidays", JSONArray().apply {
            db.holidayDao().getAll().forEach { h ->
                put(JSONObject().apply {
                    put("start", h.startEpochDay); put("end", h.endEpochDay); put("label", h.label)
                })
            }
        })
        root.put("customMessages", JSONArray().apply {
            db.customMessageDao().getAll().forEach { m ->
                put(JSONObject().apply { put("category", m.category); put("text", m.text) })
            }
        })
        root.put("settings", JSONObject().apply {
            put("leads", JSONArray(prefs.leadMinutes.toList()))
            put("semesterStart", prefs.semesterStartEpochDay ?: JSONObject.NULL)
            put("semesterEnd", prefs.semesterEndEpochDay ?: JSONObject.NULL)
            put("customSubjects", JSONArray(prefs.customSubjects.toList()))
            put("attendanceBaseline", JSONArray(prefs.attendanceBaseline.values.map { it.encode() }))
            put("messageStyle", prefs.messageStyle.name)
        })
        return root.toString(2)
    }

    /** Replaces all local data with the backup. Returns a short summary. */
    suspend fun import(context: Context, json: String): String {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This isn't a ClassVibe backup file." }
        val db = AppDatabase.get(context)
        val repo = ClassVibeRepository(context)
        repo.wipeAll()

        // Classes: keep a map old id -> new id so attendance stays linked.
        val classesJson = root.optJSONArray("classes") ?: JSONArray()
        val idMap = mutableMapOf<Long, Long>()
        for (i in 0 until classesJson.length()) {
            val o = classesJson.getJSONObject(i)
            val newId = db.scheduleDao().insertAll(
                listOf(
                    ClassSchedule(
                        dayOfWeek = o.getInt("dayOfWeek"),
                        startMinutes = o.getInt("startMinutes"),
                        endMinutes = o.getInt("endMinutes"),
                        course = o.getString("course"),
                        classType = o.optString("classType", "Lecture"),
                        room = o.optString("room"),
                        instructor = o.optString("instructor"),
                        weekParity = o.optInt("weekParity", 0)
                    )
                )
            ).first()
            idMap[o.optLong("id")] = newId
        }

        val examsJson = root.optJSONArray("exams") ?: JSONArray()
        val exams = (0 until examsJson.length()).map { i ->
            val o = examsJson.getJSONObject(i)
            Exam(
                type = o.getString("type"),
                customType = o.optString("customType"),
                course = o.getString("course"),
                epochDay = o.getLong("epochDay"),
                timeMinutes = o.optInt("timeMinutes", 600),
                room = o.optString("room"),
                instructor = o.optString("instructor"),
                notes = o.optString("notes"),
                createdEpochDay = o.optLong("createdEpochDay", o.getLong("epochDay"))
            )
        }
        db.examDao().insertAll(exams)

        val attJson = root.optJSONArray("attendance") ?: JSONArray()
        val records = (0 until attJson.length()).mapNotNull { i ->
            val o = attJson.getJSONObject(i)
            val newId = idMap[o.getLong("classId")] ?: return@mapNotNull null
            AttendanceRecord(classId = newId, course = o.getString("course"), epochDay = o.getLong("epochDay"), status = o.getString("status"))
        }
        db.attendanceDao().insertAll(records)

        val holJson = root.optJSONArray("holidays") ?: JSONArray()
        db.holidayDao().insertAll((0 until holJson.length()).map { i ->
            val o = holJson.getJSONObject(i)
            Holiday(startEpochDay = o.getLong("start"), endEpochDay = o.getLong("end"), label = o.optString("label", "Holiday"))
        })

        val msgJson = root.optJSONArray("customMessages") ?: JSONArray()
        db.customMessageDao().insertAll((0 until msgJson.length()).map { i ->
            val o = msgJson.getJSONObject(i)
            CustomMessage(category = o.getString("category"), text = o.getString("text"))
        })

        root.optJSONObject("settings")?.let { s ->
            AppSettings.update(context) { p ->
                s.optJSONArray("leads")?.let { arr ->
                    p[AppSettings.Keys.LEADS] = (0 until arr.length()).map { arr.getInt(it).toString() }.toSet()
                }
                if (!s.isNull("semesterStart")) p[AppSettings.Keys.SEM_START] = s.getLong("semesterStart")
                if (!s.isNull("semesterEnd")) p[AppSettings.Keys.SEM_END] = s.getLong("semesterEnd")
                s.optJSONArray("customSubjects")?.let { arr ->
                    p[AppSettings.Keys.CUSTOM_SUBJECTS] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
                s.optJSONArray("attendanceBaseline")?.let { arr ->
                    p[AppSettings.Keys.ATTENDANCE_BASELINE] = (0 until arr.length()).map { arr.getString(it) }
                        .filter { AttendanceBaseline.decode(it) != null }.toSet()
                }
                s.optString("messageStyle").takeIf { it.isNotBlank() }?.let { p[AppSettings.Keys.STYLE] = it }
            }
        }

        ReminderScheduler.rescheduleAll(context)
        return "Restored ${classesJson.length()} classes, ${exams.size} exams, ${records.size} attendance marks"
    }
}
