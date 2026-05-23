package com.kosilka.data.device.protocol

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProtocolDecoder"

/**
 * Deserialises raw JSON bytes into typed [IncomingMessage] instances.
 * Validates the envelope schema and rejects unsupported protocol versions.
 * Pure function — no side effects beyond structured logging.
 */
@Singleton
class ProtocolDecoder @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decode a raw JSON string into an [IncomingMessage].
     * Returns [IncomingMessage.Unknown] for unrecognised message types.
     * Returns null if the envelope is malformed or the protocol version is unsupported.
     */
    fun decode(raw: String): IncomingMessage? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val version = root["protocolVersion"]?.jsonPrimitive?.int
                ?: return logAndNull("missing protocolVersion")
            if (version < ProtocolConstants.SUPPORTED_VERSION) {
                Log.e(TAG, "Rejected envelope: protocolVersion=$version < supported=${ProtocolConstants.SUPPORTED_VERSION}")
                return null
            }
            val messageType = root["messageType"]?.jsonPrimitive?.content
                ?: return logAndNull("missing messageType")
            val messageId = root["messageId"]?.jsonPrimitive?.long
                ?: return logAndNull("missing messageId")
            val sessionId = root["sessionId"]?.jsonPrimitive?.content
                ?: return logAndNull("missing sessionId")
            val timestampMs = root["timestampMs"]?.jsonPrimitive?.long
                ?: return logAndNull("missing timestampMs")
            val payload = root["payload"]?.jsonObject
                ?: return logAndNull("missing payload")

            decodePayload(messageType, messageId, sessionId, timestampMs, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode message: ${e.message}")
            null
        }
    }

    private fun decodePayload(
        messageType: String,
        messageId: Long,
        sessionId: String,
        timestampMs: Long,
        payload: JsonObject
    ): IncomingMessage = when (messageType) {
        ProtocolConstants.TYPE_PAIR_RESPONSE -> IncomingMessage.PairResponse(
            messageId = messageId,
            sessionId = sessionId,
            timestampMs = timestampMs,
            accepted = payload["accepted"]?.jsonPrimitive?.boolean ?: false,
            deviceInstanceId = payload["deviceInstanceId"]?.jsonPrimitive?.content ?: ""
        )
        ProtocolConstants.TYPE_SESSION_ACK -> IncomingMessage.SessionAck(
            messageId = messageId,
            sessionId = sessionId,
            timestampMs = timestampMs,
            ok = payload["ok"]?.jsonPrimitive?.boolean ?: false
        )
        ProtocolConstants.TYPE_RANGING_SAMPLE -> IncomingMessage.RangingSample(
            messageId = messageId,
            sessionId = sessionId,
            timestampMs = timestampMs,
            distanceMm = payload["distanceMm"]?.jsonPrimitive?.int ?: 0,
            quality = payload["quality"]?.jsonPrimitive?.float ?: 0f,
            rssiDbm = payload["rssiDbm"]?.jsonPrimitive?.int ?: 0,
            sequence = payload["sequence"]?.jsonPrimitive?.long ?: 0L,
            anchorId = payload["anchorId"]?.jsonPrimitive?.content ?: ""
        )
        ProtocolConstants.TYPE_HEARTBEAT -> IncomingMessage.Heartbeat(
            messageId = messageId,
            sessionId = sessionId,
            timestampMs = timestampMs,
            status = payload["status"]?.jsonPrimitive?.content ?: ""
        )
        ProtocolConstants.TYPE_COVERAGE_UPDATE -> {
            val segments = payload["segments"]?.jsonArray?.map { element ->
                val seg = element.jsonObject
                CoverageSegmentPayload(
                    fromXMm = seg["fromXMm"]?.jsonPrimitive?.int ?: 0,
                    fromYMm = seg["fromYMm"]?.jsonPrimitive?.int ?: 0,
                    toXMm = seg["toXMm"]?.jsonPrimitive?.int ?: 0,
                    toYMm = seg["toYMm"]?.jsonPrimitive?.int ?: 0
                )
            } ?: emptyList()
            IncomingMessage.CoverageUpdate(
                messageId = messageId,
                sessionId = sessionId,
                timestampMs = timestampMs,
                segments = segments
            )
        }
        ProtocolConstants.TYPE_ERROR -> {
            val code = payload["code"]?.jsonPrimitive?.int ?: ProtocolConstants.ERR_INTERNAL
            val name = payload["name"]?.jsonPrimitive?.content ?: "ERR_INTERNAL"
            val detail = payload["detail"]?.jsonPrimitive?.content ?: ""
            val failedId = payload["failedMessageId"]?.jsonPrimitive?.long ?: 0L
            Log.e(TAG, "ERROR code=$code name=$name failedMessageId=$failedId detail=$detail")
            IncomingMessage.ErrorMessage(
                messageId = messageId,
                sessionId = sessionId,
                timestampMs = timestampMs,
                code = code,
                name = name,
                detail = detail,
                failedMessageId = failedId
            )
        }
        else -> IncomingMessage.Unknown(
            messageId = messageId,
            sessionId = sessionId,
            timestampMs = timestampMs,
            rawMessageType = messageType
        )
    }

    private fun logAndNull(reason: String): IncomingMessage? {
        Log.e(TAG, "Malformed envelope: $reason")
        return null
    }
}
