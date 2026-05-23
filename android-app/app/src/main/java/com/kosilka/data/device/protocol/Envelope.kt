package com.kosilka.data.device.protocol

/**
 * Canonical outgoing message envelope.
 * All fields are required; matches the protocol contract in protocol/message-contract.md.
 */
data class Envelope(
    val protocolVersion: Int,
    val messageType: String,
    val messageId: Long,
    val sessionId: String,
    val timestampMs: Long,
    val payload: Map<String, Any?>
)
