package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRangingRegressionTest {

    @Test
    fun `start ranging requires at least three anchors`() = runBlocking {
        val fakeDevice = FakeNavigationMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val result = useCase.start(
            sessionId = "session-1",
            sampleRateHz = 5,
            anchorsById = mapOf(
                "a1" to Point2dMm(0, 0),
                "a2" to Point2dMm(3000, 0)
            )
        )

        assertTrue(result.isFailure)
        assertEquals(0, fakeDevice.sentEnvelopes.size)
    }

    @Test
    fun `start ranging activates state and computes position from valid samples`() = runBlocking {
        val fakeDevice = FakeNavigationMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val anchors = mapOf(
            "a1" to Point2dMm(0, 0),
            "a2" to Point2dMm(5000, 0),
            "a3" to Point2dMm(2500, 4000)
        )

        val startResult = useCase.start(
            sessionId = "session-2",
            sampleRateHz = 5,
            anchorsById = anchors
        )

        assertTrue(startResult.isSuccess)
        assertTrue(useCase.state.value.isRangingActive)

        // Distances are generated for target position (2500, 2000).
        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 11,
                sessionId = "session-2",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 3202,
                quality = 0.95f,
                rssiDbm = -60,
                sequence = 1,
                anchorId = "a1"
            )
        )
        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 12,
                sessionId = "session-2",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 3202,
                quality = 0.95f,
                rssiDbm = -60,
                sequence = 2,
                anchorId = "a2"
            )
        )
        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 13,
                sessionId = "session-2",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 2000,
                quality = 0.95f,
                rssiDbm = -60,
                sequence = 3,
                anchorId = "a3"
            )
        )

        // Allow collector coroutine to process messages.
        kotlinx.coroutines.delay(120)

        val latest = useCase.state.value.latestPosition
        assertNotNull(latest)
        assertTrue(latest!!.xMm in 2450..2550)
        assertTrue(latest.yMm in 1950..2050)

        useCase.stop()
        assertTrue(!useCase.state.value.isRangingActive)
    }

    @Test
    fun `stop ranging sends ranging stop and deactivates state`() = runBlocking {
        val fakeDevice = FakeNavigationMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "session-3",
            sampleRateHz = 5,
            anchorsById = mapOf(
                "a1" to Point2dMm(0, 0),
                "a2" to Point2dMm(5000, 0),
                "a3" to Point2dMm(2500, 4000)
            )
        )
        assertTrue(startResult.isSuccess)
        assertTrue(useCase.state.value.isRangingActive)

        useCase.stop()

        assertTrue(!useCase.state.value.isRangingActive)
        val lastSent = fakeDevice.sentEnvelopes.lastOrNull()
        assertNotNull(lastSent)
        assertEquals(ProtocolConstants.TYPE_RANGING_STOP, lastSent!!.messageType)
    }
}

private class FakeNavigationMowerDevice : MowerDevice {
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1)
    private val incomingMessagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 32)

    val sentEnvelopes = mutableListOf<Envelope>()

    override val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingMessagesFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        sentEnvelopes += envelope
        return Result.success(Unit)
    }

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return Result.success(Point2dMm(0, 0))
    }

    suspend fun emitIncoming(message: IncomingMessage) {
        incomingMessagesFlow.emit(message)
    }
}
