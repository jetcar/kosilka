package com.kosilka.data.local.mapper

import com.kosilka.data.local.entity.SessionHistoryEntity
import com.kosilka.domain.model.SessionRecord

fun SessionHistoryEntity.toDomain(): SessionRecord = SessionRecord(
    sessionId = sessionId,
    startTimestampUtcMs = startTimestampUtcMs,
    endTimestampUtcMs = endTimestampUtcMs,
    durationSeconds = durationSeconds,
    totalDistanceMm = totalDistanceMm,
    coveragePercent = coveragePercent
)

fun SessionRecord.toEntity(): SessionHistoryEntity = SessionHistoryEntity(
    sessionId = sessionId,
    startTimestampUtcMs = startTimestampUtcMs,
    endTimestampUtcMs = endTimestampUtcMs,
    durationSeconds = durationSeconds,
    totalDistanceMm = totalDistanceMm,
    coveragePercent = coveragePercent
)
