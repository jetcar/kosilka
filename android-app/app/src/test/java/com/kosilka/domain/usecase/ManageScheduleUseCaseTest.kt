package com.kosilka.domain.usecase

import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.local.dao.ScheduleDao
import com.kosilka.data.local.entity.ScheduleEntity
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

class ManageScheduleUseCaseTest {

    @Test
    fun `Property 15 - SCHEDULE_SET Message Schema`() = runBlocking {
        val fakeDao = FakeScheduleDao()
        val fakeDevice = CapturingScheduleDevice()
        val useCase = ManageScheduleUseCase(
            mowerDevice = fakeDevice,
            scheduleDao = fakeDao,
            messageIdGenerator = MessageIdGenerator()
        )

        val result = useCase.createSchedule(
            sessionId = "session-1",
            startTimeUtcHhmm = "07:30",
            daysOfWeek = listOf(1, 3, 5),
            zoneId = "zone-main"
        )

        assertTrue(result is ScheduleResult.Success)
        val sent = fakeDevice.lastEnvelope
        requireNotNull(sent)
        assertEquals("SCHEDULE_SET", sent.messageType)

        assertTrue(sent.payload.containsKey("scheduleId"))
        assertEquals("07:30", sent.payload["startTimeUtcHhmm"])
        assertEquals(listOf(1, 3, 5), sent.payload["daysOfWeek"])
        assertEquals("zone-main", sent.payload["zoneId"])
        assertEquals(false, sent.payload["deleted"])
    }
}

private class CapturingScheduleDevice : MowerDevice {
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

private class FakeScheduleDao : ScheduleDao {
    private val state = MutableStateFlow<List<ScheduleEntity>>(emptyList())

    override suspend fun upsertSchedule(schedule: ScheduleEntity) {
        val next = state.value.filterNot { it.scheduleId == schedule.scheduleId } + schedule
        state.value = next
    }

    override fun getAllSchedules() = state.asStateFlow()

    override suspend fun getPendingSyncSchedules(): List<ScheduleEntity> =
        state.value.filter { it.pendingSync }

    override suspend fun deleteSchedule(scheduleId: String) {
        state.value = state.value.filterNot { it.scheduleId == scheduleId }
    }

    override suspend fun markDeletedPending(scheduleId: String) {
        state.value = state.value.map {
            if (it.scheduleId == scheduleId) it.copy(isDeleted = true, pendingSync = true) else it
        }
    }

    override suspend fun markSynced(scheduleId: String) {
        state.value = state.value.map {
            if (it.scheduleId == scheduleId) it.copy(pendingSync = false) else it
        }
    }
}
