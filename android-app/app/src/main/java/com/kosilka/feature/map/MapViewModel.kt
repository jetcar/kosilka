package com.kosilka.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.core.UiEvent
import com.kosilka.core.UiEventBus
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.usecase.ConnectMowerUseCase
import com.kosilka.domain.usecase.ConnectionState
import com.kosilka.domain.usecase.DefineZoneUseCase
import com.kosilka.domain.usecase.LoadAnchorsUseCase
import com.kosilka.domain.usecase.LoadSessionCoverageUseCase
import com.kosilka.domain.usecase.MoveMowerResult
import com.kosilka.domain.usecase.MoveMowerUseCase
import com.kosilka.domain.usecase.SessionHistoryRepository
import com.kosilka.domain.usecase.StartRangingUseCase
import com.kosilka.domain.usecase.TrackCoverageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MapViewModel @Inject constructor(
    private val startRangingUseCase: StartRangingUseCase,
    private val connectMowerUseCase: ConnectMowerUseCase,
    private val moveMowerUseCase: MoveMowerUseCase,
    private val defineZoneUseCase: DefineZoneUseCase,
    private val trackCoverageUseCase: TrackCoverageUseCase,
    private val sessionHistoryRepository: SessionHistoryRepository,
    private val loadSessionCoverageUseCase: LoadSessionCoverageUseCase,
    private val uiEventBus: UiEventBus,
    private val loadAnchorsUseCase: LoadAnchorsUseCase
) : ViewModel() {

    private var activeSessionId: String? = null
    private var offlineCoverageJob: Job? = null

    private val _uiState = MutableStateFlow(
        MapUiState()
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadAnchorsUseCase.ensureDefaultAnchors()
        }

        viewModelScope.launch {
            loadAnchorsUseCase.observeAnchors().collect { anchors ->
                _uiState.update { it.copy(anchors = anchors) }
            }
        }

        viewModelScope.launch {
            startRangingUseCase.state.collect { rangingState ->
                _uiState.update { current ->
                    current.copy(
                        mowerPosition = rangingState.latestPosition,
                        isPositionLost = rangingState.isPositionLost,
                        isRangingActive = rangingState.isRangingActive
                    )
                }
            }
        }

        viewModelScope.launch {
            connectMowerUseCase.connectionState.collect { connectionState ->
                when (connectionState) {
                    is ConnectionState.Connected -> {
                        activeSessionId = connectionState.sessionId
                        trackCoverageUseCase.startSession(
                            sessionId = connectionState.sessionId,
                            zone = _uiState.value.zone
                        )
                        _uiState.update { it.copy(isConnected = true, statusMessage = null) }
                    }

                    ConnectionState.Connecting -> {
                        _uiState.update { it.copy(isConnected = false, statusMessage = "Connecting...") }
                    }

                    ConnectionState.Disconnected -> {
                        activeSessionId = null
                        trackCoverageUseCase.stop()
                        _uiState.update { it.copy(isConnected = false, statusMessage = "Not connected") }
                        loadMostRecentCoverageFallback()
                    }

                    is ConnectionState.Failed -> {
                        activeSessionId = null
                        trackCoverageUseCase.stop()
                        _uiState.update { it.copy(isConnected = false, statusMessage = connectionState.reason) }
                        loadMostRecentCoverageFallback()
                    }
                }
            }
        }

        viewModelScope.launch {
            defineZoneUseCase.observeZone().collect { zone ->
                trackCoverageUseCase.updateZone(zone)
                _uiState.update { it.copy(zone = zone) }
            }
        }

        viewModelScope.launch {
            trackCoverageUseCase.state.collect { coverageState ->
                _uiState.update {
                    it.copy(
                        coverageSegments = coverageState.segments,
                        coveragePercent = coverageState.coveragePercent
                    )
                }
            }
        }

        viewModelScope.launch {
            uiEventBus.events.collect { event ->
                if (event is UiEvent.Snackbar) {
                    _uiState.update { it.copy(statusMessage = event.message) }
                }
            }
        }
    }

    private fun loadMostRecentCoverageFallback() {
        offlineCoverageJob?.cancel()
        offlineCoverageJob = viewModelScope.launch {
            val recent = sessionHistoryRepository.observeMostRecentSession().first() ?: return@launch
            loadSessionCoverageUseCase.load(recent.sessionId).collect { segments ->
                _uiState.update {
                    it.copy(
                        coverageSegments = segments,
                        coveragePercent = recent.coveragePercent
                    )
                }
            }
        }
    }

    fun startRanging() {
        viewModelScope.launch {
            val sessionId = activeSessionId
            if (sessionId == null) {
                _uiState.update { it.copy(statusMessage = "Connect before ranging") }
                return@launch
            }

            startRangingUseCase.start(
                sessionId = sessionId,
                sampleRateHz = 5,
                anchorsById = _uiState.value.anchors.associate { anchor ->
                    anchor.id to Point2dMm(anchor.xMm, anchor.yMm)
                }
            )
        }
    }

    fun stopRanging() {
        viewModelScope.launch {
            startRangingUseCase.stop()
        }
    }

    fun onMapTapped(point: Point2dMm) {
        viewModelScope.launch {
            val sessionId = activeSessionId
            if (sessionId == null) {
                _uiState.update { it.copy(statusMessage = "Not connected") }
                return@launch
            }

            when (
                val result = moveMowerUseCase.moveTo(
                    sessionId = sessionId,
                    target = point,
                    zone = _uiState.value.zone
                )
            ) {
                MoveMowerResult.Success -> {
                    _uiState.update {
                        it.copy(
                            destinationMarker = point,
                            statusMessage = null
                        )
                    }
                }

                MoveMowerResult.Busy -> {
                    _uiState.update { it.copy(statusMessage = "Mower busy") }
                }

                MoveMowerResult.OutsideZone -> {
                    _uiState.update { it.copy(statusMessage = "Outside zone") }
                }

                is MoveMowerResult.DeliveryFailed -> {
                    _uiState.update { it.copy(statusMessage = result.reason) }
                }
            }
        }
    }
}
