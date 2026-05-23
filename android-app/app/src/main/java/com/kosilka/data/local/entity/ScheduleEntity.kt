package com.kosilka.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val scheduleId: String,
    val startTimeUtcHhmm: String,
    // JSON-serialised array of day-of-week integers (0=Sunday … 6=Saturday)
    val daysOfWeekJson: String,
    val zoneId: String?,
    // true if locally marked for deletion and awaiting sync
    val isDeleted: Boolean,
    // true when the schedule has not yet been synced to the firmware
    val pendingSync: Boolean
)
