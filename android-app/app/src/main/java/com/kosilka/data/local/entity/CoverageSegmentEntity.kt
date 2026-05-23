package com.kosilka.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "coverage_segments")
data class CoverageSegmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val fromXMm: Int,
    val fromYMm: Int,
    val toXMm: Int,
    val toYMm: Int
)
