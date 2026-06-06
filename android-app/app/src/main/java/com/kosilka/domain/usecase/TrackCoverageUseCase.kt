package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.data.device.MowerDevice
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.local.dao.CoverageDao
import com.kosilka.data.local.mapper.toDomain
import com.kosilka.data.local.mapper.toEntity
import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@Singleton
class TrackCoverageUseCase @Inject constructor(
    private val mowerDevice: MowerDevice,
    private val coverageDao: CoverageDao,
    private val dispatchers: CoroutineDispatchers
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(CoverageState())
    val state: StateFlow<CoverageState> = _state.asStateFlow()

    private var activeSessionId: String? = null
    private var activeZone: Zone? = null
    private var incomingJob: Job? = null
    private var persistenceJob: Job? = null

    fun startSession(sessionId: String, zone: Zone?) {
        activeSessionId = sessionId
        activeZone = zone

        incomingJob?.cancel()
        persistenceJob?.cancel()

        // Synchronous overlay reset — eliminates a race where a previous
        // session's segments lingered briefly because the reset was launched
        // on the IO dispatcher and could lose to a concurrent state update
        // under heavy load.
        _state.value = CoverageState(sessionId = sessionId, segments = emptyList(), coveragePercent = 0f)

        scope.launch {
            coverageDao.deleteSegmentsForSession(sessionId)
        }

        persistenceJob = scope.launch {
            coverageDao.getSegmentsForSession(sessionId).collectLatest { entities ->
                val segments = entities.map { it.toDomain() }
                _state.update {
                    it.copy(
                        sessionId = sessionId,
                        segments = segments,
                        coveragePercent = computeCoveragePercent(activeZone, segments)
                    )
                }
            }
        }

        incomingJob = scope.launch {
            mowerDevice.incomingMessages.collect { message ->
                if (message !is IncomingMessage.CoverageUpdate) {
                    return@collect
                }
                if (message.sessionId != sessionId) {
                    return@collect
                }

                val mapped = message.segments.map {
                    CoverageSegment(
                        fromXMm = it.fromXMm,
                        fromYMm = it.fromYMm,
                        toXMm = it.toXMm,
                        toYMm = it.toYMm
                    )
                }

                if (mapped.isNotEmpty()) {
                    coverageDao.insertSegments(mapped.map { it.toEntity(sessionId) })
                }
            }
        }
    }

    fun updateZone(zone: Zone?) {
        activeZone = zone
        _state.update {
            it.copy(coveragePercent = computeCoveragePercent(zone, it.segments))
        }
    }

    fun stop() {
        incomingJob?.cancel()
        incomingJob = null
        persistenceJob?.cancel()
        persistenceJob = null
        activeSessionId = null
    }
}

data class CoverageState(
    val sessionId: String? = null,
    val segments: List<CoverageSegment> = emptyList(),
    val coveragePercent: Float = 0f
)

fun computeCoveragePercent(zone: Zone?, segments: List<CoverageSegment>): Float {
    if (zone == null || zone.vertices.size < 3 || segments.isEmpty()) {
        return 0f
    }

    val gridStepMm = 50
    val minX = zone.vertices.minOf { it.xMm }
    val minY = zone.vertices.minOf { it.yMm }
    val maxX = zone.vertices.maxOf { it.xMm }
    val maxY = zone.vertices.maxOf { it.yMm }

    var totalCells = 0
    var coveredCells = 0

    var y = minY
    while (y <= maxY) {
        var x = minX
        while (x <= maxX) {
            val center = Point2dMm(x, y)
            if (isInsidePolygon(center, zone.vertices)) {
                totalCells += 1
                if (isPointCovered(center, segments, thresholdMm = gridStepMm)) {
                    coveredCells += 1
                }
            }
            x += gridStepMm
        }
        y += gridStepMm
    }

    if (totalCells == 0) {
        return 0f
    }
    return (coveredCells.toFloat() / totalCells.toFloat()) * 100f
}

private fun isPointCovered(point: Point2dMm, segments: List<CoverageSegment>, thresholdMm: Int): Boolean {
    val thresholdSquared = thresholdMm.toDouble() * thresholdMm.toDouble()
    return segments.any { segment ->
        val distSquared = distancePointToSegmentSquared(
            px = point.xMm.toDouble(),
            py = point.yMm.toDouble(),
            x1 = segment.fromXMm.toDouble(),
            y1 = segment.fromYMm.toDouble(),
            x2 = segment.toXMm.toDouble(),
            y2 = segment.toYMm.toDouble()
        )
        distSquared <= thresholdSquared
    }
}

private fun distancePointToSegmentSquared(
    px: Double,
    py: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double
): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    if (dx == 0.0 && dy == 0.0) {
        val ddx = px - x1
        val ddy = py - y1
        return ddx * ddx + ddy * ddy
    }

    val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    val clampedT = max(0.0, min(1.0, t))
    val cx = x1 + clampedT * dx
    val cy = y1 + clampedT * dy

    val ddx = px - cx
    val ddy = py - cy
    return ddx * ddx + ddy * ddy
}

private fun isInsidePolygon(point: Point2dMm, polygon: List<Point2dMm>): Boolean {
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
