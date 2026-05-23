package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.core.UiEvent
import com.kosilka.core.UiEventBus
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectMowerUseCaseIntegrationTest {

    @Test
    fun `26_4 connects through handshake and reaches connected state`() = runBlocking {
        val device = FakeHandshakeMowerDevice(emitHeartbeatResponses = true)
        val useCase = ConnectMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            uiEventBus = UiEventBus()
        )

        useCase.connect()
        delay(400L)

        assertTrue(useCase.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun `26_4 heartbeat timeout transitions away from connected`() = runBlocking {
        val device = FakeHandshakeMowerDevice(emitHeartbeatResponses = false)
        val useCase = ConnectMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            uiEventBus = UiEventBus()
        )
        val fakeNowMs = AtomicLong(1_000L)
        useCase.setNowProviderForTest {
            fakeNowMs.addAndGet(1_100L)
        }

        useCase.connect()
        delay(800L)

        val state = useCase.connectionState.value
        assertTrue(state is ConnectionState.Failed || state is ConnectionState.Disconnected)
    }

    @Test
    fun `Property 20 - Error Message Logging Completeness`() = runBlocking {
        val device = FakeHandshakeMowerDevice(emitHeartbeatResponses = true)
        val uiEventBus = UiEventBus()
        val useCase = ConnectMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            uiEventBus = uiEventBus
        )

        useCase.connect()
        delay(400L)

        val sessionId = (useCase.connectionState.value as ConnectionState.Connected).sessionId
        device.emitFirmwareError(
            sessionId = sessionId,
            code = 1003,
            name = "ERR_UNAUTHORIZED",
            failedMessageId = 55L
        )

        val event = withTimeout(1_000L) { uiEventBus.events.first() }
        val snackbar = event as UiEvent.Snackbar

        assertTrue(snackbar.message.contains("ERR_UNAUTHORIZED"))
        assertTrue(snackbar.message.contains("1003"))
        assertEquals(true, snackbar.message.startsWith("Firmware error:"))
    }
}

private class FakeHandshakeMowerDevice(
    private val emitHeartbeatResponses: Boolean
) : MowerDevice {
    private val connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)
    private val incomingFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 32)

    override val connectionEvents: Flow<ConnectionEvent> = connectionFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> {
        connectionFlow.emit(ConnectionEvent.Connected("transport"))
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connectionFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        when (envelope.messageType) {
            ProtocolConstants.TYPE_PAIR_REQUEST -> {
                incomingFlow.emit(
                    IncomingMessage.PairResponse(
                        messageId = envelope.messageId + 100,
                        sessionId = envelope.sessionId,
                        timestampMs = System.currentTimeMillis(),
                        accepted = true,
                        deviceInstanceId = "fake-device"
                    )
                )
            }

            ProtocolConstants.TYPE_SESSION_START -> {
                incomingFlow.emit(
                    IncomingMessage.SessionAck(
                        messageId = envelope.messageId + 100,
                        sessionId = envelope.sessionId,
                        timestampMs = System.currentTimeMillis(),
                        ok = true
                    )
                )
            }

            ProtocolConstants.TYPE_HEARTBEAT -> {
                if (emitHeartbeatResponses) {
                    incomingFlow.emit(
                        IncomingMessage.Heartbeat(
                            messageId = envelope.messageId + 100,
                            sessionId = envelope.sessionId,
                            timestampMs = System.currentTimeMillis(),
                            status = "ok"
                        )
                    )
                }
            }
        }
        return Result.success(Unit)
    }

    suspend fun emitFirmwareError(sessionId: String, code: Int, name: String, failedMessageId: Long) {
        incomingFlow.emit(
            IncomingMessage.ErrorMessage(
                messageId = 999L,
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                code = code,
                name = name,
                detail = "error",
                failedMessageId = failedMessageId
            )
        )
    }
}
