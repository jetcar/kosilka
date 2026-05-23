package com.kosilka.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.core.emulator.EmulatorScenario
import com.kosilka.core.emulator.EmulatorScenarioEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class EmulatorControlViewModel @Inject constructor(
    private val scenarioEngine: EmulatorScenarioEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmulatorUiState())
    val uiState: StateFlow<EmulatorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val scenario = scenarioEngine.activeScenario()
                val position = scenarioEngine.currentPosition()
                _uiState.update {
                    it.copy(
                        activeScenarioLabel = scenario.label(),
                        currentPosition = position
                    )
                }
                delay(500L)
            }
        }
    }

    fun updateDuration(value: String) {
        _uiState.update { it.copy(durationMs = value) }
    }

    fun updateDriftRate(value: String) {
        _uiState.update { it.copy(driftRateMmPerSec = value) }
    }

    fun activateScenario(scenarioType: EmulatorScenarioType) {
        val state = _uiState.value
        val durationMs = state.durationMs.toLongOrNull()?.coerceAtLeast(500L) ?: 5_000L
        val driftRate = state.driftRateMmPerSec.toFloatOrNull()?.coerceAtLeast(1f) ?: 80f

        val scenario = when (scenarioType) {
            EmulatorScenarioType.NORMAL -> EmulatorScenario.Normal
            EmulatorScenarioType.DRIFT -> EmulatorScenario.Drift(driftRateMmPerSec = driftRate)
            EmulatorScenarioType.STUCK -> EmulatorScenario.Stuck(durationMs = durationMs)
            EmulatorScenarioType.SIGNAL_INTERFERENCE -> EmulatorScenario.SignalInterference(durationMs = durationMs)
            EmulatorScenarioType.SIGNAL_LOSS -> EmulatorScenario.SignalLoss(durationMs = durationMs)
            EmulatorScenarioType.BUSY -> EmulatorScenario.Busy(durationMs = durationMs)
        }

        scenarioEngine.activateScenario(scenario)
    }

    fun clearScenario() {
        scenarioEngine.clearScenario()
    }
}

enum class EmulatorScenarioType(val label: String) {
    NORMAL("Normal"),
    DRIFT("Drift"),
    STUCK("Stuck"),
    SIGNAL_INTERFERENCE("Signal Interference"),
    SIGNAL_LOSS("Signal Loss"),
    BUSY("Busy")
}

private fun EmulatorScenario.label(): String = when (this) {
    EmulatorScenario.Normal -> "Normal"
    is EmulatorScenario.Drift -> "Drift"
    is EmulatorScenario.Stuck -> "Stuck"
    is EmulatorScenario.SignalInterference -> "Signal Interference"
    is EmulatorScenario.SignalLoss -> "Signal Loss"
    is EmulatorScenario.Busy -> "Busy"
}
