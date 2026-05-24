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

    fun observeZone(zoneId: String = AVAILABLE_ZONE_ID): Flow<Zone?> =
        zoneDao.getZoneById(zoneId).map { it?.toDomain() }

    fun observeAvailableZone(): Flow<Zone?> = observeZone(AVAILABLE_ZONE_ID)

    fun observeNoGoZone(): Flow<Zone?> = observeZone(NO_GO_ZONE_ID)

    fun observeZonesByPrefix(prefix: String): Flow<List<Zone>> {
        return zoneDao.getZonesByPrefix(prefix).map { entities -> entities.map { it.toDomain() } }
    }

    fun observeAvailableZones(): Flow<List<Zone>> = observeZonesByPrefix(AVAILABLE_ZONE_PREFIX)

    fun observeNoGoZones(): Flow<List<Zone>> = observeZonesByPrefix(NO_GO_ZONE_PREFIX)

    suspend fun defineZone(
        sessionId: String,
        vertices: List<Point2dMm>,
        zoneId: String = AVAILABLE_ZONE_ID
    ): DefineZoneResult {
        if (vertices.size < 3) {
            return DefineZoneResult.Invalid("Zone requires at least 3 vertices")
        }

        val previousZone = zoneDao.getZoneById(zoneId).first()?.toDomain()
        val nextZone = Zone(id = previousZone?.id ?: zoneId, vertices = vertices)

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

    suspend fun defineNoGoZone(sessionId: String, vertices: List<Point2dMm>): DefineZoneResult {
        return defineZone(sessionId = sessionId, vertices = vertices, zoneId = NO_GO_ZONE_ID)
    }

    suspend fun defineAvailableZone(sessionId: String, zoneIndex: Int, vertices: List<Point2dMm>): DefineZoneResult {
        val zoneId = "$AVAILABLE_ZONE_PREFIX$zoneIndex"
        return defineZone(sessionId = sessionId, vertices = vertices, zoneId = zoneId)
    }

    suspend fun defineNoGoZone(sessionId: String, zoneIndex: Int, vertices: List<Point2dMm>): DefineZoneResult {
        val zoneId = "$NO_GO_ZONE_PREFIX$zoneIndex"
        return defineZone(sessionId = sessionId, vertices = vertices, zoneId = zoneId)
    }

    suspend fun saveAvailableZonesLocally(zones: List<List<Point2dMm>>) {
        saveZonesLocally(prefix = AVAILABLE_ZONE_PREFIX, zones = zones)
    }

    suspend fun saveNoGoZonesLocally(zones: List<List<Point2dMm>>) {
        saveZonesLocally(prefix = NO_GO_ZONE_PREFIX, zones = zones)
    }

    suspend fun clearZone(zoneId: String = AVAILABLE_ZONE_ID) {
        zoneDao.getZoneById(zoneId).first()?.let { existing ->
            zoneDao.deleteZone(existing.id)
        }
    }

    suspend fun clearNoGoZone() {
        clearZone(NO_GO_ZONE_ID)
    }

    suspend fun clearZonesByPrefix(prefix: String) {
        zoneDao.deleteZonesByPrefix(prefix)
    }

    suspend fun getZoneIdsByPrefix(prefix: String): List<String> {
        return zoneDao.getZoneIdsByPrefix(prefix)
    }

    suspend fun deleteZoneById(zoneId: String) {
        zoneDao.deleteZone(zoneId)
    }

    private suspend fun saveZonesLocally(prefix: String, zones: List<List<Point2dMm>>) {
        zones.forEachIndexed { index, vertices ->
            if (vertices.size >= 3) {
                val zone = Zone(id = "$prefix$index", vertices = vertices)
                zoneDao.upsertZone(zone.toEntity())
            }
        }

        val validIds = zones.indices.map { "$prefix$it" }.toSet()
        zoneDao.getZoneIdsByPrefix(prefix)
            .filter { it !in validIds }
            .forEach { staleId ->
                zoneDao.deleteZone(staleId)
            }
    }

    private companion object {
        const val AVAILABLE_ZONE_ID = "zone-main"
        const val NO_GO_ZONE_ID = "zone-no-go"
        const val AVAILABLE_ZONE_PREFIX = "zone-available-"
        const val NO_GO_ZONE_PREFIX = "zone-no-go-"
    }
}

sealed class DefineZoneResult {
    data class Success(val zone: Zone) : DefineZoneResult()
    data class Invalid(val reason: String) : DefineZoneResult()
    data class DeliveryFailed(val reason: String) : DefineZoneResult()
    data class FirmwareError(val code: Int, val name: String, val detail: String) : DefineZoneResult()
}
