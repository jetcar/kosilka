package com.kosilka.data.device.protocol

/**
 * Sealed hierarchy of all message types the app can receive from the mower.
 * ProtocolDecoder maps raw JSON envelopes to these typed classes.
 */
sealed class IncomingMessage {
    abstract val messageId: Long
    abstract val sessionId: String
    abstract val timestampMs: Long

    data class PairResponse(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val accepted: Boolean,
        val deviceInstanceId: String
    ) : IncomingMessage()

    data class SessionAck(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val ok: Boolean
    ) : IncomingMessage()

    data class RangingSample(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val distanceMm: Int,
        val quality: Float,
        val rssiDbm: Int,
        val sequence: Long,
        val anchorId: String
    ) : IncomingMessage()

    data class Heartbeat(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val status: String
    ) : IncomingMessage()

    data class CoverageUpdate(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val segments: List<CoverageSegmentPayload>
    ) : IncomingMessage()

    data class ErrorMessage(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val code: Int,
        val name: String,
        val detail: String,
        val failedMessageId: Long
    ) : IncomingMessage()

    data class Unknown(
        override val messageId: Long,
        override val sessionId: String,
        override val timestampMs: Long,
        val rawMessageType: String
    ) : IncomingMessage()
}

data class CoverageSegmentPayload(
    val fromXMm: Int,
    val fromYMm: Int,
    val toXMm: Int,
    val toYMm: Int
)
