package com.kosilka.feature.schedule

import com.kosilka.domain.model.Schedule

data class ScheduleUiState(
    val schedules: List<Schedule> = emptyList(),
    val startTimeInput: String = "07:30",
    val selectedDays: Set<Int> = setOf(1, 3, 5),
    val selectedZoneId: String? = null,
    val statusMessage: String? = null,
    val isSaving: Boolean = false
)
