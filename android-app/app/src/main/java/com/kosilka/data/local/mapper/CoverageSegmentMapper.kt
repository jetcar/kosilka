package com.kosilka.data.local.mapper

import com.kosilka.data.local.entity.CoverageSegmentEntity
import com.kosilka.domain.model.CoverageSegment

fun CoverageSegmentEntity.toDomain(): CoverageSegment = CoverageSegment(
    fromXMm = fromXMm,
    fromYMm = fromYMm,
    toXMm = toXMm,
    toYMm = toYMm
)

fun CoverageSegment.toEntity(sessionId: String): CoverageSegmentEntity = CoverageSegmentEntity(
    sessionId = sessionId,
    fromXMm = fromXMm,
    fromYMm = fromYMm,
    toXMm = toXMm,
    toYMm = toYMm
)
