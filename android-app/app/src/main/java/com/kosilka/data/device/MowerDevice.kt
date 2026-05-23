package com.kosilka.data.device

import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the physical mower transport.
 *
 * All domain, use-case, and repository code depends only on this interface.
 * Transport implementations remain inside the data layer.
 * Hilt provides the concrete binding per build flavor.
 */
interface MowerDevice {

    /** Lifecycle events emitted when the connection state changes. */
    val connectionEvents: Flow<ConnectionEvent>

    /** Stream of decoded messages received from the mower. */
    val incomingMessages: Flow<IncomingMessage>

    /**
     * Initiate the connection to the mower.
     * Returns [Result.success] when the transport is ready to send/receive.
     * Returns [Result.failure] if the connection could not be established.
     */
    suspend fun connect(): Result<Unit>

    /** Close the connection and release all resources. */
    suspend fun disconnect()

    /**
     * Send an outgoing envelope to the mower.
     * Returns [Result.success] on delivery, [Result.failure] on transport error.
     */
    suspend fun send(envelope: Envelope): Result<Unit>
}
