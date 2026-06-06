package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.CoverageSegmentPayload
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.local.dao.CoverageDao
import com.kosilka.data.local.entity.CoverageSegmentEntity
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCoverageUseCaseIntegrationTest {

    @Test
    fun `26_6 coverage overlay clears when new session starts`() = runBlocking {
        val dao = FakeCoverageDao()
        val device = FakeCoverageDevice()
        val useCase = TrackCoverageUseCase(
            mowerDevice = device,
            coverageDao = dao,
            dispatchers = CoroutineDispatchers()
        )

        useCase.startSession("s1", zone = null)
        device.emitCoverage("s1", 0, 0, 100, 100)
        withTimeout(10_000L) { while (useCase.state.value.segments.isEmpty()) delay(50L) }
        assertTrue(useCase.state.value.segments.isNotEmpty())

        useCase.startSession("s2", zone = null)
        withTimeout(10_000L) { while (useCase.state.value.segments.isNotEmpty()) delay(50L) }

        assertEquals("s2", useCase.state.value.sessionId)
        assertTrue(useCase.state.value.segments.isEmpty())
    }
}

private class FakeCoverageDevice : MowerDevice {
    private val connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 1)
    private val incomingFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 16)

    override val connectionEvents: Flow<ConnectionEvent> = connectionFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        connectionFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> = Result.success(Unit)

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return Result.success(Point2dMm(0, 0))
    }

    suspend fun emitCoverage(sessionId: String, fx: Int, fy: Int, tx: Int, ty: Int) {
        incomingFlow.emit(
            IncomingMessage.CoverageUpdate(
                messageId = 1,
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                segments = listOf(
                    CoverageSegmentPayload(
                        fromXMm = fx,
                        fromYMm = fy,
                        toXMm = tx,
                        toYMm = ty
                    )
                )
            )
        )
    }
}

private class FakeCoverageDao : CoverageDao {
    private val bySession = mutableMapOf<String, MutableStateFlow<List<CoverageSegmentEntity>>>()

    override suspend fun insertSegments(segments: List<CoverageSegmentEntity>) {
        segments.groupBy { it.sessionId }.forEach { (sessionId, group) ->
            val flow = bySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }
            flow.value = flow.value + group
        }
    }

    override fun getSegmentsForSession(sessionId: String): Flow<List<CoverageSegmentEntity>> {
        return bySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.map { it }
    }

    override suspend fun deleteSegmentsForSession(sessionId: String) {
        bySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value = emptyList()
    }
}
