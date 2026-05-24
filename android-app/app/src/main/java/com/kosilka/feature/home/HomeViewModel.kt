package com.kosilka.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.core.UiEvent
import com.kosilka.core.UiEventBus
import com.kosilka.domain.usecase.ConnectMowerUseCase
import com.kosilka.domain.usecase.ConnectionState
import com.kosilka.domain.usecase.PendingSyncUseCase
import com.kosilka.domain.usecase.SessionHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectMowerUseCase: ConnectMowerUseCase,
    private val pendingSyncUseCase: PendingSyncUseCase,
    private val sessionHistoryRepository: SessionHistoryRepository,
    private val uiEventBus: UiEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var autoConnectJob: Job? = null

    init {
        pendingSyncUseCase.start()
        sessionHistoryRepository.startTracking()

        viewModelScope.launch {
            sessionHistoryRepository.observeMostRecentSession().collectLatest { mostRecent ->
                _uiState.update {
                    it.copy(
                        mostRecentSessionSummary = mostRecent?.let { record ->
                            "${record.durationSeconds}s | ${"%.1f".format(record.coveragePercent)}%"
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            uiEventBus.events.collectLatest { event ->
                if (event is UiEvent.Snackbar) {
                    _uiState.update { it.copy(errorMessage = event.message) }
                }
            }
        }

        viewModelScope.launch {
            connectMowerUseCase.connectionState.collectLatest { state ->
                _uiState.update { current ->
                    when (state) {
                        ConnectionState.Disconnected -> current.copy(
                            connectionLabel = "Disconnected",
                            isBusy = false,
                            canConnect = true,
                            canDisconnect = false,
                            errorMessage = null
                        )

                        ConnectionState.Connecting -> current.copy(
                            connectionLabel = "Connecting...",
                            isBusy = true,
                            canConnect = false,
                            canDisconnect = false,
                            errorMessage = null
                        )

                        is ConnectionState.Connected -> current.copy(
                            connectionLabel = "Connected (${state.sessionId})",
                            isBusy = false,
                            canConnect = false,
                            canDisconnect = true,
                            errorMessage = null
                        )

                        is ConnectionState.Failed -> current.copy(
                            connectionLabel = "Connection failed",
                            isBusy = false,
                            canConnect = true,
                            canDisconnect = false,
                            errorMessage = state.reason
                        )
                    }
                }

                when (state) {
                    is ConnectionState.Connected,
                    ConnectionState.Connecting -> stopAutoConnectLoop()

                    ConnectionState.Disconnected,
                    is ConnectionState.Failed -> ensureAutoConnectLoop()
                }
            }
        }

        ensureAutoConnectLoop()
    }

    fun connect() {
        viewModelScope.launch {
            connectMowerUseCase.connect()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopAutoConnectLoop()
            connectMowerUseCase.disconnectByUser()
        }
    }

    private fun ensureAutoConnectLoop() {
        if (autoConnectJob?.isActive == true) {
            return
        }

        autoConnectJob = viewModelScope.launch {
            while (isActive) {
                val state = connectMowerUseCase.connectionState.value
                if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                    break
                }
                connectMowerUseCase.connect()
                delay(AUTO_CONNECT_RETRY_MS)
            }
        }
    }

    private fun stopAutoConnectLoop() {
        autoConnectJob?.cancel()
        autoConnectJob = null
    }

    private companion object {
        const val AUTO_CONNECT_RETRY_MS = 2_000L
    }
}
