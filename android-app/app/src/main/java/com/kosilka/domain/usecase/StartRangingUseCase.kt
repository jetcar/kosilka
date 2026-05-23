package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.MowerPosition
import com.kosilka.domain.model.Point2dMm
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class StartRangingUseCase @Inject constructor(
    private val mowerDevice: MowerDevice,
    private val messageIdGenerator: MessageIdGenerator,
    private val dispatchers: CoroutineDispatchers,
    private val trilaterationSolver: TrilaterationSolver
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(RangingState())
    val state: StateFlow<RangingState> = _state.asStateFlow()

    private var incomingJob: Job? = null
    private var timeoutMonitorJob: Job? = null

    private var activeSessionId: String = ""
    private var anchorPositionsById: Map<String, Point2dMm> = emptyMap()
    private val latestDistancesByAnchorId = mutableMapOf<String, Int>()

    @Volatile
    private var lastSampleAtMs: Long = 0L

    suspend fun start(sessionId: String, sampleRateHz: Int, anchorsById: Map<String, Point2dMm>): Result<Unit> {
        if (anchorsById.size < 3) {
            return Result.failure(IllegalArgumentException("At least 3 anchors are required"))
        }

        activeSessionId = sessionId
        anchorPositionsById = anchorsById
        latestDistancesByAnchorId.clear()
        _state.value = RangingState(isRangingActive = true, isPositionLost = false, latestPosition = null)

        val sent = mowerDevice.send(
            Envelope(
                protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                messageType = ProtocolConstants.TYPE_RANGING_START,
                messageId = messageIdGenerator.next(),
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                payload = mapOf("sampleRateHz" to sampleRateHz.coerceAtLeast(5))
            )
        )
        if (sent.isFailure) {
            _state.value = RangingState(isRangingActive = false, isPositionLost = true, latestPosition = null)
            return sent
        }

        startIncomingCollector()
        startTimeoutMonitor()
        return Result.success(Unit)
    }

    suspend fun stop() {
        incomingJob?.cancel()
        incomingJob = null
        timeoutMonitorJob?.cancel()
        timeoutMonitorJob = null

        mowerDevice.send(
            Envelope(
                protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                messageType = ProtocolConstants.TYPE_RANGING_STOP,
                messageId = messageIdGenerator.next(),
                sessionId = activeSessionId,
                timestampMs = System.currentTimeMillis(),
                payload = mapOf("reason" to "screen_leave")
            )
        )

        _state.value = _state.value.copy(isRangingActive = false)
    }

    private fun startIncomingCollector() {
        incomingJob?.cancel()
        incomingJob = scope.launch {
            mowerDevice.incomingMessages.collect { message ->
                if (message !is IncomingMessage.RangingSample) {
                    return@collect
                }
                if (message.quality < 0.5f) {
                    return@collect
                }

                lastSampleAtMs = System.currentTimeMillis()
                latestDistancesByAnchorId[message.anchorId] = message.distanceMm

                val resolvedDistances = latestDistancesByAnchorId.entries
                    .mapNotNull { (anchorId, distance) ->
                        val point = anchorPositionsById[anchorId] ?: return@mapNotNull null
                        point to distance
                    }
                    .toMap()

                if (resolvedDistances.size < 3) {
                    return@collect
                }

                trilaterationSolver.solve(resolvedDistances)
                    .onSuccess { mowerPosition ->
                        _state.value = _state.value.copy(
                            latestPosition = mowerPosition,
                            isPositionLost = false,
                            isRangingActive = true
                        )
                    }
            }
        }
    }

    private fun startTimeoutMonitor() {
        timeoutMonitorJob?.cancel()
        timeoutMonitorJob = scope.launch {
            while (isActive && _state.value.isRangingActive) {
                if (lastSampleAtMs > 0L && System.currentTimeMillis() - lastSampleAtMs >= 3_000L) {
                    _state.value = _state.value.copy(isPositionLost = true)
                }
                delay(250L)
            }
        }
    }
}

data class RangingState(
    val isRangingActive: Boolean = false,
    val isPositionLost: Boolean = false,
    val latestPosition: MowerPosition? = null
)
