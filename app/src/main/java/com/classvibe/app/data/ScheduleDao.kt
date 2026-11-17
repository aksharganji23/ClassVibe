package com.classvibe.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScheduleDao {

    @Query("SELECT * FROM class_schedule ORDER BY dayOfWeek ASC, startMinutes ASC")
    abstract fun observeAll(): Flow<List<ClassSchedule>>

    @Query("SELECT * FROM class_schedule ORDER BY dayOfWeek ASC, startMinutes ASC")
    abstract suspend fun getAll(): List<ClassSchedule>

    @Query("SELECT * FROM class_schedule WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: Long): ClassSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<ClassSchedule>): List<Long>

    @Update
    abstract suspend fun update(item: ClassSchedule)

    @Delete
    abstract suspend fun delete(item: ClassSchedule)

    @Query("DELETE FROM class_schedule")
    abstract suspend fun deleteAll()

    /** Atomically swap the whole timetable for a freshly imported one. */
    @Transaction
    open suspend fun replaceAll(items: List<ClassSchedule>): List<Long> {
        deleteAll()
        return insertAll(items)
    }
}

@Dao
interface ExamDao {
    @Query("SELECT * FROM exam ORDER BY epochDay ASC, timeMinutes ASC")
    fun observeAll(): Flow<List<Exam>>

    @Query("SELECT * FROM exam ORDER BY epochDay ASC, timeMinutes ASC")
    suspend fun getAll(): List<Exam>

    @Query("SELECT * FROM exam WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Exam?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exam: Exam): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exams: List<Exam>): List<Long>

    @Update
    suspend fun update(exam: Exam)

    @Delete
    suspend fun delete(exam: Exam)

    @Query("DELETE FROM exam")
    suspend fun deleteAll()
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance ORDER BY epochDay DESC")
    fun observeAll(): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance ORDER BY epochDay DESC")
    suspend fun getAll(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance WHERE classId = :classId AND epochDay = :epochDay LIMIT 1")
    suspend fun get(classId: Long, epochDay: Long): AttendanceRecord?

    @Query("SELECT * FROM attendance WHERE course = :course")
    suspend fun forCourse(course: String): List<AttendanceRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AttendanceRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<AttendanceRecord>)

    @Query("DELETE FROM attendance WHERE classId = :classId AND epochDay = :epochDay")
    suspend fun clear(classId: Long, epochDay: Long)

    @Query("DELETE FROM attendance")
    suspend fun deleteAll()

    @Query("DELETE FROM attendance WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holiday ORDER BY startEpochDay ASC")
    fun observeAll(): Flow<List<Holiday>>

    @Query("SELECT * FROM holiday ORDER BY startEpochDay ASC")
    suspend fun getAll(): List<Holiday>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(holiday: Holiday): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(holidays: List<Holiday>)

    @Delete
    suspend fun delete(holiday: Holiday)

    @Query("DELETE FROM holiday")
    suspend fun deleteAll()
}

@Dao
interface CustomMessageDao {
    @Query("SELECT * FROM custom_message ORDER BY id DESC")
    fun observeAll(): Flow<List<CustomMessage>>

    @Query("SELECT * FROM custom_message")
    suspend fun getAll(): List<CustomMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: CustomMessage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<CustomMessage>)

    @Delete
    suspend fun delete(message: CustomMessage)

    @Query("DELETE FROM custom_message")
    suspend fun deleteAll()
}
