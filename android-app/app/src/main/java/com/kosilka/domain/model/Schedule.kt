package com.kosilka.domain.model

data class Schedule(
    val scheduleId: String,
    val startTimeUtcHhmm: String,
    val daysOfWeek: List<Int>,
    val zoneId: String?,
    val isDeleted: Boolean = false,
    val pendingSync: Boolean = false
)
