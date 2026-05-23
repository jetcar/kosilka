package com.kosilka.domain.usecase

import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.data.local.dao.ScheduleDao
import com.kosilka.data.local.mapper.toDomain
import com.kosilka.data.local.mapper.toEntity
import com.kosilka.domain.model.Schedule
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ManageScheduleUseCase @Inject constructor(
    private val mowerDevice: MowerDevice,
    private val scheduleDao: ScheduleDao,
    private val messageIdGenerator: MessageIdGenerator
) {
    fun observeSchedules(): Flow<List<Schedule>> =
        scheduleDao.getAllSchedules().map { entities -> entities.map { it.toDomain() } }

    suspend fun createSchedule(
        sessionId: String?,
        startTimeUtcHhmm: String,
        daysOfWeek: List<Int>,
        zoneId: String?
    ): ScheduleResult {
        if (!isValidTime(startTimeUtcHhmm)) {
            return ScheduleResult.Invalid("startTimeUtcHhmm must be HH:MM")
        }
        if (daysOfWeek.isEmpty() || daysOfWeek.any { it !in 0..6 }) {
            return ScheduleResult.Invalid("daysOfWeek must contain one or more values in 0..6")
        }

        val schedule = Schedule(
            scheduleId = "sched-${UUID.randomUUID()}",
            startTimeUtcHhmm = startTimeUtcHhmm,
            daysOfWeek = daysOfWeek,
            zoneId = zoneId,
            isDeleted = false,
            pendingSync = sessionId == null
        )

        if (sessionId != null) {
            val send = mowerDevice.send(toScheduleSetEnvelope(schedule, sessionId, deleted = false))
            if (send.isFailure) {
                scheduleDao.upsertSchedule(schedule.copy(pendingSync = true).toEntity())
                return ScheduleResult.QueuedOffline(schedule.scheduleId)
            }
            scheduleDao.upsertSchedule(schedule.copy(pendingSync = false).toEntity())
            return ScheduleResult.Success(schedule.scheduleId)
        }

        scheduleDao.upsertSchedule(schedule.toEntity())
        return ScheduleResult.QueuedOffline(schedule.scheduleId)
    }

    suspend fun deleteSchedule(sessionId: String?, scheduleId: String): ScheduleResult {
        if (sessionId != null) {
            val send = mowerDevice.send(
                Envelope(
                    protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                    messageType = ProtocolConstants.TYPE_SCHEDULE_SET,
                    messageId = messageIdGenerator.next(),
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    payload = mapOf(
                        "scheduleId" to scheduleId,
                        "deleted" to true
                    )
                )
            )
            if (send.isSuccess) {
                scheduleDao.deleteSchedule(scheduleId)
                return ScheduleResult.Success(scheduleId)
            }
        }

        scheduleDao.markDeletedPending(scheduleId)
        return ScheduleResult.QueuedOffline(scheduleId)
    }

    private fun toScheduleSetEnvelope(schedule: Schedule, sessionId: String, deleted: Boolean): Envelope {
        return Envelope(
            protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
            messageType = ProtocolConstants.TYPE_SCHEDULE_SET,
            messageId = messageIdGenerator.next(),
            sessionId = sessionId,
            timestampMs = System.currentTimeMillis(),
            payload = mapOf(
                "scheduleId" to schedule.scheduleId,
                "startTimeUtcHhmm" to schedule.startTimeUtcHhmm,
                "daysOfWeek" to schedule.daysOfWeek,
                "zoneId" to schedule.zoneId,
                "deleted" to deleted
            )
        )
    }

    private fun isValidTime(time: String): Boolean {
        val parts = time.split(":")
        if (parts.size != 2) return false
        val hh = parts[0].toIntOrNull() ?: return false
        val mm = parts[1].toIntOrNull() ?: return false
        return hh in 0..23 && mm in 0..59
    }
}

sealed class ScheduleResult {
    data class Success(val scheduleId: String) : ScheduleResult()
    data class QueuedOffline(val scheduleId: String) : ScheduleResult()
    data class Invalid(val reason: String) : ScheduleResult()
}
