package com.kosilka.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.data.device.TransportMode
import com.kosilka.data.device.TransportModeStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TransportModeDebugViewModel @Inject constructor(
    private val transportModeStore: TransportModeStore
) : ViewModel() {

    val currentMode: StateFlow<TransportMode> = transportModeStore.modeFlow
        .map { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransportMode.USB
        )

    fun setMode(mode: TransportMode) {
        viewModelScope.launch {
            transportModeStore.setMode(mode)
        }
    }

    val serviceEndpoint: StateFlow<String> = transportModeStore.serviceEndpointFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ""
        )

    fun setServiceEndpoint(endpoint: String) {
        viewModelScope.launch {
            val normalized = endpoint.trim().trimEnd('/')
            if (normalized.isBlank()) {
                return@launch
            }
            transportModeStore.setServiceEndpoint(normalized)
        }
    }

    fun resetServiceEndpoint() {
        viewModelScope.launch {
            transportModeStore.resetServiceEndpoint()
        }
    }
}
