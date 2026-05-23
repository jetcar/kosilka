package com.kosilka.domain.usecase

import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.data.local.dao.ScheduleDao
import com.kosilka.data.local.mapper.toDomain
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Singleton
class PendingSyncUseCase @Inject constructor(
    private val connectMowerUseCase: ConnectMowerUseCase,
    private val scheduleDao: ScheduleDao,
    private val mowerDevice: MowerDevice,
    private val messageIdGenerator: MessageIdGenerator
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var syncJob: Job? = null

    fun start() {
        if (syncJob?.isActive == true) {
            return
        }

        syncJob = scope.launch {
            connectMowerUseCase.connectionState.collectLatest { state ->
                val connected = state as? ConnectionState.Connected ?: return@collectLatest
                flushPendingSchedules(connected.sessionId)
            }
        }
    }

    private suspend fun flushPendingSchedules(sessionId: String) {
        val pending = scheduleDao.getPendingSyncSchedules()
        for (entity in pending) {
            val model = entity.toDomain()
            val payload = if (model.isDeleted) {
                mapOf(
                    "scheduleId" to model.scheduleId,
                    "deleted" to true
                )
            } else {
                mapOf(
                    "scheduleId" to model.scheduleId,
                    "startTimeUtcHhmm" to model.startTimeUtcHhmm,
                    "daysOfWeek" to model.daysOfWeek,
                    "zoneId" to model.zoneId,
                    "deleted" to false
                )
            }

            val result = mowerDevice.send(
                Envelope(
                    protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                    messageType = ProtocolConstants.TYPE_SCHEDULE_SET,
                    messageId = messageIdGenerator.next(),
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    payload = payload
                )
            )

            if (result.isSuccess) {
                if (model.isDeleted) {
                    scheduleDao.deleteSchedule(model.scheduleId)
                } else {
                    scheduleDao.markSynced(model.scheduleId)
                }
            }
        }
    }
}
