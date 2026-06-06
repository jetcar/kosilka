package com.kosilka.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.core.UiEvent
import com.kosilka.core.UiEventBus
import com.kosilka.data.device.MowerDevice
import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import com.kosilka.domain.usecase.ConnectMowerUseCase
import com.kosilka.domain.usecase.ConnectionState
import com.kosilka.domain.usecase.DefineZoneUseCase
import com.kosilka.domain.usecase.LoadAnchorsUseCase
import com.kosilka.domain.usecase.LoadSessionCoverageUseCase
import com.kosilka.domain.usecase.MoveMowerResult
import com.kosilka.domain.usecase.MoveMowerUseCase
import com.kosilka.domain.usecase.ReadCurrentMowerPositionUseCase
import com.kosilka.domain.usecase.SessionHistoryRepository
import com.kosilka.domain.usecase.StartRangingUseCase
import com.kosilka.domain.usecase.TrackCoverageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class MapViewModel @Inject constructor(
    private val startRangingUseCase: StartRangingUseCase,
    private val connectMowerUseCase: ConnectMowerUseCase,
    private val moveMowerUseCase: MoveMowerUseCase,
    private val readCurrentMowerPositionUseCase: ReadCurrentMowerPositionUseCase,
    private val defineZoneUseCase: DefineZoneUseCase,
    private val trackCoverageUseCase: TrackCoverageUseCase,
    private val sessionHistoryRepository: SessionHistoryRepository,
    private val loadSessionCoverageUseCase: LoadSessionCoverageUseCase,
    private val uiEventBus: UiEventBus,
    private val loadAnchorsUseCase: LoadAnchorsUseCase,
    private val mapSettingsStore: MapSettingsStore,
    private val mowerDevice: MowerDevice
) : ViewModel() {

    private var activeSessionId: String? = null
    private var offlineCoverageJob: Job? = null
    private var plannedNavigationJob: Job? = null
    private var positionPollingJob: Job? = null

    private val _uiState = MutableStateFlow(
        MapUiState()
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadAnchorsUseCase.ensureDefaultAnchors()
        }

        viewModelScope.launch {
            mapSettingsStore.sweepWidthMmFlow.collect { width ->
                _uiState.update { current ->
                    if (current.sweepWidthMm == width) {
                        current
                    } else {
                        val chunks = buildCoverageChunks(
                            availableZones = current.availableZones,
                            noGoZones = current.noGoZones,
                            laneSpacingMm = width
                        )
                        current.copy(
                            sweepWidthMm = width,
                            coverageChunks = chunks,
                            expectedCoverageSegments = chunks.flatMap { it.sweepSegments }
                        )
                    }
                }
            }
        }

        // Show mower marker immediately on map open, even before ranging starts.
        viewModelScope.launch {
            refreshCurrentMowerPosition()
        }

        viewModelScope.launch {
            loadAnchorsUseCase.observeAnchors().collect { anchors ->
                _uiState.update { it.copy(anchors = anchors) }
            }
        }

        viewModelScope.launch {
            startRangingUseCase.state.collect { rangingState ->
                _uiState.update { current ->
                    current.copy(
                        mowerPosition = rangingState.latestPosition ?: current.mowerPosition,
                        isPositionLost = rangingState.isPositionLost,
                        isRangingActive = rangingState.isRangingActive,
                        visibleTagCount = rangingState.visibleTagCount
                    )
                }
            }
        }

        viewModelScope.launch {
            connectMowerUseCase.connectionState.collect { connectionState ->
                when (connectionState) {
                    is ConnectionState.Connected -> {
                        activeSessionId = connectionState.sessionId
                        trackCoverageUseCase.startSession(
                            sessionId = connectionState.sessionId,
                            zone = _uiState.value.zone
                        )
                        _uiState.update { it.copy(isConnected = true, statusMessage = null) }
                        refreshCurrentMowerPosition()
                        startPositionPollingIfNeeded()
                        refreshUwbTags()
                    }

                    ConnectionState.Connecting -> {
                        _uiState.update { it.copy(isConnected = false, statusMessage = "Connecting...") }
                    }

                    ConnectionState.Disconnected -> {
                        activeSessionId = null
                        plannedNavigationJob?.cancel()
                        plannedNavigationJob = null
                        stopPositionPolling()
                        trackCoverageUseCase.stop()
                        _uiState.update { it.copy(isConnected = false, statusMessage = "Not connected") }
                        loadMostRecentCoverageFallback()
                    }

                    is ConnectionState.Failed -> {
                        activeSessionId = null
                        plannedNavigationJob?.cancel()
                        plannedNavigationJob = null
                        stopPositionPolling()
                        trackCoverageUseCase.stop()
                        _uiState.update { it.copy(isConnected = false, statusMessage = connectionState.reason) }
                        loadMostRecentCoverageFallback()
                    }
                }
            }
        }

        viewModelScope.launch {
            defineZoneUseCase.observeAvailableZones().collect { availableZones ->
                _uiState.update { current ->
                    val selectedIndex = if (availableZones.isEmpty()) {
                        0
                    } else {
                        current.selectedAvailableZoneIndex.coerceIn(0, availableZones.lastIndex)
                    }
                    val selectedZone = availableZones.getOrNull(selectedIndex)
                    trackCoverageUseCase.updateZone(selectedZone)
                    val chunks = buildCoverageChunks(
                        availableZones = availableZones,
                        noGoZones = current.noGoZones,
                        laneSpacingMm = current.sweepWidthMm
                    )
                    current.copy(
                        availableZones = availableZones,
                        selectedAvailableZoneIndex = selectedIndex,
                        zone = selectedZone,
                        coverageChunks = chunks,
                        expectedCoverageSegments = chunks.flatMap { it.sweepSegments }
                    )
                }
            }
        }

        viewModelScope.launch {
            defineZoneUseCase.observeNoGoZones().collect { noGoZones ->
                _uiState.update { current ->
                    val chunks = buildCoverageChunks(
                        availableZones = current.availableZones,
                        noGoZones = noGoZones,
                        laneSpacingMm = current.sweepWidthMm
                    )
                    current.copy(
                        noGoZones = noGoZones,
                        coverageChunks = chunks,
                        expectedCoverageSegments = chunks.flatMap { it.sweepSegments }
                    )
                }
            }
        }

        viewModelScope.launch {
            trackCoverageUseCase.state.collect { coverageState ->
                _uiState.update {
                    it.copy(
                        coverageSegments = coverageState.segments,
                        coveragePercent = coverageState.coveragePercent
                    )
                }
            }
        }

        viewModelScope.launch {
            uiEventBus.events.collect { event ->
                if (event is UiEvent.Snackbar) {
                    _uiState.update { it.copy(statusMessage = event.message) }
                }
            }
        }
    }

    private fun refreshUwbTags() {
        viewModelScope.launch {
            mowerDevice.getUwbTags().getOrNull()?.let { tags ->
                _uiState.update { it.copy(uwbTags = tags) }
            }
        }
    }

    fun toggleUwbTag(tagId: String) {
        viewModelScope.launch {
            mowerDevice.toggleUwbTag(tagId)
            refreshUwbTags()
        }
    }

    private fun loadMostRecentCoverageFallback() {
        offlineCoverageJob?.cancel()
        offlineCoverageJob = viewModelScope.launch {
            val recent = sessionHistoryRepository.observeMostRecentSession().first() ?: return@launch
            loadSessionCoverageUseCase.load(recent.sessionId).collect { segments ->
                _uiState.update {
                    it.copy(
                        coverageSegments = segments,
                        coveragePercent = recent.coveragePercent
                    )
                }
            }
        }
    }

    fun startRanging() {
        viewModelScope.launch {
            val sessionId = activeSessionId
            if (sessionId == null) {
                _uiState.update { it.copy(statusMessage = "Connect before ranging") }
                return@launch
            }

            val selectedZone = _uiState.value.zone
            if (selectedZone == null) {
                _uiState.update { it.copy(statusMessage = "Select an available zone before ranging") }
                return@launch
            }

            val startResult = startRangingUseCase.start(
                sessionId = sessionId,
                sampleRateHz = 5,
                anchorsById = _uiState.value.anchors.associate { anchor ->
                    anchor.id to Point2dMm(anchor.xMm, anchor.yMm)
                }
            )

            if (startResult.isFailure) {
                _uiState.update {
                    it.copy(statusMessage = startResult.exceptionOrNull()?.message ?: "Failed to start ranging")
                }
                return@launch
            }

            startPlannedNavigation(sessionId)
        }
    }

    fun stopRanging() {
        viewModelScope.launch {
            plannedNavigationJob?.cancel()
            plannedNavigationJob = null
            startRangingUseCase.stop()
            _uiState.update { it.copy(statusMessage = "Ranging and planned navigation stopped") }
        }
    }

    fun increaseSweepWidth() {
        _uiState.update { current ->
            val nextWidth = (current.sweepWidthMm + SWEEP_WIDTH_STEP_MM).coerceAtMost(MAX_SWEEP_WIDTH_MM)
            val chunks = buildCoverageChunks(
                availableZones = current.availableZones,
                noGoZones = current.noGoZones,
                laneSpacingMm = nextWidth
            )
            current.copy(
                sweepWidthMm = nextWidth,
                coverageChunks = chunks,
                expectedCoverageSegments = chunks.flatMap { it.sweepSegments }
            )
        }
        viewModelScope.launch {
            mapSettingsStore.setSweepWidthMm(_uiState.value.sweepWidthMm)
        }
    }

    fun decreaseSweepWidth() {
        _uiState.update { current ->
            val nextWidth = (current.sweepWidthMm - SWEEP_WIDTH_STEP_MM).coerceAtLeast(MIN_SWEEP_WIDTH_MM)
            val chunks = buildCoverageChunks(
                availableZones = current.availableZones,
                noGoZones = current.noGoZones,
                laneSpacingMm = nextWidth
            )
            current.copy(
                sweepWidthMm = nextWidth,
                coverageChunks = chunks,
                expectedCoverageSegments = chunks.flatMap { it.sweepSegments }
            )
        }
        viewModelScope.launch {
            mapSettingsStore.setSweepWidthMm(_uiState.value.sweepWidthMm)
        }
    }

    fun selectAvailableZone(index: Int) {
        _uiState.update { current ->
            if (current.availableZones.isEmpty()) {
                trackCoverageUseCase.updateZone(null)
                return@update current.copy(selectedAvailableZoneIndex = 0, zone = null)
            }

            val clampedIndex = index.coerceIn(0, current.availableZones.lastIndex)
            val selectedZone = current.availableZones[clampedIndex]
            trackCoverageUseCase.updateZone(selectedZone)
            current.copy(
                selectedAvailableZoneIndex = clampedIndex,
                zone = selectedZone,
                statusMessage = "Selected zone ${clampedIndex + 1}/${current.availableZones.size}"
            )
        }
    }

    fun onMapTapped(point: Point2dMm) {
        viewModelScope.launch {
            val sessionId = activeSessionId
            if (sessionId == null) {
                _uiState.update { it.copy(statusMessage = "Not connected") }
                return@launch
            }

            // A manual target should interrupt any planned-path loop.
            plannedNavigationJob?.cancel()
            plannedNavigationJob = null

            val currentPosition = _uiState.value.mowerPosition?.let { Point2dMm(it.xMm, it.yMm) }
            val availablePolygons = _uiState.value.availableZones.map { it.vertices }.filter { it.size >= 3 }
            val noGoPolygons = _uiState.value.noGoZones.map { it.vertices }.filter { it.size >= 3 }
            val constrainedTarget = if (currentPosition != null) {
                val insideAvailable = clampTargetInsideAvailable(
                    start = currentPosition,
                    desiredTarget = point,
                    availablePolygons = availablePolygons
                )
                clampTargetBeforeNoGo(
                    start = currentPosition,
                    desiredTarget = insideAvailable,
                    noGoPolygons = noGoPolygons
                )
            } else {
                point
            }

            val waypoints = if (currentPosition != null) {
                val detour = buildDetourWaypoints(
                    start = currentPosition,
                    end = constrainedTarget,
                    availableZones = _uiState.value.availableZones,
                    noGoZones = _uiState.value.noGoZones
                )
                buildList {
                    addAll(detour)
                    add(constrainedTarget)
                }
            } else {
                listOf(constrainedTarget)
            }

            if (waypoints.isEmpty()) {
                _uiState.update { it.copy(statusMessage = "No safe route to target") }
                return@launch
            }

            for ((index, waypoint) in waypoints.withIndex()) {
                when (
                    val result = moveMowerUseCase.moveTo(
                        sessionId = sessionId,
                        target = waypoint,
                        zone = _uiState.value.zone
                    )
                ) {
                    MoveMowerResult.Success -> {
                        val mowerPosition = _uiState.value.mowerPosition
                        val vectorDxMm = mowerPosition?.let { waypoint.xMm - it.xMm }
                        val vectorDyMm = mowerPosition?.let { waypoint.yMm - it.yMm }
                        val distanceMm = if (vectorDxMm != null && vectorDyMm != null) {
                            hypot(vectorDxMm.toDouble(), vectorDyMm.toDouble()).roundToInt()
                        } else {
                            null
                        }

                        _uiState.update {
                            it.copy(
                                destinationMarker = waypoint,
                                lastMoveVectorDxMm = vectorDxMm,
                                lastMoveVectorDyMm = vectorDyMm,
                                lastMoveDistanceMm = distanceMm,
                                statusMessage = "MOVE_TO ${index + 1}/${waypoints.size}: (${waypoint.xMm}, ${waypoint.yMm})"
                            )
                        }

                        val reached = waitUntilWaypointReached(waypoint)
                        if (!reached) {
                            _uiState.update {
                                it.copy(statusMessage = "MOVE_TO ${index + 1}/${waypoints.size}: waypoint not reached in time")
                            }
                            return@launch
                        }
                    }

                    MoveMowerResult.Busy -> {
                        _uiState.update { it.copy(statusMessage = "Mower busy") }
                        return@launch
                    }

                    MoveMowerResult.OutsideZone -> {
                        _uiState.update { it.copy(statusMessage = "Outside zone") }
                        return@launch
                    }

                    is MoveMowerResult.DeliveryFailed -> {
                        _uiState.update { it.copy(statusMessage = result.reason) }
                        return@launch
                    }
                }
            }
        }
    }

    fun stopMower() {
        viewModelScope.launch {
            val sessionId = activeSessionId
            if (sessionId == null) {
                _uiState.update { it.copy(statusMessage = "Not connected") }
                return@launch
            }

            val mowerPosition = _uiState.value.mowerPosition
            if (mowerPosition == null) {
                _uiState.update { it.copy(statusMessage = "Cannot stop: mower position unknown") }
                return@launch
            }

            val holdPosition = Point2dMm(mowerPosition.xMm, mowerPosition.yMm)
            when (
                val result = moveMowerUseCase.moveTo(
                    sessionId = sessionId,
                    target = holdPosition,
                    zone = _uiState.value.zone
                )
            ) {
                MoveMowerResult.Success -> {
                    _uiState.update {
                        it.copy(
                            destinationMarker = null,
                            lastMoveVectorDxMm = 0,
                            lastMoveVectorDyMm = 0,
                            lastMoveDistanceMm = 0,
                            statusMessage = "STOP sent"
                        )
                    }
                }

                MoveMowerResult.Busy -> {
                    _uiState.update { it.copy(statusMessage = "Mower busy") }
                }

                MoveMowerResult.OutsideZone -> {
                    _uiState.update { it.copy(statusMessage = "Outside zone") }
                }

                is MoveMowerResult.DeliveryFailed -> {
                    _uiState.update { it.copy(statusMessage = result.reason) }
                }
            }
        }
    }

    private suspend fun refreshCurrentMowerPosition() {
        readCurrentMowerPositionUseCase.read().getOrNull()?.let { point ->
            updateMowerPosition(point)
        }
    }

    private fun startPositionPollingIfNeeded() {
        if (positionPollingJob?.isActive == true) {
            return
        }

        positionPollingJob = viewModelScope.launch {
            while (isActive && activeSessionId != null) {
                readCurrentMowerPositionUseCase.read().getOrNull()?.let { point ->
                    updateMowerPosition(point)
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }

    private fun updateMowerPosition(point: Point2dMm) {
        _uiState.update { current ->
            val mowerPosition = com.kosilka.domain.model.MowerPosition(
                xMm = point.xMm,
                yMm = point.yMm,
                timestampMs = System.currentTimeMillis()
            )

            val destination = current.destinationMarker
            if (destination == null) {
                return@update current.copy(mowerPosition = mowerPosition, isPositionLost = false)
            }

            val distanceToDestination = hypot(
                (destination.xMm - point.xMm).toDouble(),
                (destination.yMm - point.yMm).toDouble()
            )

            if (distanceToDestination <= WAYPOINT_REACHED_THRESHOLD_MM) {
                current.copy(
                    mowerPosition = mowerPosition,
                    destinationMarker = null,
                    isPositionLost = false
                )
            } else {
                current.copy(
                    mowerPosition = mowerPosition,
                    isPositionLost = false
                )
            }
        }
    }

    private fun startPlannedNavigation(sessionId: String) {
        plannedNavigationJob?.cancel()

        val waypoints = buildWaypointsFromChunks(
            chunks = _uiState.value.coverageChunks,
            availableZones = _uiState.value.availableZones,
            noGoZones = _uiState.value.noGoZones
        )
        if (waypoints.isEmpty()) {
            _uiState.update {
                it.copy(statusMessage = "Ranging started. No planned path points to navigate")
            }
            return
        }

        plannedNavigationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(statusMessage = "Ranging started. Following planned path (${waypoints.size} points)")
            }

            for ((index, waypoint) in waypoints.withIndex()) {
                if (!isActive) {
                    break
                }

                val currentPosition = _uiState.value.mowerPosition?.let {
                    Point2dMm(it.xMm, it.yMm)
                }
                val availablePolygons = _uiState.value.availableZones.map { it.vertices }.filter { it.size >= 3 }
                val noGoPolygons = _uiState.value.noGoZones.map { it.vertices }.filter { it.size >= 3 }

                val constrainedTarget = if (currentPosition != null) {
                    val insideAvailable = clampTargetInsideAvailable(
                        start = currentPosition,
                        desiredTarget = waypoint,
                        availablePolygons = availablePolygons
                    )
                    clampTargetBeforeNoGo(
                        start = currentPosition,
                        desiredTarget = insideAvailable,
                        noGoPolygons = noGoPolygons
                    )
                } else {
                    waypoint
                }

                val result = moveMowerUseCase.moveTo(
                    sessionId = sessionId,
                    target = constrainedTarget,
                    zone = _uiState.value.zone
                )

                when (result) {
                    MoveMowerResult.Success -> {
                        _uiState.update {
                            it.copy(
                                destinationMarker = constrainedTarget,
                                statusMessage = "Path ${index + 1}/${waypoints.size}: moving to (${constrainedTarget.xMm}, ${constrainedTarget.yMm})"
                            )
                        }

                        val reached = waitUntilWaypointReached(constrainedTarget)
                        if (!reached) {
                            _uiState.update {
                                it.copy(statusMessage = "Path ${index + 1}/${waypoints.size}: skipped (waypoint not reached in time)")
                            }
                            continue
                        }
                    }

                    MoveMowerResult.Busy -> {
                        _uiState.update { it.copy(statusMessage = "Path paused: mower busy") }
                        break
                    }

                    MoveMowerResult.OutsideZone -> {
                        _uiState.update { it.copy(statusMessage = "Path ${index + 1}/${waypoints.size}: skipped (outside allowed area)") }
                        continue
                    }

                    is MoveMowerResult.DeliveryFailed -> {
                        _uiState.update { it.copy(statusMessage = "Path paused: ${result.reason}") }
                        break
                    }
                }
            }

            if (isActive) {
                _uiState.update { it.copy(statusMessage = "Planned path complete") }
            }
            plannedNavigationJob = null
        }
    }

    private suspend fun waitUntilWaypointReached(target: Point2dMm): Boolean {
        repeat(MAX_WAYPOINT_WAIT_STEPS) {
            val polledMower = readCurrentMowerPositionUseCase.read().getOrNull()?.let { point ->
                com.kosilka.domain.model.MowerPosition(
                    xMm = point.xMm,
                    yMm = point.yMm,
                    timestampMs = System.currentTimeMillis()
                )
            }

            if (polledMower != null) {
                updateMowerPosition(Point2dMm(polledMower.xMm, polledMower.yMm))
            }

            val mower = polledMower ?: _uiState.value.mowerPosition
            if (mower != null) {
                val distanceMm = hypot((target.xMm - mower.xMm).toDouble(), (target.yMm - mower.yMm).toDouble())
                if (distanceMm <= WAYPOINT_REACHED_THRESHOLD_MM) {
                    return true
                }
            }

            if (!currentCoroutineContext().isActive) {
                return false
            }
            delay(WAYPOINT_WAIT_POLL_MS)
        }
        return false
    }

    private fun buildWaypointsFromChunks(
        chunks: List<CoverageChunk>,
        availableZones: List<Zone>,
        noGoZones: List<Zone>
    ): List<Point2dMm> {
        if (chunks.isEmpty()) return emptyList()

        val points = mutableListOf<Point2dMm>()
        fun appendPointIfFar(point: Point2dMm) {
            val last = points.lastOrNull()
            if (last == null) {
                points += point
                return
            }
            val distanceMm = hypot((point.xMm - last.xMm).toDouble(), (point.yMm - last.yMm).toDouble())
            if (distanceMm >= MIN_WAYPOINT_SPACING_MM) {
                points += point
            }
        }

        for (chunk in chunks) {
            if (chunk.sweepSegments.isEmpty()) continue
            val chunkEntry = Point2dMm(
                xMm = chunk.sweepSegments.first().fromXMm,
                yMm = chunk.sweepSegments.first().fromYMm
            )

            val previousPoint = points.lastOrNull()
            if (previousPoint != null) {
                // Navigate around obstacles between chunks; uses existing detour logic.
                buildDetourWaypoints(
                    start = previousPoint,
                    end = chunkEntry,
                    availableZones = availableZones,
                    noGoZones = noGoZones
                ).forEach(::appendPointIfFar)
            }

            // Within-chunk transitions are obstacle-free by grid construction;
            // no detour routing is needed between segments of the same chunk.
            for (segment in chunk.sweepSegments) {
                appendPointIfFar(Point2dMm(segment.fromXMm, segment.fromYMm))
                appendPointIfFar(Point2dMm(segment.toXMm, segment.toYMm))
            }
        }
        return points
    }

    private fun buildDetourWaypoints(
        start: Point2dMm,
        end: Point2dMm,
        availableZones: List<Zone>,
        noGoZones: List<Zone>
    ): List<Point2dMm> {
        if (noGoZones.isEmpty()) {
            return emptyList()
        }

        if (isSafeStraightPath(start, end, availableZones, noGoZones)) {
            return emptyList()
        }

        // Collect all blocking no-go zones and compute their union bounding box.
        val blockingZones = noGoZones.filter { zone ->
            firstIntersectionT(start, end, zone.vertices) != null
        }
        if (blockingZones.isEmpty()) return emptyList()

        val boxMinX = blockingZones.minOf { z -> z.vertices.minOf { it.xMm } }
        val boxMaxX = blockingZones.maxOf { z -> z.vertices.maxOf { it.xMm } }
        val boxMinY = blockingZones.minOf { z -> z.vertices.minOf { it.yMm } }
        val boxMaxY = blockingZones.maxOf { z -> z.vertices.maxOf { it.yMm } }

        // Try progressively larger margins until a safe detour fits inside the available zone.
        // A small margin is preferred to stay well inside the available polygon.
        for (margin in DETOUR_TRY_MARGINS_MM) {
            val candidateRoutes = listOf(
                listOf(
                    Point2dMm(start.xMm, boxMinY - margin),
                    Point2dMm(end.xMm, boxMinY - margin)
                ),
                listOf(
                    Point2dMm(start.xMm, boxMaxY + margin),
                    Point2dMm(end.xMm, boxMaxY + margin)
                ),
                listOf(
                    Point2dMm(boxMinX - margin, start.yMm),
                    Point2dMm(boxMinX - margin, end.yMm)
                ),
                listOf(
                    Point2dMm(boxMaxX + margin, start.yMm),
                    Point2dMm(boxMaxX + margin, end.yMm)
                )
            )

            val route = candidateRoutes.firstOrNull { route ->
                isSafeWaypointRoute(start, route, end, availableZones, noGoZones)
            }
            if (route != null) return route
        }

        return emptyList()
    }

    private fun isSafeWaypointRoute(
        start: Point2dMm,
        route: List<Point2dMm>,
        end: Point2dMm,
        availableZones: List<Zone>,
        noGoZones: List<Zone>
    ): Boolean {
        val points = buildList {
            add(start)
            addAll(route)
            add(end)
        }

        return points
            .zipWithNext()
            .all { (from, to) -> isSafeStraightPath(from, to, availableZones, noGoZones) }
    }

    private fun isSafeStraightPath(
        start: Point2dMm,
        end: Point2dMm,
        availableZones: List<Zone>,
        noGoZones: List<Zone>
    ): Boolean {
        if (noGoZones.any { zone -> firstIntersectionT(start, end, zone.vertices) != null }) {
            return false
        }

        if (availableZones.isEmpty()) {
            return true
        }

        val samples = maxOf(2, (hypot((end.xMm - start.xMm).toDouble(), (end.yMm - start.yMm).toDouble()) / PATH_SAMPLE_STEP_MM).toInt())
        for (index in 0..samples) {
            val t = index.toDouble() / samples.toDouble()
            val samplePoint = Point2dMm(
                xMm = (start.xMm + (end.xMm - start.xMm) * t).roundToInt(),
                yMm = (start.yMm + (end.yMm - start.yMm) * t).roundToInt()
            )
            val insideAvailable = availableZones.any { zone -> isPointInsideOrOnPolygon(samplePoint, zone.vertices) }
            if (!insideAvailable) {
                return false
            }
            val insideNoGo = noGoZones.any { zone -> isPointInsideOrOnPolygon(samplePoint, zone.vertices) }
            if (insideNoGo) {
                return false
            }
        }

        return true
    }

    private fun clampTargetBeforeNoGo(
        start: Point2dMm,
        desiredTarget: Point2dMm,
        noGoPolygons: List<List<Point2dMm>>
    ): Point2dMm {
        if (noGoPolygons.isEmpty()) {
            return desiredTarget
        }

        val nearestHitT = noGoPolygons
            .mapNotNull { polygon -> firstIntersectionT(start, desiredTarget, polygon) }
            .minOrNull()
            ?: return desiredTarget

        val safeT = (nearestHitT - NO_GO_STOP_MARGIN_T).coerceIn(0.0, 1.0)
        val safeX = start.xMm + ((desiredTarget.xMm - start.xMm) * safeT)
        val safeY = start.yMm + ((desiredTarget.yMm - start.yMm) * safeT)
        return Point2dMm(
            xMm = safeX.roundToInt(),
            yMm = safeY.roundToInt()
        )
    }

    private fun clampTargetInsideAvailable(
        start: Point2dMm,
        desiredTarget: Point2dMm,
        availablePolygons: List<List<Point2dMm>>
    ): Point2dMm {
        if (availablePolygons.isEmpty()) {
            return desiredTarget
        }

        if (isPathInsideAvailableUnion(start, desiredTarget, availablePolygons)) {
            return desiredTarget
        }

        val safeT = findLastSafeAvailableT(start, desiredTarget, availablePolygons)
            ?.minus(AVAILABLE_STOP_MARGIN_T)
            ?.coerceIn(0.0, 1.0)
            ?: return start
        val safeX = start.xMm + ((desiredTarget.xMm - start.xMm) * safeT)
        val safeY = start.yMm + ((desiredTarget.yMm - start.yMm) * safeT)
        return Point2dMm(
            xMm = safeX.roundToInt(),
            yMm = safeY.roundToInt()
        )
    }

    private fun isPathInsideAvailableUnion(
        start: Point2dMm,
        end: Point2dMm,
        availablePolygons: List<List<Point2dMm>>
    ): Boolean {
        val samples = maxOf(
            2,
            (hypot((end.xMm - start.xMm).toDouble(), (end.yMm - start.yMm).toDouble()) / PATH_SAMPLE_STEP_MM).toInt()
        )

        for (index in 0..samples) {
            val t = index.toDouble() / samples.toDouble()
            if (!isPointInsideAvailableUnion(interpolatePoint(start, end, t), availablePolygons)) {
                return false
            }
        }

        return true
    }

    private fun findLastSafeAvailableT(
        start: Point2dMm,
        end: Point2dMm,
        availablePolygons: List<List<Point2dMm>>
    ): Double? {
        val startInsideAvailable = isPointInsideAvailableUnion(start, availablePolygons)
        if (!startInsideAvailable) {
            return if (isPointInsideAvailableUnion(end, availablePolygons)) 1.0 else null
        }

        val samples = maxOf(
            2,
            (hypot((end.xMm - start.xMm).toDouble(), (end.yMm - start.yMm).toDouble()) / PATH_SAMPLE_STEP_MM).toInt()
        )
        var safeT = 0.0

        for (index in 1..samples) {
            val candidateT = index.toDouble() / samples.toDouble()
            val candidatePoint = interpolatePoint(start, end, candidateT)
            if (isPointInsideAvailableUnion(candidatePoint, availablePolygons)) {
                safeT = candidateT
                continue
            }

            var lower = safeT
            var upper = candidateT
            repeat(20) {
                val mid = (lower + upper) / 2.0
                val midPoint = interpolatePoint(start, end, mid)
                if (isPointInsideAvailableUnion(midPoint, availablePolygons)) {
                    lower = mid
                } else {
                    upper = mid
                }
            }
            return lower
        }

        return 1.0
    }

    private fun isPointInsideAvailableUnion(
        point: Point2dMm,
        availablePolygons: List<List<Point2dMm>>
    ): Boolean {
        return availablePolygons.any { polygon -> isPointInsideOrOnPolygon(point, polygon) }
    }

    private fun interpolatePoint(start: Point2dMm, end: Point2dMm, t: Double): Point2dMm {
        return Point2dMm(
            xMm = (start.xMm + (end.xMm - start.xMm) * t).roundToInt(),
            yMm = (start.yMm + (end.yMm - start.yMm) * t).roundToInt()
        )
    }

    private fun firstIntersectionT(start: Point2dMm, end: Point2dMm, polygon: List<Point2dMm>): Double? {
        if (polygon.size < 3) {
            return null
        }

        val sx = start.xMm.toDouble()
        val sy = start.yMm.toDouble()
        val ex = end.xMm.toDouble()
        val ey = end.yMm.toDouble()
        val dx = ex - sx
        val dy = ey - sy
        if (dx == 0.0 && dy == 0.0) {
            return null
        }

        var nearestT: Double? = null
        var previous = polygon.last()
        polygon.forEach { current ->
            val intersectionT = segmentIntersectionT(
                sx = sx,
                sy = sy,
                dx = dx,
                dy = dy,
                ax = previous.xMm.toDouble(),
                ay = previous.yMm.toDouble(),
                bx = current.xMm.toDouble(),
                by = current.yMm.toDouble()
            )
            if (intersectionT != null) {
                nearestT = if (nearestT == null) intersectionT else minOf(nearestT, intersectionT)
            }
            previous = current
        }

        return nearestT
    }

    private fun segmentIntersectionT(
        sx: Double,
        sy: Double,
        dx: Double,
        dy: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double
    ): Double? {
        val ex = bx - ax
        val ey = by - ay
        val denominator = (dx * ey) - (dy * ex)
        if (kotlin.math.abs(denominator) < 1e-9) {
            return null
        }

        val qpx = ax - sx
        val qpy = ay - sy
        val t = ((qpx * ey) - (qpy * ex)) / denominator
        val u = ((qpx * dy) - (qpy * dx)) / denominator

        if (t in 0.0..1.0 && u in 0.0..1.0) {
            return t
        }
        return null
    }

    private fun isPointInsidePolygon(point: Point2dMm, polygon: List<Point2dMm>): Boolean {
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

    private fun isPointInsideOrOnPolygon(point: Point2dMm, polygon: List<Point2dMm>): Boolean {
        if (isPointInsidePolygon(point, polygon)) {
            return true
        }

        var previous = polygon.last()
        polygon.forEach { current ->
            if (orientation(previous, point, current) == 0 && onSegment(previous, point, current)) {
                return true
            }
            previous = current
        }

        return false
    }

    private fun orientation(a: Point2dMm, b: Point2dMm, c: Point2dMm): Int {
        val value = (b.yMm - a.yMm).toLong() * (c.xMm - b.xMm).toLong() -
            (b.xMm - a.xMm).toLong() * (c.yMm - b.yMm).toLong()
        return when {
            value == 0L -> 0
            value > 0L -> 1
            else -> 2
        }
    }

    private fun onSegment(a: Point2dMm, b: Point2dMm, c: Point2dMm): Boolean {
        return b.xMm <= maxOf(a.xMm, c.xMm) &&
            b.xMm >= minOf(a.xMm, c.xMm) &&
            b.yMm <= maxOf(a.yMm, c.yMm) &&
            b.yMm >= minOf(a.yMm, c.yMm)
    }

    /**
     * Decomposes all available zones into a grid of rectangular coverage chunks using the
     * bounding-box edges of every no-go zone as grid boundaries.  Because each cell's edges
     * align with no-go boundaries, the scan lines within a cell never cross a no-go zone and
     * diagonal inter-lane transitions are always obstacle-free.  Only moves between chunks
     * may need detour routing.
     *
     * Cells are visited row-by-row with column direction alternating each row (boustrophedon).
     */
    private fun buildCoverageChunks(
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        laneSpacingMm: Int
    ): List<CoverageChunk> {
        val effectiveSpacing = laneSpacingMm.coerceIn(MIN_SWEEP_WIDTH_MM, MAX_SWEEP_WIDTH_MM)

        val xBoundaries = sortedSetOf<Int>()
        val yBoundaries = sortedSetOf<Int>()

        for (zone in availableZones) {
            if (zone.vertices.size < 3) continue
            xBoundaries.add(zone.vertices.minOf { it.xMm })
            xBoundaries.add(zone.vertices.maxOf { it.xMm })
            yBoundaries.add(zone.vertices.minOf { it.yMm })
            yBoundaries.add(zone.vertices.maxOf { it.yMm })
        }
        for (noGo in noGoZones) {
            if (noGo.vertices.size < 3) continue
            xBoundaries.add(noGo.vertices.minOf { it.xMm })
            xBoundaries.add(noGo.vertices.maxOf { it.xMm })
            yBoundaries.add(noGo.vertices.minOf { it.yMm })
            yBoundaries.add(noGo.vertices.maxOf { it.yMm })
        }

        if (xBoundaries.size < 2 || yBoundaries.size < 2) return emptyList()

        val xList = xBoundaries.toList()
        val yList = yBoundaries.toList()
        val chunks = mutableListOf<CoverageChunk>()

        for (row in 0 until yList.size - 1) {
            val cellMinY = yList[row]
            val cellMaxY = yList[row + 1]
            val colRange = if (row % 2 == 0) 0 until xList.size - 1 else (xList.size - 2) downTo 0
            for (col in colRange) {
                val cellMinX = xList[col]
                val cellMaxX = xList[col + 1]
                val segments = buildSweepSegmentsInCell(
                    availableZones = availableZones,
                    noGoZones = noGoZones,
                    cellMinX = cellMinX,
                    cellMaxX = cellMaxX,
                    cellMinY = cellMinY,
                    cellMaxY = cellMaxY,
                    laneSpacingMm = effectiveSpacing
                )
                if (segments.isNotEmpty()) {
                    chunks += CoverageChunk(
                        id = chunks.size,
                        sweepSegments = segments,
                        cellMinXMm = cellMinX,
                        cellMaxXMm = cellMaxX,
                        cellMinYMm = cellMinY,
                        cellMaxYMm = cellMaxY
                    )
                }
            }
        }
        return mergeAdjacentChunks(chunks, availableZones, noGoZones, effectiveSpacing)
    }

    /**
     * Greedily merges adjacent rectangular cells into larger rectangles when the shared edge is
     * not load-bearing for either an available-zone boundary or a no-go boundary.
     *
     * The pass runs horizontally first so narrow side-by-side slices collapse before attempting
     * vertical merges. After each merge the sweep segments are rebuilt for the merged rectangle.
     */
    private fun mergeAdjacentChunks(
        rawChunks: List<CoverageChunk>,
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        laneSpacingMm: Int
    ): List<CoverageChunk> {
        if (rawChunks.isEmpty()) return emptyList()

        val boundaryZones = availableZones + noGoZones
        var currentChunks = rawChunks

        while (true) {
            val horizontallyMerged = mergeChunksHorizontally(
                chunks = currentChunks,
                boundaryZones = boundaryZones,
                availableZones = availableZones,
                noGoZones = noGoZones,
                laneSpacingMm = laneSpacingMm
            )
            val fullyMerged = mergeChunksVertically(
                chunks = horizontallyMerged,
                boundaryZones = boundaryZones,
                availableZones = availableZones,
                noGoZones = noGoZones,
                laneSpacingMm = laneSpacingMm
            )
            if (fullyMerged.size == currentChunks.size) {
                return fullyMerged.mapIndexed { index, chunk -> chunk.copy(id = index) }
            }
            currentChunks = fullyMerged
        }
    }

    private fun mergeChunksHorizontally(
        chunks: List<CoverageChunk>,
        boundaryZones: List<Zone>,
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        laneSpacingMm: Int
    ): List<CoverageChunk> {
        val merged = mutableListOf<CoverageChunk>()
        val chunksByRow = chunks.groupBy { it.cellMinYMm to it.cellMaxYMm }

        for ((yRange, rowChunks) in chunksByRow.entries.sortedBy { it.key.first }) {
            val (yMin, yMax) = yRange
            val sorted = rowChunks.sortedBy { it.cellMinXMm }
            var currentMinX = sorted.first().cellMinXMm
            var currentMaxX = sorted.first().cellMaxXMm

            for (index in 1 until sorted.size) {
                val next = sorted[index]
                val sharedX = currentMaxX
                val isAdjacent = next.cellMinXMm == sharedX
                val canMerge = isAdjacent && !hasBoundaryAtX(boundaryZones, sharedX, yMin, yMax)

                if (canMerge) {
                    currentMaxX = next.cellMaxXMm
                } else {
                    addMergedChunk(merged, availableZones, noGoZones, currentMinX, currentMaxX, yMin, yMax, laneSpacingMm)
                    currentMinX = next.cellMinXMm
                    currentMaxX = next.cellMaxXMm
                }
            }

            addMergedChunk(merged, availableZones, noGoZones, currentMinX, currentMaxX, yMin, yMax, laneSpacingMm)
        }

        return merged
    }

    private fun mergeChunksVertically(
        chunks: List<CoverageChunk>,
        boundaryZones: List<Zone>,
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        laneSpacingMm: Int
    ): List<CoverageChunk> {
        val merged = mutableListOf<CoverageChunk>()
        val chunksByColumn = chunks.groupBy { it.cellMinXMm to it.cellMaxXMm }

        for ((xRange, columnChunks) in chunksByColumn.entries.sortedBy { it.key.first }) {
            val (xMin, xMax) = xRange
            val sorted = columnChunks.sortedBy { it.cellMinYMm }
            var currentMinY = sorted.first().cellMinYMm
            var currentMaxY = sorted.first().cellMaxYMm

            for (index in 1 until sorted.size) {
                val next = sorted[index]
                val sharedY = currentMaxY
                val isAdjacent = next.cellMinYMm == sharedY
                val canMerge = isAdjacent && !hasBoundaryAtY(boundaryZones, sharedY, xMin, xMax)

                if (canMerge) {
                    currentMaxY = next.cellMaxYMm
                } else {
                    addMergedChunk(merged, availableZones, noGoZones, xMin, xMax, currentMinY, currentMaxY, laneSpacingMm)
                    currentMinY = next.cellMinYMm
                    currentMaxY = next.cellMaxYMm
                }
            }

            addMergedChunk(merged, availableZones, noGoZones, xMin, xMax, currentMinY, currentMaxY, laneSpacingMm)
        }

        return merged
    }

    private fun hasBoundaryAtX(
        zones: List<Zone>,
        xMm: Int,
        yMin: Int,
        yMax: Int
    ): Boolean {
        return zones.any { zone ->
            if (zone.vertices.size < 3) {
                false
            } else {
                val zoneMinX = zone.vertices.minOf { it.xMm }
                val zoneMaxX = zone.vertices.maxOf { it.xMm }
                val zoneMinY = zone.vertices.minOf { it.yMm }
                val zoneMaxY = zone.vertices.maxOf { it.yMm }
                (zoneMinX == xMm || zoneMaxX == xMm) && zoneMaxY > yMin && zoneMinY < yMax
            }
        }
    }

    private fun hasBoundaryAtY(
        zones: List<Zone>,
        yMm: Int,
        xMin: Int,
        xMax: Int
    ): Boolean {
        return zones.any { zone ->
            if (zone.vertices.size < 3) {
                false
            } else {
                val zoneMinX = zone.vertices.minOf { it.xMm }
                val zoneMaxX = zone.vertices.maxOf { it.xMm }
                val zoneMinY = zone.vertices.minOf { it.yMm }
                val zoneMaxY = zone.vertices.maxOf { it.yMm }
                (zoneMinY == yMm || zoneMaxY == yMm) && zoneMaxX > xMin && zoneMinX < xMax
            }
        }
    }

    private fun addMergedChunk(
        target: MutableList<CoverageChunk>,
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        xMin: Int,
        xMax: Int,
        yMin: Int,
        yMax: Int,
        laneSpacingMm: Int
    ) {
        val segments = buildSweepSegmentsInCell(availableZones, noGoZones, xMin, xMax, yMin, yMax, laneSpacingMm)
        if (segments.isNotEmpty()) {
            target += CoverageChunk(
                id = 0,
                sweepSegments = segments,
                cellMinXMm = xMin,
                cellMaxXMm = xMax,
                cellMinYMm = yMin,
                cellMaxYMm = yMax
            )
        }
    }

    private fun buildSweepSegmentsInCell(
        availableZones: List<Zone>,
        noGoZones: List<Zone>,
        cellMinX: Int,
        cellMaxX: Int,
        cellMinY: Int,
        cellMaxY: Int,
        laneSpacingMm: Int
    ): List<CoverageSegment> {
        val segments = mutableListOf<CoverageSegment>()
        var leftToRight = true
        var y = cellMinY
        while (y <= cellMaxY) {
            val availableIntervals = availableZones.flatMap { zone ->
                horizontalIntervalsAtY(zone.vertices, y)
            }
            val clippedIntervals = availableIntervals.mapNotNull { (left, right) ->
                val clippedLeft = maxOf(left, cellMinX.toDouble())
                val clippedRight = minOf(right, cellMaxX.toDouble())
                if (clippedRight - clippedLeft >= MIN_EXPECTED_SEGMENT_MM) clippedLeft to clippedRight else null
            }
            val noGoIntervals = noGoZones
                .flatMap { zone -> horizontalIntervalsAtY(zone.vertices, y) }
                .sortedBy { it.first }

            for (interval in clippedIntervals) {
                val freeIntervals = subtractIntervals(interval, noGoIntervals)
                for (freeInterval in freeIntervals) {
                    val fromX: Int
                    val toX: Int
                    if (leftToRight) {
                        fromX = freeInterval.first.roundToInt()
                        toX = freeInterval.second.roundToInt()
                    } else {
                        fromX = freeInterval.second.roundToInt()
                        toX = freeInterval.first.roundToInt()
                    }
                    if (kotlin.math.abs(toX - fromX) >= MIN_EXPECTED_SEGMENT_MM) {
                        segments += CoverageSegment(
                            fromXMm = fromX,
                            fromYMm = y,
                            toXMm = toX,
                            toYMm = y
                        )
                        leftToRight = !leftToRight
                    }
                }
            }
            y += laneSpacingMm
        }
        return segments
    }

    private fun horizontalIntervalsAtY(vertices: List<Point2dMm>, yMm: Int): List<Pair<Double, Double>> {
        if (vertices.size < 3) {
            return emptyList()
        }

        val y = yMm.toDouble()
        val intersections = mutableListOf<Double>()
        var previous = vertices.last()
        for (current in vertices) {
            val y1 = previous.yMm.toDouble()
            val y2 = current.yMm.toDouble()
            val crosses = (y1 > y) != (y2 > y)
            if (crosses) {
                val x1 = previous.xMm.toDouble()
                val x2 = current.xMm.toDouble()
                val t = (y - y1) / (y2 - y1)
                intersections += x1 + (x2 - x1) * t
            }
            previous = current
        }

        val sorted = intersections.sorted()
        if (sorted.size < 2) {
            return emptyList()
        }

        val intervals = mutableListOf<Pair<Double, Double>>()
        var index = 0
        while (index + 1 < sorted.size) {
            val left = sorted[index]
            val right = sorted[index + 1]
            if (right - left >= MIN_EXPECTED_SEGMENT_MM) {
                intervals += (left to right)
            }
            index += 2
        }
        return intervals
    }

    private fun subtractIntervals(
        base: Pair<Double, Double>,
        blocked: List<Pair<Double, Double>>
    ): List<Pair<Double, Double>> {
        var free = listOf(base)
        for (blockedInterval in blocked) {
            free = free.flatMap { current -> subtractSingleInterval(current, blockedInterval) }
            if (free.isEmpty()) {
                break
            }
        }
        return free
    }

    private fun subtractSingleInterval(
        base: Pair<Double, Double>,
        blocked: Pair<Double, Double>
    ): List<Pair<Double, Double>> {
        val left = base.first
        val right = base.second
        val blockLeft = min(blocked.first, blocked.second)
        val blockRight = max(blocked.first, blocked.second)

        if (blockRight <= left || blockLeft >= right) {
            return listOf(base)
        }

        val parts = mutableListOf<Pair<Double, Double>>()
        if (blockLeft > left) {
            // Inset slightly from the no-go edge so sweep endpoints never sit exactly on the boundary.
            // Boundary-coincident points are treated as "inside" by the ray-cast check and break detour routing.
            parts += (left to (blockLeft - NOGO_EDGE_INSET_MM).coerceAtLeast(left))
        }
        if (blockRight < right) {
            parts += ((blockRight + NOGO_EDGE_INSET_MM).coerceAtMost(right) to right)
        }
        return parts.filter { (start, end) -> (end - start) >= MIN_EXPECTED_SEGMENT_MM }
    }

    private companion object {
        const val MIN_EXPECTED_SEGMENT_MM = 150.0
        const val WAYPOINT_REACHED_THRESHOLD_MM = 220.0
        const val WAYPOINT_WAIT_POLL_MS = 250L
        const val MAX_WAYPOINT_WAIT_STEPS = 120
        const val POSITION_POLL_INTERVAL_MS = 250L
        const val MIN_WAYPOINT_SPACING_MM = 120.0
        const val NO_GO_STOP_MARGIN_T = 0.03
        const val AVAILABLE_STOP_MARGIN_T = 0.01
        const val SWEEP_WIDTH_STEP_MM = 50
        const val MIN_SWEEP_WIDTH_MM = 150
        const val MAX_SWEEP_WIDTH_MM = 1200
        val DETOUR_TRY_MARGINS_MM = listOf(50, 100, 150, 200, 300, 500)
        const val NOGO_EDGE_INSET_MM = 50.0
        const val PATH_SAMPLE_STEP_MM = 120.0
    }
}
