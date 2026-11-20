package com.classvibe.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

/** One day of placement training (Day 1 … Day N). */
@Entity(tableName = "training_day")
data class TrainingDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayNumber: Int,
    val epochDay: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val title: String = "",
    val venue: String = "",
    /** "" = not marked, ATTENDED, MISSED */
    @ColumnInfo(defaultValue = "''") val status: String = ""
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
    val startsAt: LocalDateTime get() = date.atStartOfDay().plusMinutes(startMinutes.toLong())
    val endsAt: LocalDateTime get() = date.atStartOfDay().plusMinutes(endMinutes.toLong())
    val label: String get() = "Day $dayNumber" + if (title.isNotBlank()) " – $title" else ""
}

@Dao
interface TrainingDao {
    @Query("SELECT * FROM training_day ORDER BY epochDay ASC, startMinutes ASC")
    fun observeAll(): Flow<List<TrainingDay>>

    @Query("SELECT * FROM training_day ORDER BY epochDay ASC, startMinutes ASC")
    suspend fun getAll(): List<TrainingDay>

    @Query("SELECT * FROM training_day WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TrainingDay?

    @Upsert
    suspend fun upsert(day: TrainingDay): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(days: List<TrainingDay>)

    @Delete
    suspend fun delete(day: TrainingDay)

    @Query("DELETE FROM training_day")
    suspend fun deleteAll()
}

/** The default placement-training calendar (added when a student opts in). */
object DefaultTraining {
    const val DEFAULT_START = 9 * 60      // 9:00 AM
    const val DEFAULT_END = 16 * 60       // 4:00 PM

    val DATES: List<LocalDate> = listOf(
        "2026-08-22", "2026-08-26", "2026-08-27", "2026-09-01", "2026-09-04", "2026-09-12",
        "2026-09-19", "2026-10-03", "2026-10-07", "2026-10-10", "2026-10-17", "2026-10-23",
        "2026-10-24", "2026-10-31", "2026-11-05", "2026-11-14", "2026-11-21", "2026-11-22"
    ).map(LocalDate::parse)

    fun days(start: Int = DEFAULT_START, end: Int = DEFAULT_END): List<TrainingDay> =
        DATES.mapIndexed { i, d -> TrainingDay(dayNumber = i + 1, epochDay = d.toEpochDay(), startMinutes = start, endMinutes = end) }

    /** The next day that hasn't finished yet (null when training is over). */
    fun next(days: List<TrainingDay>, now: LocalDateTime): TrainingDay? =
        days.filter { it.endsAt.isAfter(now) }.minByOrNull { it.startsAt }
}
