package com.kosilka.testing

import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.AnchorInfo
import com.kosilka.data.device.protocol.CoverageSegmentPayload
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object EmulatorContainerSupport {
    private val lock = Any()
    private var resolvedBaseUrl: String? = null
    private const val DefaultBaseUrl = "http://localhost:8080"
    private const val BaseUrlProperty = "emulator.baseUrl"
    private const val BaseUrlEnv = "EMULATOR_BASE_URL"
    private val DefaultMowerPosition = Point2dMm(1500, 1500)
    private val AvailableZoneVertices = listOf(
        Point2dMm(1000, 500),
        Point2dMm(7000, 500),
        Point2dMm(7000, 5000),
        Point2dMm(1000, 5000)
    )
    private val TopMiddleNoGoZoneVertices = listOf(
        Point2dMm(3400, 3200),
        Point2dMm(4600, 3200),
        Point2dMm(4600, 4300),
        Point2dMm(3400, 4300)
    )
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun availableZone(): Zone = Zone(id = "zone-available-0", vertices = AvailableZoneVertices)

    fun topMiddleNoGoZone(): Zone = Zone(id = "zone-no-go-top-middle-0", vertices = TopMiddleNoGoZoneVertices)

    fun defaultMowerPosition(): Point2dMm = DefaultMowerPosition

    fun prepareTestMap(
        mowerPosition: Point2dMm,
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        speedMmPerSec: Int? = null,
        rotationSpeedDegPerSec: Int? = null
    ): String {
        synchronized(lock) {
            val baseUrl = resolveBaseUrl()
            verifyEmulatorReachable(baseUrl)
            resetUiState(
                baseUrl = baseUrl,
                mowerPosition = mowerPosition,
                availableZones = availableZones,
                noGoZones = noGoZones,
                speedMmPerSec = speedMmPerSec,
                rotationSpeedDegPerSec = rotationSpeedDegPerSec
            )
            resolvedBaseUrl = baseUrl
            return baseUrl
        }
    }

    fun addUwbTags(baseUrl: String, tags: List<Triple<String, Point2dMm, Int>>) {
        tags.forEach { (label, pos, maxRangeMm) ->
            val body = """{"label":"$label","xMm":${pos.xMm},"yMm":${pos.yMm},"maxRangeMm":$maxRangeMm}"""
            request(baseUrl, "POST", "/api/v1/ui/uwb-tags", body)
        }
    }

    fun baseUrl(): String {
        synchronized(lock) {
            resolvedBaseUrl?.let {
                resetUiState(it)
                return it
            }

            val normalizedBaseUrl = resolveBaseUrl()

            verifyEmulatorReachable(normalizedBaseUrl)
            resetUiState(normalizedBaseUrl)
            resolvedBaseUrl = normalizedBaseUrl
            return normalizedBaseUrl
        }
    }

    private fun resolveBaseUrl(): String {
        val configuredUrl = System.getProperty(BaseUrlProperty)
            ?: System.getenv(BaseUrlEnv)
            ?: DefaultBaseUrl
        return configuredUrl.trimEnd('/')
    }

    private fun verifyEmulatorReachable(baseUrl: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/health"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        val response = runCatching {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrElse { cause ->
            throw IllegalStateException(
                "Cannot reach running emulator at $baseUrl. " +
                    "Start emulator-service or set $BaseUrlProperty/$BaseUrlEnv to a reachable URL.",
                cause
            )
        }

        if (response.statusCode() !in 200..299) {
            error(
                "Running emulator is not healthy at $baseUrl. " +
                    "Set $BaseUrlProperty or $BaseUrlEnv to a valid endpoint. " +
                    "GET /health returned ${response.statusCode()}."
            )
        }
    }

    private fun resetUiState(
        baseUrl: String,
        mowerPosition: Point2dMm = DefaultMowerPosition,
        availableZones: List<Zone> = listOf(availableZone()),
        noGoZones: List<Zone> = listOf(topMiddleNoGoZone()),
        speedMmPerSec: Int? = null,
        rotationSpeedDegPerSec: Int? = null
    ) {
        runCatching { request(baseUrl, "POST", "/api/v1/ui/scenario/clear", "{}") }
        runCatching { request(baseUrl, "DELETE", "/api/v1/ui/command-log", null) }
        runCatching { request(baseUrl, "POST", "/api/v1/ui/navigation/stop", "{}") }
        runCatching { request(baseUrl, "DELETE", "/api/v1/ui/zones", null) }
        runCatching { request(baseUrl, "DELETE", "/api/v1/ui/uwb-tags", null) }
        runCatching {
            request(
                baseUrl,
                "PUT",
                "/api/v1/ui/mower-position",
                """
                    {
                        "xMm": ${mowerPosition.xMm},
                        "yMm": ${mowerPosition.yMm}
                    }
                """.trimIndent()
            )
        }

        availableZones.forEachIndexed { index, zone ->
            request(
                baseUrl,
                "POST",
                "/api/v1/ui/zones",
                zonePayload(zone = zone, areaType = "AVAILABLE", fallbackId = "zone-available-$index")
            )
        }
        noGoZones.forEachIndexed { index, zone ->
            request(
                baseUrl,
                "POST",
                "/api/v1/ui/zones",
                zonePayload(zone = zone, areaType = "NO_GO", fallbackId = "zone-no-go-$index")
            )
        }

        if (speedMmPerSec != null || rotationSpeedDegPerSec != null) {
            request(
                baseUrl,
                "PUT",
                "/api/v1/ui/settings",
                """
                    {
                        "speedMmPerSec": ${speedMmPerSec ?: 200},
                        "rotationSpeedDegPerSec": ${rotationSpeedDegPerSec ?: 360}
                    }
                """.trimIndent()
            )
        }
    }

    private fun zonePayload(zone: Zone, areaType: String, fallbackId: String): String {
        val zoneId = if (zone.id.isBlank()) fallbackId else zone.id
        val verticesJson = zone.vertices.joinToString(",") { vertex ->
            "{\"xMm\":${vertex.xMm},\"yMm\":${vertex.yMm}}"
        }
        return """
            {
              "zoneId":"$zoneId",
              "areaType":"$areaType",
              "vertices":[
                $verticesJson
              ]
            }
        """.trimIndent()
    }

    private fun request(baseUrl: String, method: String, path: String, body: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(5))

        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Request failed: $method $path status=${response.statusCode()} body=${response.body()}")
        }
        return response
    }
}

