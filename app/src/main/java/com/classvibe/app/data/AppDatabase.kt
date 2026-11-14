package com.classvibe.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ClassSchedule::class,
        Exam::class,
        AttendanceRecord::class,
        Holiday::class,
        CustomMessage::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao
    abstract fun examDao(): ExamDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun holidayDao(): HolidayDao
    abstract fun customMessageDao(): CustomMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 (ClassVibe 1.0.x) -> v2: keeps the timetable, adds the new tables. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `class_schedule` ADD COLUMN `weekParity` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exam` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, `customType` TEXT NOT NULL, `course` TEXT NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, `timeMinutes` INTEGER NOT NULL, `room` TEXT NOT NULL, " +
                        "`instructor` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdEpochDay` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attendance` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `classId` INTEGER NOT NULL, " +
                        "`course` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, `status` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_attendance_classId_epochDay` " +
                        "ON `attendance` (`classId`, `epochDay`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `holiday` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochDay` INTEGER NOT NULL, " +
                        "`endEpochDay` INTEGER NOT NULL, `label` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_message` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classvibe.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
