package com.kosilka.data.device.remote

import com.kosilka.BuildConfig
import com.kosilka.core.CoroutineDispatchers
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class RemoteServiceApiClient @Inject constructor(
    private val dispatchers: CoroutineDispatchers
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl: String = BuildConfig.MOWER_SERVICE_BASE_URL.trimEnd('/')

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

    private suspend fun request(method: String, pathAndQuery: String, body: String?): HttpResponse =
        withContext(dispatchers.io) {
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
}

private data class HttpResponse(
    val code: Int,
    val body: String
)
