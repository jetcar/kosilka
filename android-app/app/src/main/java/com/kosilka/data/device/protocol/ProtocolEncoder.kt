package com.kosilka.data.device.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialises outgoing [Envelope] objects to JSON strings.
 * Pure function — no side effects, no I/O.
 */
@Singleton
class ProtocolEncoder @Inject constructor() {

    fun encode(envelope: Envelope): String {
        val json = buildJsonObject {
            put("protocolVersion", envelope.protocolVersion)
            put("messageType", envelope.messageType)
            put("messageId", envelope.messageId)
            put("sessionId", envelope.sessionId)
            put("timestampMs", envelope.timestampMs)
            put("payload", encodePayload(envelope.payload))
        }
        return json.toString()
    }

    private fun encodePayload(payload: Map<String, Any?>): JsonObject = buildJsonObject {
        payload.forEach { (key, value) ->
            when (value) {
                null -> put(key, JsonPrimitive(null as String?))
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> put(key, value)
                is Double -> put(key, value)
                is String -> put(key, value)
                is List<*> -> putJsonArray(key) {
                    value.forEach { item ->
                        when (item) {
                            is Int -> add(JsonPrimitive(item))
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                add(encodePayload(item as Map<String, Any?>))
                            }
                            else -> add(JsonPrimitive(item?.toString()))
                        }
                    }
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    put(key, encodePayload(value as Map<String, Any?>))
                }
                else -> put(key, value.toString())
            }
        }
    }
}
