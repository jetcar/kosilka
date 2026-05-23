package com.kosilka.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_history")
data class SessionHistoryEntity(
    @PrimaryKey val sessionId: String,
    val startTimestampUtcMs: Long,
    val endTimestampUtcMs: Long,
    val durationSeconds: Long,
    val totalDistanceMm: Long,
    val coveragePercent: Float
)
