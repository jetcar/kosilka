package com.kosilka.data.device.remote

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.TransportModeStore
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.UwbTag
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class RemoteServiceApiClient @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
    private val transportModeStore: TransportModeStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun connect(): Result<String?> = runCatching {
        val response = request("POST", "/api/v1/device/connect", null)
        ensureSuccess(response)
        parseSessionId(response.body)
    }

    suspend fun disconnect(): Result<Unit> = runCatching {
        val response = request("POST", "/api/v1/device/disconnect", null)
        ensureSuccess(response)
        Unit
    }

    suspend fun sendEnvelope(envelopeJson: String): Result<Unit> = runCatching {
        val response = request("POST", "/api/v1/device/send", envelopeJson)
        ensureSuccess(response)
        Unit
    }

    suspend fun pollMessages(sinceMessageId: Long?): Result<List<String>> = runCatching {
        val query = sinceMessageId?.let { "?sinceId=$it" } ?: ""
        val response = request("GET", "/api/v1/device/messages$query", null)
        ensureSuccess(response)
        parseMessages(response.body)
    }

    suspend fun getUwbTags(): Result<List<UwbTag>> = runCatching {
        val response = request("GET", "/api/v1/ui/uwb-tags", null)
        ensureSuccess(response)
        parseUwbTags(response.body)
    }

    suspend fun toggleUwbTag(tagId: String): Result<Unit> = runCatching {
        val response = request("POST", "/api/v1/ui/uwb-tags/${tagId}/toggle", "{}")
        ensureSuccess(response)
        Unit
    }

    suspend fun readCurrentPosition(): Result<Point2dMm> = runCatching {
        val response = request("GET", "/api/v1/emulator/state", null)
        ensureSuccess(response)
        parsePosition(response.body)
    }

    private suspend fun request(method: String, pathAndQuery: String, body: String?): HttpResponse =
        withContext(dispatchers.io) {
            val baseUrl = transportModeStore.currentServiceEndpoint()
            val connection = (URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            try {
                if (body != null) {
                    OutputStreamWriter(connection.outputStream).use { writer -> writer.write(body) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                HttpResponse(code = code, body = responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private fun ensureSuccess(response: HttpResponse) {
        if (response.code !in 200..299) {
            throw IllegalStateException("Remote mower service call failed with HTTP ${response.code}: ${response.body}")
        }
    }

    private fun parseSessionId(body: String): String? {
        if (body.isBlank()) {
            return null
        }

        val root = json.parseToJsonElement(body)
        return (root as? JsonObject)
            ?.get("sessionId")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private fun parseMessages(body: String): List<String> {
        if (body.isBlank()) {
            return emptyList()
        }

        val root = json.parseToJsonElement(body)
        return when (root) {
            is JsonArray -> root.map { it.toString() }
            is JsonObject -> {
                val messages = root["messages"]
                if (messages is JsonArray) {
                    messages.map { it.toString() }
                } else {
                    listOf(root.toString())
                }
            }
            else -> emptyList()
        }
    }

    private fun parseUwbTags(body: String): List<UwbTag> {
        if (body.isBlank()) return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["uwbTags"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            UwbTag(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                xMm = obj["xMm"]?.jsonPrimitive?.intOrNull ?: 0,
                yMm = obj["yMm"]?.jsonPrimitive?.intOrNull ?: 0,
                label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "",
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                maxRangeMm = obj["maxRangeMm"]?.jsonPrimitive?.intOrNull ?: 8000
            )
        }
    }

    private fun parsePosition(body: String): Point2dMm {
        if (body.isBlank()) {
            throw IllegalStateException("Missing emulator state body")
        }

        val root = json.parseToJsonElement(body).jsonObject
        val position = root["position"]?.jsonObject
            ?: throw IllegalStateException("Missing position in emulator state")
        val xMm = position["xMm"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalStateException("Missing xMm in emulator state")
        val yMm = position["yMm"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalStateException("Missing yMm in emulator state")
        return Point2dMm(xMm = xMm, yMm = yMm)
    }
}

private data class HttpResponse(
    val code: Int,
    val body: String
)
