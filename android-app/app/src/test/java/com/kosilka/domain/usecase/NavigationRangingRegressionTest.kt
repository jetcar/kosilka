package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.Point2dMm
import com.kosilka.testing.EmulatorContainerSupport
import com.kosilka.testing.RestEmulatorMowerDevice
import kotlinx.coroutines.delay
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
    fun `emulator emits ranging samples from placed uwb tags and position is computed`() = runBlocking {
        // Place 3 UWB tags and put the mower at a known position — the emulator must emit
        // RANGING_SAMPLE for each enabled tag; StartRangingUseCase must compute position via trilateration.
        val mowerPosition = Point2dMm(2500, 2000)
        val baseUrl = EmulatorContainerSupport.prepareTestMap(
            mowerPosition = mowerPosition,
            availableZones = listOf(EmulatorContainerSupport.availableZone()),
            noGoZones = emptyList()
        )
        EmulatorContainerSupport.addUwbTags(
            baseUrl = baseUrl,
            tags = listOf(
                Triple("Tag 1", Point2dMm(0, 0), 10000),
                Triple("Tag 2", Point2dMm(5000, 0), 10000),
                Triple("Tag 3", Point2dMm(2500, 4000), 10000)
            )
        )

        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(200L)

        val sessionId = device.sessionId()
        val rangingUseCase = StartRangingUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        // Pass 3 dummy anchors so the ≥3 check passes; real positions arrive via ANCHOR_CONFIG
        val startResult = rangingUseCase.start(
            sessionId = sessionId,
            sampleRateHz = 5,
            anchorsById = mapOf(
                "placeholder-1" to Point2dMm(0, 0),
                "placeholder-2" to Point2dMm(5000, 0),
                "placeholder-3" to Point2dMm(2500, 4000)
            )
        )
        assertTrue(startResult.isSuccess)
        assertTrue(rangingUseCase.state.value.isRangingActive)

        // Wait for ANCHOR_CONFIG + a few ranging ticks (5 Hz → samples every 200 ms)
        delay(1500L)

        val latest = rangingUseCase.state.value.latestPosition
        assertNotNull("Expected a computed position but got null", latest)
        // Trilateration should resolve close to (2500, 2000) — allow ±300 mm noise
        assertTrue(
            "xMm=${latest!!.xMm} not near 2500",
            latest.xMm in (mowerPosition.xMm - 300)..(mowerPosition.xMm + 300)
        )
        assertTrue(
            "yMm=${latest.yMm} not near 2000",
            latest.yMm in (mowerPosition.yMm - 300)..(mowerPosition.yMm + 300)
        )

        rangingUseCase.stop()
        device.disconnect()
    }

    @Test
    fun `mover moves to target when three uwb tags are present`() = runBlocking {
        val startPosition = Point2dMm(1500, 1500)
        val target = Point2dMm(3000, 2000)
        val baseUrl = EmulatorContainerSupport.prepareTestMap(
            mowerPosition = startPosition,
            availableZones = listOf(EmulatorContainerSupport.availableZone()),
            noGoZones = emptyList(),
            speedMmPerSec = 1500,
            rotationSpeedDegPerSec = 720
        )
        EmulatorContainerSupport.addUwbTags(
            baseUrl = baseUrl,
            tags = listOf(
                Triple("Tag 1", Point2dMm(0, 0), 10000),
                Triple("Tag 2", Point2dMm(7000, 0), 10000),
                Triple("Tag 3", Point2dMm(3500, 5000), 10000)
            )
        )

        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(300L)

        val sessionId = device.sessionId()
        val moveUseCase = MoveMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers()
        )

        val result = moveUseCase.moveTo(sessionId = sessionId, target = target, zone = null)
        assertTrue("Expected Success but got $result", result is MoveMowerResult.Success)

        // Wait for emulator to actually move the mower
        val toleranceMm = 150
        val settled = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            while (true) {
                val pos = device.readCurrentPosition().getOrThrow()
                if (pos.xMm in (target.xMm - toleranceMm)..(target.xMm + toleranceMm) &&
                    pos.yMm in (target.yMm - toleranceMm)..(target.yMm + toleranceMm)) {
                    return@withTimeoutOrNull pos
                }
                delay(100L)
            }
            null
        }
        assertNotNull("Mower did not reach target within 5s", settled)

        device.disconnect()
    }

    @Test
    fun `mower does not move without uwb tags`() = runBlocking {
        val baseUrl = EmulatorContainerSupport.prepareTestMap(
            mowerPosition = Point2dMm(1500, 1500),
            availableZones = listOf(EmulatorContainerSupport.availableZone()),
            noGoZones = emptyList()
        )
        // prepareTestMap clears all uwbTags — mower should refuse to move
        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        val moveUseCase = MoveMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers()
        )
        device.connect()
        delay(200L)

        val sessionId = "uwb-gating-test"
        val target = Point2dMm(3000, 2000)
        val result = moveUseCase.moveTo(sessionId = sessionId, target = target, zone = null)

        // MOVE_TO is accepted at transport level (no ERR_BUSY), but emulator blocks movement
        assertTrue(result is MoveMowerResult.Success)

        // Position must not have changed — mower blocked by insufficient UWB tags
        delay(300L)
        val position = device.readCurrentPosition().getOrThrow()
        assertTrue(
            "Expected mower to stay near (1500,1500) but was (${position.xMm},${position.yMm})",
            position.xMm in 1200..1800 && position.yMm in 1200..1800
        )

        device.disconnect()
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
