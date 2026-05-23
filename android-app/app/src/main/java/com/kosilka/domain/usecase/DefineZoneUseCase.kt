package com.kosilka.domain.usecase

import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.data.local.dao.ZoneDao
import com.kosilka.data.local.mapper.toDomain
import com.kosilka.data.local.mapper.toEntity
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class DefineZoneUseCase @Inject constructor(
    private val mowerDevice: MowerDevice,
    private val zoneDao: ZoneDao,
    private val messageIdGenerator: MessageIdGenerator
) {

    fun observeZone(): Flow<Zone?> = zoneDao.getZone().map { it?.toDomain() }

    suspend fun defineZone(
        sessionId: String,
        vertices: List<Point2dMm>
    ): DefineZoneResult {
        if (vertices.size < 3) {
            return DefineZoneResult.Invalid("Zone requires at least 3 vertices")
        }

        val previousZone = zoneDao.getZone().first()?.toDomain()
        val nextZone = Zone(id = previousZone?.id ?: DEFAULT_ZONE_ID, vertices = vertices)

        val messageId = messageIdGenerator.next()
        val sendResult = mowerDevice.send(
            Envelope(
                protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                messageType = ProtocolConstants.TYPE_ZONE_SET,
                messageId = messageId,
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                payload = mapOf(
                    "zoneId" to nextZone.id,
                    "vertices" to vertices.map { vertex ->
                        mapOf("xMm" to vertex.xMm, "yMm" to vertex.yMm)
                    }
                )
            )
        )
        if (sendResult.isFailure) {
            return DefineZoneResult.DeliveryFailed(
                sendResult.exceptionOrNull()?.message ?: "ZONE_SET delivery failed"
            )
        }

        val error = withTimeoutOrNull(800L) {
            mowerDevice.incomingMessages
                .filterIsInstance<IncomingMessage.ErrorMessage>()
                .first { it.failedMessageId == messageId }
        }

        if (error != null) {
            return DefineZoneResult.FirmwareError(error.code, error.name, error.detail)
        }

        zoneDao.upsertZone(nextZone.toEntity())
        return DefineZoneResult.Success(nextZone)
    }

    suspend fun clearZone() {
        zoneDao.getZone().first()?.let { existing ->
            zoneDao.deleteZone(existing.id)
        }
    }

    private companion object {
        const val DEFAULT_ZONE_ID = "zone-main"
    }
}

sealed class DefineZoneResult {
    data class Success(val zone: Zone) : DefineZoneResult()
    data class Invalid(val reason: String) : DefineZoneResult()
    data class DeliveryFailed(val reason: String) : DefineZoneResult()
    data class FirmwareError(val code: Int, val name: String, val detail: String) : DefineZoneResult()
}
