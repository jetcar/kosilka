package com.kosilka.domain.usecase

import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.local.dao.ZoneDao
import com.kosilka.data.local.entity.ZoneEntity
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefineZoneUseCaseTest {

    @Test
    fun `Property 6 - ZONE_SET Message Schema and Vertex Ordering`() = runBlocking {
        val fakeDao = FakeZoneDao()
        val fakeDevice = CapturingMowerDevice()
        val useCase = DefineZoneUseCase(
            mowerDevice = fakeDevice,
            zoneDao = fakeDao,
            messageIdGenerator = MessageIdGenerator()
        )

        val vertices = listOf(
            Point2dMm(100, 200),
            Point2dMm(300, 400),
            Point2dMm(500, 600)
        )

        val result = useCase.defineZone(sessionId = "s1", vertices = vertices)
        assertTrue(result is DefineZoneResult.Success)

        val sent = fakeDevice.lastEnvelope
        requireNotNull(sent)
        assertEquals("ZONE_SET", sent.messageType)

        val payloadVertices = sent.payload["vertices"] as List<*>
        assertEquals(3, payloadVertices.size)

        val first = payloadVertices[0] as Map<*, *>
        val second = payloadVertices[1] as Map<*, *>
        val third = payloadVertices[2] as Map<*, *>

        assertEquals(100, first["xMm"])
        assertEquals(200, first["yMm"])
        assertEquals(300, second["xMm"])
        assertEquals(400, second["yMm"])
        assertEquals(500, third["xMm"])
        assertEquals(600, third["yMm"])
    }
}

private class CapturingMowerDevice : MowerDevice {
    private val connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 1)
    private val incomingFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 8)

    var lastEnvelope: Envelope? = null

    override val connectionEvents: Flow<ConnectionEvent> = connectionFlow.asSharedFlow()
    override val incomingMessages: Flow<IncomingMessage> = incomingFlow.asSharedFlow()

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        connectionFlow.emit(ConnectionEvent.Disconnected)
    }

    override suspend fun send(envelope: Envelope): Result<Unit> {
        lastEnvelope = envelope
        return Result.success(Unit)
    }

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return Result.success(Point2dMm(0, 0))
    }
}

private class FakeZoneDao : ZoneDao {
    private val state = MutableStateFlow<ZoneEntity?>(null)
    private val byPrefix = MutableStateFlow<List<ZoneEntity>>(emptyList())

    override suspend fun upsertZone(zone: ZoneEntity) {
        state.value = zone
        byPrefix.value = listOf(zone)
    }

    override fun getZone() = state.asStateFlow()

    override fun getZoneById(id: String) = MutableStateFlow(state.value?.takeIf { it.id == id }).asStateFlow()

    override fun getZonesByPrefix(prefix: String) =
        MutableStateFlow(byPrefix.value.filter { it.id.startsWith(prefix) }).asStateFlow()

    override suspend fun getZoneIdsByPrefix(prefix: String): List<String> {
        return byPrefix.value.filter { it.id.startsWith(prefix) }.map { it.id }
    }

    override suspend fun deleteZone(id: String) {
        if (state.value?.id == id) {
            state.value = null
        }
        byPrefix.value = byPrefix.value.filterNot { it.id == id }
    }

    override suspend fun deleteZonesByPrefix(prefix: String) {
        byPrefix.value = byPrefix.value.filterNot { it.id.startsWith(prefix) }
        if (state.value?.id?.startsWith(prefix) == true) {
            state.value = null
        }
    }
}
