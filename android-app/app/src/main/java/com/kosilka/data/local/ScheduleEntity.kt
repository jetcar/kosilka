package com.kosilka.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val scheduleId: String,
    val startTimeUtcHhmm: String,
    val daysOfWeekJson: String,  // JSON array of ints
    val zoneId: String?,
    val pendingSync: Boolean
)
