package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveMowerUseCaseTest {

    @Test
    fun `Property 8 - Out-of-Zone MOVE_TO Blocking`() = runBlocking {
        val fakeDevice = FakeMowerDevice()
        val useCase = MoveMowerUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers()
        )

        val zone = Zone(
            id = "z1",
            vertices = listOf(
                Point2dMm(0, 0),
                Point2dMm(1000, 0),
                Point2dMm(1000, 1000),
                Point2dMm(0, 1000)
            )
        )

        val result = useCase.moveTo(
            sessionId = "session-1",
            target = Point2dMm(1500, 1500),
            zone = zone
        )

        assertTrue(result is MoveMowerResult.OutsideZone)
        assertEquals(0, fakeDevice.sendCount)
    }
}

private class FakeMowerDevice : MowerDevice {
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1)
    private val incomingMessagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 8)

    var sendCount: Int = 0

    override val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingMessagesFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        sendCount += 1
        return Result.success(Unit)
    }
}
