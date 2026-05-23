package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class MoveMowerUseCase @Inject constructor(
    private val mowerDevice: MowerDevice,
    private val messageIdGenerator: MessageIdGenerator,
    private val dispatchers: CoroutineDispatchers
) {

    suspend fun moveTo(
        sessionId: String,
        target: Point2dMm,
        zone: Zone?
    ): MoveMowerResult {
        if (zone != null && !isInsidePolygon(target, zone.vertices)) {
            return MoveMowerResult.OutsideZone
        }

        var lastFailure: Throwable? = null

        repeat(3) {
            val messageId = messageIdGenerator.next()
            val sendResult = mowerDevice.send(
                Envelope(
                    protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                    messageType = ProtocolConstants.TYPE_MOVE_TO,
                    messageId = messageId,
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    payload = mapOf(
                        "targetXMm" to target.xMm,
                        "targetYMm" to target.yMm
                    )
                )
            )

            if (sendResult.isFailure) {
                lastFailure = sendResult.exceptionOrNull()
                delay(250L)
                return@repeat
            }

            val error = withTimeoutOrNull(600L) {
                mowerDevice.incomingMessages
                    .filterIsInstance<IncomingMessage.ErrorMessage>()
                    .first { it.failedMessageId == messageId }
            }

            if (error?.code == ProtocolConstants.ERR_BUSY) {
                return MoveMowerResult.Busy
            }

            return MoveMowerResult.Success
        }

        return MoveMowerResult.DeliveryFailed(lastFailure?.message ?: "MOVE_TO delivery failed")
    }

    private fun isInsidePolygon(point: Point2dMm, polygon: List<Point2dMm>): Boolean {
        if (polygon.size < 3) {
            return false
        }

        var inside = false
        var previous = polygon.last()

        polygon.forEach { current ->
            val py = point.yMm.toDouble()
            val px = point.xMm.toDouble()
            val cy = current.yMm.toDouble()
            val pyPrev = previous.yMm.toDouble()
            val cx = current.xMm.toDouble()
            val pxPrev = previous.xMm.toDouble()

            val intersects = ((cy > py) != (pyPrev > py)) &&
                (px < ((pxPrev - cx) * (py - cy) / (pyPrev - cy)) + cx)
            if (intersects) {
                inside = !inside
            }
            previous = current
        }

        return inside
    }
}

sealed class MoveMowerResult {
    data object Success : MoveMowerResult()
    data object OutsideZone : MoveMowerResult()
    data object Busy : MoveMowerResult()
    data class DeliveryFailed(val reason: String) : MoveMowerResult()
}
