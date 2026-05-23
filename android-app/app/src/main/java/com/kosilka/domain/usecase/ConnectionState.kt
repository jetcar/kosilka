package com.kosilka.domain.usecase

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val sessionId: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
