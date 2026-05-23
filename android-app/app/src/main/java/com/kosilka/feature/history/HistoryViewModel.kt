package com.kosilka.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.domain.model.SessionRecord
import com.kosilka.domain.usecase.LoadHistoryUseCase
import com.kosilka.domain.usecase.LoadSessionCoverageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val loadHistoryUseCase: LoadHistoryUseCase,
    private val loadSessionCoverageUseCase: LoadSessionCoverageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private var selectedCoverageJob: Job? = null

    init {
        viewModelScope.launch {
            loadHistoryUseCase.loadHistory().collect { records ->
                _uiState.update { current ->
                    current.copy(
                        records = records,
                        selectedRecord = current.selectedRecord?.let { selected ->
                            records.firstOrNull { it.sessionId == selected.sessionId }
                        }
                    )
                }
            }
        }
    }

    fun selectRecord(record: SessionRecord) {
        _uiState.update { it.copy(selectedRecord = record) }

        selectedCoverageJob?.cancel()
        selectedCoverageJob = viewModelScope.launch {
            loadSessionCoverageUseCase.load(record.sessionId).collect { segments ->
                _uiState.update { current -> current.copy(selectedCoverageSegments = segments) }
            }
        }
    }
}
