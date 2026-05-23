package com.kosilka.core.emulator

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.domain.model.Point2dMm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

@Singleton
class EmulatorScenarioEngine @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
    private val path: List<Point2dMm> = DefaultEmulatorPath.waypoints,
    private val speedMmPerSec: Float = 200f
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val stateMutex = Mutex()
    private val messagesFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)

    private var rangingJob: Job? = null
    private var nextMessageId: Long = 1L
    private var activeSessionId: String = ""
    private var currentScenario: ScenarioState = ScenarioState(EmulatorScenario.Normal, startedAtMs = 0L)

    private var currentPathIndex = 0
    private var currentPosition = path.firstOrNull() ?: Point2dMm(0, 0)
    private var leftoverDistanceMm = 0f
    private var driftOffsetMm = 0f
    private var sampleSequence = 0L

    val messages: SharedFlow<IncomingMessage> = messagesFlow.asSharedFlow()

    fun activateScenario(scenario: EmulatorScenario) {
        scope.launch {
            stateMutex.withLock {
                currentScenario = ScenarioState(
                    scenario = scenario,
                    startedAtMs = nowMs()
                )
                if (scenario !is EmulatorScenario.Drift) {
                    driftOffsetMm = 0f
                }
            }
        }
    }

    fun clearScenario() {
        scope.launch {
            stateMutex.withLock {
                currentScenario = ScenarioState(EmulatorScenario.Normal, startedAtMs = nowMs())
                driftOffsetMm = 0f
            }
        }
    }

    suspend fun currentPosition(): Point2dMm = stateMutex.withLock { currentPosition }

    suspend fun activeScenario(): EmulatorScenario = stateMutex.withLock {
        if (isScenarioExpired(nowMs())) {
            currentScenario = ScenarioState(EmulatorScenario.Normal, nowMs())
            driftOffsetMm = 0f
        }
        currentScenario.scenario
    }

    suspend fun isBusyActive(): Boolean = stateMutex.withLock {
        if (isScenarioExpired(nowMs())) {
            currentScenario = ScenarioState(EmulatorScenario.Normal, nowMs())
            driftOffsetMm = 0f
        }
        currentScenario.scenario is EmulatorScenario.Busy
    }

    fun startRanging(sessionId: String, sampleRateHz: Int) {
        val effectiveRate = sampleRateHz.coerceAtLeast(1)
        activeSessionId = sessionId
        rangingJob?.cancel()
        rangingJob = scope.launch {
            val intervalMs = (1_000L / effectiveRate).coerceAtLeast(100L)
            while (isActive) {
                emitSample(intervalMs)
                delay(intervalMs)
            }
        }
    }

    fun stopRanging() {
        rangingJob?.cancel()
        rangingJob = null
    }

    private suspend fun emitSample(intervalMs: Long) {
        val state = stateMutex.withLock {
            maybeExpireScenarioLocked(nowMs())

            val frozen = currentScenario.scenario is EmulatorScenario.Stuck
            if (!frozen) {
                advancePathLocked((speedMmPerSec * (intervalMs / 1_000f)) + leftoverDistanceMm)
            }

            val quality = when (currentScenario.scenario) {
                is EmulatorScenario.SignalInterference -> 0.3f
                else -> 0.95f
            }

            if (currentScenario.scenario is EmulatorScenario.Drift) {
                val driftRate = (currentScenario.scenario as EmulatorScenario.Drift).driftRateMmPerSec
                driftOffsetMm += driftRate * (intervalMs / 1_000f)
            }

            val shouldDrop = currentScenario.scenario is EmulatorScenario.SignalLoss
            val truePosition = currentPosition
            val reportedPosition = Point2dMm(
                xMm = (truePosition.xMm + driftOffsetMm).toInt(),
                yMm = truePosition.yMm
            )

            val distanceFromOriginMm = hypot(
                reportedPosition.xMm.toDouble(),
                reportedPosition.yMm.toDouble()
            ).toInt()

            sampleSequence += 1
            SampleState(
                shouldDrop = shouldDrop,
                quality = quality,
                distanceMm = distanceFromOriginMm,
                sequence = sampleSequence,
                timestampMs = nowMs()
            )
        }

        if (state.shouldDrop) {
            return
        }

        messagesFlow.emit(
            IncomingMessage.RangingSample(
                messageId = nextMessageId++,
                sessionId = activeSessionId,
                timestampMs = state.timestampMs,
                distanceMm = state.distanceMm,
                quality = state.quality,
                rssiDbm = -62,
                sequence = state.sequence,
                anchorId = "emu-anchor-1"
            )
        )
    }

    private fun advancePathLocked(distanceMm: Float) {
        if (path.size < 2 || distanceMm <= 0f) {
            leftoverDistanceMm = 0f
            return
        }

        var remaining = distanceMm
        while (remaining > 0f) {
            val nextIndex = (currentPathIndex + 1) % path.size
            val from = path[currentPathIndex]
            val to = path[nextIndex]
            val segmentLength = hypot((to.xMm - from.xMm).toDouble(), (to.yMm - from.yMm).toDouble()).toFloat()
            if (segmentLength <= 0f) {
                currentPathIndex = nextIndex
                continue
            }

            val fromToCurrent = hypot(
                (currentPosition.xMm - from.xMm).toDouble(),
                (currentPosition.yMm - from.yMm).toDouble()
            ).toFloat()
            val distanceToSegmentEnd = (segmentLength - fromToCurrent).coerceAtLeast(0f)

            if (remaining >= distanceToSegmentEnd) {
                currentPosition = to
                currentPathIndex = nextIndex
                remaining -= distanceToSegmentEnd
            } else {
                val t = (fromToCurrent + remaining) / segmentLength
                currentPosition = Point2dMm(
                    xMm = (from.xMm + (to.xMm - from.xMm) * t).toInt(),
                    yMm = (from.yMm + (to.yMm - from.yMm) * t).toInt()
                )
                remaining = 0f
            }
        }

        leftoverDistanceMm = remaining
    }

    private fun maybeExpireScenarioLocked(nowMs: Long) {
        if (isScenarioExpired(nowMs)) {
            currentScenario = ScenarioState(EmulatorScenario.Normal, nowMs)
            driftOffsetMm = 0f
        }
    }

    private fun isScenarioExpired(nowMs: Long): Boolean {
        val elapsed = nowMs - currentScenario.startedAtMs
        return when (val scenario = currentScenario.scenario) {
            is EmulatorScenario.Stuck -> elapsed >= scenario.durationMs
            is EmulatorScenario.SignalInterference -> elapsed >= scenario.durationMs
            is EmulatorScenario.SignalLoss -> elapsed >= scenario.durationMs
            is EmulatorScenario.Busy -> elapsed >= scenario.durationMs
            else -> false
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private data class ScenarioState(
        val scenario: EmulatorScenario,
        val startedAtMs: Long
    )

    private data class SampleState(
        val shouldDrop: Boolean,
        val quality: Float,
        val distanceMm: Int,
        val sequence: Long,
        val timestampMs: Long
    )
}
