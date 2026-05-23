package com.kosilka.domain.model

data class SessionRecord(
    val sessionId: String,
    val startTimestampUtcMs: Long,
    val endTimestampUtcMs: Long,
    val durationSeconds: Long,
    val totalDistanceMm: Long,
    val coveragePercent: Float
)
