package com.kosilka.data.device.remote

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolDecoder
import com.kosilka.data.device.protocol.ProtocolEncoder
import com.kosilka.domain.model.Point2dMm
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

@Singleton
class RemoteServiceMowerDevice @Inject constructor(
    private val apiClient: RemoteServiceApiClient,
    private val protocolEncoder: ProtocolEncoder,
    private val protocolDecoder: ProtocolDecoder,
    private val dispatchers: CoroutineDispatchers
) : MowerDevice {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)
    private val incomingMessagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    private val json = Json { ignoreUnknownKeys = true }

    private var pollingJob: Job? = null
    private var connected = false
    private var sessionId: String = "service-session"
    private var lastSeenMessageId: Long? = null

    override val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingMessagesFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> {
        // Reset message cursor for a new transport session. This prevents
        // reconnects from requesting messages with a stale high sinceId.
        lastSeenMessageId = null
        val connectResult = apiClient.connect()
        if (connectResult.isFailure) {
            val throwable = connectResult.exceptionOrNull() ?: IllegalStateException("Failed to connect")
            connectionEventsFlow.emit(ConnectionEvent.Error("Remote service connection failed", throwable))
            return Result.failure(throwable)
        }

        connected = true
        sessionId = connectResult.getOrNull().orEmpty().ifBlank { "service-session-${System.currentTimeMillis()}" }
        connectionEventsFlow.emit(ConnectionEvent.Connected(sessionId))
        startPollingLoop()
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        if (!connected) {
            return
        }

        connected = false
        stopPollingLoop()
        lastSeenMessageId = null
        apiClient.disconnect()
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        if (!connected) {
            return Result.failure(IllegalStateException("Remote service is not connected"))
        }

        val outbound = if (envelope.sessionId.isBlank()) {
            envelope.copy(sessionId = sessionId)
        } else {
            envelope
        }

        val encodedEnvelope = protocolEncoder.encode(outbound)
        val result = apiClient.sendEnvelope(encodedEnvelope)
        if (result.isFailure) {
            val throwable = result.exceptionOrNull() ?: IllegalStateException("Failed to send envelope")
            connectionEventsFlow.emit(ConnectionEvent.Error("Remote service send failed", throwable))
            return Result.failure(throwable)
        }

        return Result.success(Unit)
    }

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        if (!connected) {
            return Result.failure(IllegalStateException("Remote service is not connected"))
        }
        return apiClient.readCurrentPosition()
    }

    private fun startPollingLoop() {
        stopPollingLoop()
        pollingJob = scope.launch {
            while (isActive && connected) {
                val pollResult = apiClient.pollMessages(lastSeenMessageId)
                if (pollResult.isFailure) {
                    val error = pollResult.exceptionOrNull() ?: IllegalStateException("Polling failed")
                    connectionEventsFlow.emit(ConnectionEvent.Error("Remote service polling failed", error))
                    delay(1_000L)
                    continue
                }

                pollResult.getOrNull().orEmpty().forEach { rawEnvelope ->
                    protocolDecoder.decode(rawEnvelope)?.let { message ->
                        lastSeenMessageId = maxOf(lastSeenMessageId ?: Long.MIN_VALUE, message.messageId)
                        incomingMessagesFlow.emit(message)
                    } ?: run {
                        extractMessageId(rawEnvelope)?.let { parsedId ->
                            lastSeenMessageId = maxOf(lastSeenMessageId ?: Long.MIN_VALUE, parsedId)
                        }
                    }
                }

                delay(300L)
            }
        }
    }

    private fun stopPollingLoop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun extractMessageId(rawEnvelope: String): Long? {
        return runCatching {
            json.parseToJsonElement(rawEnvelope).jsonObject["messageId"]?.jsonPrimitive?.long
        }.getOrNull()
    }
}
