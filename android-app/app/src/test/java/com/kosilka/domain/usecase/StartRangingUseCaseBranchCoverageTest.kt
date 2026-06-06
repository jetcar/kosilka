package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRangingUseCaseBranchCoverageTest {

    @Test
    fun `start fails when fewer than three anchors are provided`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val result = useCase.start(
            sessionId = "s-too-few-anchors",
            sampleRateHz = 5,
            anchorsById = mapOf(
                "a1" to Point2dMm(0, 0),
                "a2" to Point2dMm(1000, 0)
            )
        )

        assertTrue(result.isFailure)
        assertTrue(!useCase.state.value.isRangingActive)

        useCase.stop()
        assertTrue(!useCase.state.value.isRangingActive)
    }

    @Test
    fun `start failure marks ranging inactive and position lost`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice(sendShouldFail = true)
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "s-fail",
            sampleRateHz = 5,
            anchorsById = anchors()
        )

        assertTrue(startResult.isFailure)
        assertTrue(!useCase.state.value.isRangingActive)
        assertTrue(useCase.state.value.isPositionLost)

        useCase.stop()
    }

    @Test
    fun `low-quality and unknown-anchor samples do not produce position`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "s-filter",
            sampleRateHz = 5,
            anchorsById = anchors()
        )

        assertTrue(startResult.isSuccess)

        // Ensure collector is running before emitting the sample.
        delay(120L)

        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 1,
                sessionId = "s-filter",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 1000,
                quality = 0.2f,
                rssiDbm = -65,
                sequence = 1,
                anchorId = "a1"
            )
        )
        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 2,
                sessionId = "s-filter",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 1200,
                quality = 0.95f,
                rssiDbm = -65,
                sequence = 2,
                anchorId = "unknown-anchor"
            )
        )

        delay(200L)

        assertTrue(useCase.state.value.isRangingActive)
        assertEquals(null, useCase.state.value.latestPosition)
        assertTrue(!useCase.state.value.isPositionLost)

        useCase.stop()
    }

    @Test
    fun `valid three-anchor samples produce position and stop sends ranging stop`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "s-ok",
            sampleRateHz = 5,
            anchorsById = anchors()
        )

        assertTrue(startResult.isSuccess)

        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 11,
                sessionId = "s-ok",
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
                sessionId = "s-ok",
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
                sessionId = "s-ok",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 2000,
                quality = 0.95f,
                rssiDbm = -60,
                sequence = 3,
                anchorId = "a3"
            )
        )

        val latest = waitForLatestPosition(useCase)
        assertNotNull(latest)
        assertTrue(latest!!.xMm in 2450..2550)
        assertTrue(latest.yMm in 1950..2050)

        useCase.stop()

        assertTrue(!useCase.state.value.isRangingActive)
        val lastSent = fakeDevice.sentEnvelopes.lastOrNull()
        assertNotNull(lastSent)
        assertEquals(ProtocolConstants.TYPE_RANGING_STOP, lastSent!!.messageType)
    }

    @Test
    fun `position is marked lost after sample timeout`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "s-timeout",
            sampleRateHz = 5,
            anchorsById = anchors()
        )

        assertTrue(startResult.isSuccess)

        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 21,
                sessionId = "s-timeout",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 3202,
                quality = 0.95f,
                rssiDbm = -60,
                sequence = 1,
                anchorId = "a1"
            )
        )

        withTimeout(5_000L) {
            while (!useCase.state.value.isPositionLost) {
                delay(100L)
            }
        }

        assertTrue(useCase.state.value.isPositionLost)

        useCase.stop()
    }

    @Test
    fun `heartbeat is ignored and does not produce lost position`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "s-heartbeat",
            sampleRateHz = 5,
            anchorsById = anchors()
        )
        assertTrue(startResult.isSuccess)

        delay(120L)
        fakeDevice.emitIncoming(
            IncomingMessage.Heartbeat(
                messageId = 31,
                sessionId = "s-heartbeat",
                timestampMs = System.currentTimeMillis(),
                status = "ok"
            )
        )

        delay(400L)
        assertTrue(useCase.state.value.isRangingActive)
        assertTrue(!useCase.state.value.isPositionLost)
        assertEquals(null, useCase.state.value.latestPosition)

        useCase.stop()
    }

    @Test
    fun `recent sample keeps position from being marked lost`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = useCase.start(
            sessionId = "s-recent-sample",
            sampleRateHz = 5,
            anchorsById = anchors()
        )
        assertTrue(startResult.isSuccess)

        delay(120L)
        fakeDevice.emitIncoming(
            IncomingMessage.RangingSample(
                messageId = 41,
                sessionId = "s-recent-sample",
                timestampMs = System.currentTimeMillis(),
                distanceMm = 3000,
                quality = 0.95f,
                rssiDbm = -60,
                sequence = 1,
                anchorId = "a1"
            )
        )

        delay(700L)
        assertTrue(!useCase.state.value.isPositionLost)

        useCase.stop()
    }

    @Test
    fun `starting again restarts collectors without failing`() = runBlocking {
        val fakeDevice = BranchCoverageMowerDevice()
        val useCase = StartRangingUseCase(
            mowerDevice = fakeDevice,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val first = useCase.start(
            sessionId = "s-restart-1",
            sampleRateHz = 5,
            anchorsById = anchors()
        )
        val second = useCase.start(
            sessionId = "s-restart-2",
            sampleRateHz = 5,
            anchorsById = anchors()
        )

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertTrue(useCase.state.value.isRangingActive)

        useCase.stop()
    }

    private fun anchors(): Map<String, Point2dMm> {
        return mapOf(
            "a1" to Point2dMm(0, 0),
            "a2" to Point2dMm(5000, 0),
            "a3" to Point2dMm(2500, 4000)
        )
    }

    private suspend fun waitForLatestPosition(useCase: StartRangingUseCase) = withTimeout(3_000L) {
        while (useCase.state.value.latestPosition == null) {
            delay(50L)
        }
        useCase.state.value.latestPosition
    }
}

private class BranchCoverageMowerDevice(
    private val sendShouldFail: Boolean = false
) : MowerDevice {
    private val connectionEventsFlow = MutableSharedFlow<ConnectionEvent>(replay = 1)
    private val incomingMessagesFlow = MutableSharedFlow<IncomingMessage>(replay = 32, extraBufferCapacity = 32)

    val sentEnvelopes = mutableListOf<Envelope>()

    override val connectionEvents: Flow<ConnectionEvent> = connectionEventsFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingMessagesFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        connectionEventsFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        sentEnvelopes += envelope
        if (sendShouldFail && envelope.messageType == ProtocolConstants.TYPE_RANGING_START) {
            return Result.failure(IllegalStateException("Injected send failure"))
        }
        return Result.success(Unit)
    }

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return Result.success(Point2dMm(0, 0))
    }

    suspend fun emitIncoming(message: IncomingMessage) {
        incomingMessagesFlow.emit(message)
    }
}
