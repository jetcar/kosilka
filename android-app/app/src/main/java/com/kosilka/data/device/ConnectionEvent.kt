package com.kosilka.data.device

/** Events emitted by a MowerDevice when the connection state changes. */
sealed class ConnectionEvent {
    /** The device accepted the connection and a session is active. */
    data class Connected(val sessionId: String) : ConnectionEvent()

    /** The connection was closed, either by the user or by the device. */
    object Disconnected : ConnectionEvent()

    /** A connection-level error occurred. */
    data class Error(val message: String, val cause: Throwable? = null) : ConnectionEvent()
}
