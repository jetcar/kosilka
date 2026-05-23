package com.kosilka.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.domain.usecase.ConnectMowerUseCase
import com.kosilka.domain.usecase.ConnectionState
import com.kosilka.domain.usecase.ManageScheduleUseCase
import com.kosilka.domain.usecase.ScheduleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val manageScheduleUseCase: ManageScheduleUseCase,
    private val connectMowerUseCase: ConnectMowerUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    private var activeSessionId: String? = null

    init {
        viewModelScope.launch {
            manageScheduleUseCase.observeSchedules().collect { schedules ->
                _uiState.update { it.copy(schedules = schedules) }
            }
        }

        viewModelScope.launch {
            connectMowerUseCase.connectionState.collect { state ->
                activeSessionId = (state as? ConnectionState.Connected)?.sessionId
            }
        }
    }

    fun updateStartTime(value: String) {
        _uiState.update { it.copy(startTimeInput = value) }
    }

    fun toggleDay(day: Int) {
        _uiState.update { current ->
            val next = current.selectedDays.toMutableSet()
            if (!next.add(day)) {
                next.remove(day)
            }
            current.copy(selectedDays = next)
        }
    }

    fun createSchedule() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = null) }

            when (
                val result = manageScheduleUseCase.createSchedule(
                    sessionId = activeSessionId,
                    startTimeUtcHhmm = _uiState.value.startTimeInput,
                    daysOfWeek = _uiState.value.selectedDays.toList().sorted(),
                    zoneId = _uiState.value.selectedZoneId
                )
            ) {
                is ScheduleResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, statusMessage = "Schedule saved") }
                }

                is ScheduleResult.QueuedOffline -> {
                    _uiState.update { it.copy(isSaving = false, statusMessage = "Queued for sync") }
                }

                is ScheduleResult.Invalid -> {
                    _uiState.update { it.copy(isSaving = false, statusMessage = result.reason) }
                }
            }
        }
    }

    fun deleteSchedule(scheduleId: String) {
        viewModelScope.launch {
            when (val result = manageScheduleUseCase.deleteSchedule(activeSessionId, scheduleId)) {
                is ScheduleResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Schedule deleted") }
                }

                is ScheduleResult.QueuedOffline -> {
                    _uiState.update { it.copy(statusMessage = "Delete queued for sync") }
                }

                is ScheduleResult.Invalid -> {
                    _uiState.update { it.copy(statusMessage = result.reason) }
                }
            }
        }
    }
}
