package com.kosilka.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kosilka.data.local.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Upsert
    suspend fun upsertSchedule(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules")
    fun getAllSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE pendingSync = 1")
    suspend fun getPendingSyncSchedules(): List<ScheduleEntity>

    @Query("DELETE FROM schedules WHERE scheduleId = :scheduleId")
    suspend fun deleteSchedule(scheduleId: String)

    @Query("UPDATE schedules SET pendingSync = 0 WHERE scheduleId = :scheduleId")
    suspend fun markSynced(scheduleId: String)
}
