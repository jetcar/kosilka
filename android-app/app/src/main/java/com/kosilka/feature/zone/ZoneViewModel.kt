package com.kosilka.feature.zone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.usecase.ConnectMowerUseCase
import com.kosilka.domain.usecase.ConnectionState
import com.kosilka.domain.usecase.DefineZoneResult
import com.kosilka.domain.usecase.DefineZoneUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ZoneViewModel @Inject constructor(
    private val defineZoneUseCase: DefineZoneUseCase,
    private val connectMowerUseCase: ConnectMowerUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ZoneUiState())
    val uiState: StateFlow<ZoneUiState> = _uiState.asStateFlow()

    private var activeSessionId: String? = null

    init {
        viewModelScope.launch {
            defineZoneUseCase.observeZone().collect { zone ->
                _uiState.update { current -> current.copy(currentZone = zone) }
            }
        }

        viewModelScope.launch {
            connectMowerUseCase.connectionState.collect { state ->
                activeSessionId = (state as? ConnectionState.Connected)?.sessionId
            }
        }
    }

    fun addVertex(point: Point2dMm) {
        _uiState.update { it.copy(draftVertices = it.draftVertices + point, statusMessage = null) }
    }

    fun removeLastVertex() {
        _uiState.update { current ->
            if (current.draftVertices.isEmpty()) current
            else current.copy(draftVertices = current.draftVertices.dropLast(1))
        }
    }

    fun clearDraft() {
        _uiState.update { it.copy(draftVertices = emptyList(), statusMessage = null) }
    }

    fun confirmZone() {
        viewModelScope.launch {
            val sessionId = activeSessionId
            if (sessionId == null) {
                _uiState.update { it.copy(statusMessage = "Not connected") }
                return@launch
            }

            val vertices = _uiState.value.draftVertices
            _uiState.update { it.copy(isSaving = true, statusMessage = null) }

            when (val result = defineZoneUseCase.defineZone(sessionId, vertices)) {
                is DefineZoneResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            draftVertices = emptyList(),
                            currentZone = result.zone,
                            statusMessage = "Zone saved"
                        )
                    }
                }

                is DefineZoneResult.Invalid -> {
                    _uiState.update { it.copy(isSaving = false, statusMessage = result.reason) }
                }

                is DefineZoneResult.DeliveryFailed -> {
                    _uiState.update { it.copy(isSaving = false, statusMessage = result.reason) }
                }

                is DefineZoneResult.FirmwareError -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            statusMessage = "Firmware error ${result.name} (${result.code})"
                        )
                    }
                }
            }
        }
    }
}
