package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.core.UiEventBus
import com.kosilka.data.device.ConnectionEvent
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.data.local.dao.ScheduleDao
import com.kosilka.data.local.entity.ScheduleEntity
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSyncUseCaseIntegrationTest {

    @Test
    fun `26_5 flushes pending schedules on reconnect`() = runBlocking {
        val scheduleDao = FakePendingScheduleDao()
        scheduleDao.upsertSchedule(
            ScheduleEntity(
                scheduleId = "sched-active",
                startTimeUtcHhmm = "07:30",
                daysOfWeekJson = "[1,3,5]",
                zoneId = "zone-main",
                isDeleted = false,
                pendingSync = true
            )
        )
        scheduleDao.upsertSchedule(
            ScheduleEntity(
                scheduleId = "sched-deleted",
                startTimeUtcHhmm = "09:00",
                daysOfWeekJson = "[2,4]",
                zoneId = null,
                isDeleted = true,
                pendingSync = true
            )
        )

        val device = FakeReconnectMowerDevice()
        val connectMowerUseCase = ConnectMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            uiEventBus = UiEventBus()
        )
        val pendingSyncUseCase = PendingSyncUseCase(
            connectMowerUseCase = connectMowerUseCase,
            scheduleDao = scheduleDao,
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator()
        )

        pendingSyncUseCase.start()
        connectMowerUseCase.connect()
        delay(700L)

        val scheduleMessages = device.sentEnvelopes.filter {
            it.messageType == ProtocolConstants.TYPE_SCHEDULE_SET
        }

        assertEquals(2, scheduleMessages.size)
        assertTrue(scheduleDao.getPendingSyncSchedules().isEmpty())
        assertTrue(scheduleDao.snapshot().none { it.scheduleId == "sched-deleted" })

        val active = scheduleDao.snapshot().first { it.scheduleId == "sched-active" }
        assertEquals(false, active.pendingSync)
    }
}

private class FakeReconnectMowerDevice : MowerDevice {
    private val connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 1, extraBufferCapacity = 8)
    private val incomingFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 32)

    val sentEnvelopes = mutableListOf<Envelope>()

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
        sentEnvelopes.add(envelope)

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

        return Result.success(Unit)
    }

    override suspend fun readCurrentPosition(): Result<Point2dMm> {
        return Result.success(Point2dMm(0, 0))
    }
}

private class FakePendingScheduleDao : ScheduleDao {
    private val state = MutableStateFlow<List<ScheduleEntity>>(emptyList())

    override suspend fun upsertSchedule(schedule: ScheduleEntity) {
        state.value = state.value.filterNot { it.scheduleId == schedule.scheduleId } + schedule
    }

    override fun getAllSchedules(): Flow<List<ScheduleEntity>> = state.asStateFlow()

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

    fun snapshot(): List<ScheduleEntity> = state.value
}