class RestEmulatorMowerDevice(
    private val baseUrl: String = EmulatorContainerSupport.baseUrl()
) : MowerDevice {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)
    private val incomingFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var lastSeenMessageId: Long = 0L
    private var sessionId: String = ""

    val sentEnvelopes = CopyOnWriteArrayList<Envelope>()

    fun sessionId(): String = sessionId

    override val connectionEvents: Flow<ConnectionEvent> = connectionFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> {
        return runCatching {
            resetUiScenario()
            syncLastSeenMessageId()

            val connectResponse = request("POST", "/api/v1/device/connect", "{}")
            val connectBody = json.parseToJsonElement(connectResponse.body()).jsonObject
            sessionId = connectBody["sessionId"]?.jsonPrimitive?.content.orEmpty()

            startPollingIfNeeded()
            connectionFlow.emit(ConnectionEvent.Connected("rest-emulator"))
        }
    }

    override suspend fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        runCatching { request("POST", "/api/v1/device/disconnect", "{}") }
        connectionFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        sentEnvelopes.add(envelope)
        return runCatching {
            request("POST", "/api/v1/device/send", json.encodeToString(envelope.toJsonElement()))
        }.map { Unit }
    }

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return runCatching {
            val response = request("GET", "/api/v1/ui/state", null)
            val body = json.parseToJsonElement(response.body()).jsonObject
            val position = body["position"]?.jsonObject ?: JsonObject(emptyMap())
            Point2dMm(
                xMm = position["xMm"]?.jsonPrimitive?.int ?: 0,
                yMm = position["yMm"]?.jsonPrimitive?.int ?: 0
            )
        }
    }

    suspend fun activateBusyScenario() {
        request(
            method = "POST",
            path = "/api/v1/ui/scenario/activate",
            body = """
                {"type":"BUSY","durationMs":5000}
            """.trimIndent()
        )
    }

    private suspend fun resetUiScenario() {
        runCatching { request("POST", "/api/v1/ui/scenario/clear", "{}") }
        runCatching { request("DELETE", "/api/v1/ui/command-log", null) }
    }

    private suspend fun syncLastSeenMessageId() {
        val response = request("GET", "/api/v1/device/messages", null)
        val body = json.parseToJsonElement(response.body()).jsonObject
        val messages = body["messages"]?.jsonArray ?: JsonArray(emptyList())
        lastSeenMessageId = messages.maxOfOrNull { it.jsonObject["messageId"]?.jsonPrimitive?.long ?: 0L } ?: 0L
    }

    private fun startPollingIfNeeded() {
        if (pollingJob?.isActive == true) {
            return
        }

        pollingJob = scope.launch {
            while (isActive) {
                val result = runCatching {
                    request("GET", "/api/v1/device/messages?sinceId=$lastSeenMessageId", null)
                }

                result.onSuccess { response ->
                    val body = json.parseToJsonElement(response.body()).jsonObject
                    val messages = body["messages"]?.jsonArray ?: JsonArray(emptyList())
                    messages.forEach { element ->
                        val messageObject = element.jsonObject
                        val messageId = messageObject["messageId"]?.jsonPrimitive?.long ?: lastSeenMessageId
                        if (messageId > lastSeenMessageId) {
                            lastSeenMessageId = messageId
                        }
                        decodeIncoming(messageObject)?.let { incomingFlow.tryEmit(it) }
                    }
                }

                delay(100L)
            }
        }
    }

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(5))

        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Request failed: $method $path status=${response.statusCode()} body=${response.body()}")
        }
        return response
    }

    private fun decodeIncoming(message: JsonObject): IncomingMessage? {
        val messageType = message["messageType"]?.jsonPrimitive?.content ?: return null
        val messageId = message["messageId"]?.jsonPrimitive?.long ?: return null
        val incomingSessionId = message["sessionId"]?.jsonPrimitive?.content ?: ""
        val timestampMs = message["timestampMs"]?.jsonPrimitive?.long ?: 0L
        val payload = message["payload"]?.jsonObject ?: JsonObject(emptyMap())

        return when (messageType) {
            ProtocolConstants.TYPE_PAIR_RESPONSE -> IncomingMessage.PairResponse(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                accepted = payload["accepted"]?.jsonPrimitive?.boolean ?: false,
                deviceInstanceId = payload["deviceInstanceId"]?.jsonPrimitive?.content.orEmpty()
            )

            ProtocolConstants.TYPE_SESSION_ACK -> IncomingMessage.SessionAck(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                ok = payload["ok"]?.jsonPrimitive?.boolean ?: false
            )

            ProtocolConstants.TYPE_HEARTBEAT -> IncomingMessage.Heartbeat(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                status = payload["status"]?.jsonPrimitive?.content.orEmpty()
            )

            ProtocolConstants.TYPE_RANGING_SAMPLE -> IncomingMessage.RangingSample(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                distanceMm = payload["distanceMm"]?.jsonPrimitive?.int ?: 0,
                quality = payload["quality"]?.jsonPrimitive?.float ?: 0f,
                rssiDbm = payload["rssiDbm"]?.jsonPrimitive?.int ?: 0,
                sequence = payload["sequence"]?.jsonPrimitive?.long ?: 0L,
                anchorId = payload["anchorId"]?.jsonPrimitive?.content.orEmpty()
            )

            ProtocolConstants.TYPE_COVERAGE_UPDATE -> IncomingMessage.CoverageUpdate(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                segments = payload["segments"]
                    ?.jsonArray
                    ?.mapNotNull { decodeCoverageSegment(it) }
                    .orEmpty()
            )

            ProtocolConstants.TYPE_ANCHOR_CONFIG -> {
                val anchors = payload["anchors"]?.jsonArray?.mapNotNull { element ->
                    val a = element.jsonObject
                    AnchorInfo(
                        id = a["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        xMm = a["xMm"]?.jsonPrimitive?.int ?: 0,
                        yMm = a["yMm"]?.jsonPrimitive?.int ?: 0,
                        label = a["label"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
                IncomingMessage.AnchorConfig(
                    messageId = messageId,
                    sessionId = incomingSessionId,
                    timestampMs = timestampMs,
                    anchors = anchors
                )
            }

            ProtocolConstants.TYPE_ERROR -> IncomingMessage.ErrorMessage(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                code = payload["code"]?.jsonPrimitive?.int ?: ProtocolConstants.ERR_INTERNAL,
                name = payload["name"]?.jsonPrimitive?.content.orEmpty(),
                detail = payload["detail"]?.jsonPrimitive?.content.orEmpty(),
                failedMessageId = payload["failedMessageId"]?.jsonPrimitive?.long ?: 0L
            )

            else -> IncomingMessage.Unknown(
                messageId = messageId,
                sessionId = incomingSessionId,
                timestampMs = timestampMs,
                rawMessageType = messageType
            )
        }
    }

    private fun decodeCoverageSegment(element: JsonElement): CoverageSegmentPayload? {
        val segment = element.jsonObject
        return CoverageSegmentPayload(
            fromXMm = segment["fromXMm"]?.jsonPrimitive?.int ?: return null,
            fromYMm = segment["fromYMm"]?.jsonPrimitive?.int ?: return null,
            toXMm = segment["toXMm"]?.jsonPrimitive?.int ?: return null,
            toYMm = segment["toYMm"]?.jsonPrimitive?.int ?: return null
        )
    }

    private fun Envelope.toJsonElement(): JsonElement {
        fun anyToElement(value: Any?): JsonElement {
            return when (value) {
                null -> JsonPrimitive(null as String?)
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToElement(v) })
                is List<*> -> JsonArray(value.map { anyToElement(it) })
                else -> JsonPrimitive(value.toString())
            }
        }

        return JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(protocolVersion),
                "messageType" to JsonPrimitive(messageType),
                "messageId" to JsonPrimitive(messageId),
                "sessionId" to JsonPrimitive(sessionId),
                "timestampMs" to JsonPrimitive(timestampMs),
                "payload" to anyToElement(payload)
            )
        )
    }
}
