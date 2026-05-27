package com.kosilka.feature.zone

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.usecase.ConnectMowerUseCase
import com.kosilka.domain.usecase.ConnectionState
import com.kosilka.domain.usecase.DefineZoneUseCase
import com.kosilka.domain.usecase.MoveMowerResult
import com.kosilka.domain.usecase.MoveMowerUseCase
import com.kosilka.domain.usecase.ReadCurrentMowerPositionUseCase
import com.kosilka.domain.usecase.StartRangingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.roundToInt

@HiltViewModel
class ZoneViewModel @Inject constructor(
    private val defineZoneUseCase: DefineZoneUseCase,
    private val connectMowerUseCase: ConnectMowerUseCase,
    private val moveMowerUseCase: MoveMowerUseCase,
    private val readCurrentMowerPositionUseCase: ReadCurrentMowerPositionUseCase,
    private val startRangingUseCase: StartRangingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ZoneUiState())
    val uiState: StateFlow<ZoneUiState> = _uiState.asStateFlow()

    private var activeSessionId: String? = null
    private var lastNavigationCommandAtMs: Long = 0L
    private var queuedNavigationTarget: Point2dMm? = null
    private var navigationDispatchJob: Job? = null
    private var positionPollingJob: Job? = null
    private var draftPersistenceJob: Job? = null
    private var autoConnectJob: Job? = null

    init {
        viewModelScope.launch {
            defineZoneUseCase.observeAvailableZones().collect { zones ->
                _uiState.update { current ->
                    val nextAvailable = if (zones.isNotEmpty()) {
                        zones.mapIndexed { index, zone ->
                            ZoneDraft(
                                id = zone.id,
                                vertices = zoneToRectangle(zone.vertices) ?: defaultRectangleVertices(index)
                            )
                        }
                    } else if (current.availableZones.isNotEmpty()) {
                        current.availableZones
                    } else {
                        listOf(ZoneDraft(id = defaultAvailableId(0), vertices = defaultRectangleVertices(0)))
                    }

                    clampSelectedIndex(
                        current.copy(availableZones = nextAvailable)
                    )
                }
            }
        }

        viewModelScope.launch {
            defineZoneUseCase.observeNoGoZones().collect { zones ->
                _uiState.update { current ->
                    val nextNoGo = if (zones.isNotEmpty()) {
                        zones.mapIndexed { index, zone ->
                            ZoneDraft(
                                id = zone.id,
                                vertices = zoneToRectangle(zone.vertices) ?: defaultNoGoVertices(index)
                            )
                        }
                    } else if (current.noGoZones.isNotEmpty()) {
                        current.noGoZones
                    } else {
                        emptyList()
                    }

                    clampSelectedIndex(
                        current.copy(noGoZones = nextNoGo)
                    )
                }
            }
        }

        viewModelScope.launch {
            connectMowerUseCase.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        activeSessionId = state.sessionId
                        _uiState.update { it.copy(statusMessage = null) }
                        stopAutoConnectLoop()
                        refreshCurrentMowerPosition()
                        startPositionPollingIfNeeded()
                    }

                    ConnectionState.Connecting -> {
                        Unit
                    }

                    ConnectionState.Disconnected -> {
                        activeSessionId = null
                        ensureAutoConnectLoop()
                        stopPositionPolling()
                    }

                    is ConnectionState.Failed -> {
                        activeSessionId = null
                        ensureAutoConnectLoop()
                        stopPositionPolling()
                        _uiState.update { it.copy(statusMessage = state.reason) }
                    }
                }
            }
        }

        ensureAutoConnectLoop()

        // Apply the latest known mower position as soon as Zone screen opens.
        viewModelScope.launch {
            refreshCurrentMowerPosition()
        }

        viewModelScope.launch {
            startRangingUseCase.state.collect { rangingState ->
                val rangedPosition = rangingState.latestPosition?.let { position ->
                    Point2dMm(position.xMm, position.yMm)
                }
                if (rangedPosition != null) {
                    updateMowerPosition(rangedPosition)
                }
            }
        }
    }

    fun onMapTapped(point: Point2dMm) {
        if (!_uiState.value.isNavigationMode) {
            return
        }
        if (hasActiveMovementTarget(_uiState.value)) {
            _uiState.update {
                it.copy(statusMessage = "Movement in progress. Press Stop or wait for arrival before setting a new target")
            }
            return
        }
        queueNavigationTarget(point = point, forceImmediate = true)
    }

    fun onMapTapAndHold(point: Point2dMm) {
        if (!_uiState.value.isNavigationMode) {
            return
        }
        if (hasActiveMovementTarget(_uiState.value)) {
            return
        }
        queueNavigationTarget(point = point, forceImmediate = false)
    }

    fun onEditMowerDrag(point: Point2dMm) {
        queueNavigationTarget(
            point = point,
            forceImmediate = false,
            allowWhenNavigationModeOff = true
        )
    }

    fun setNavigationMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isNavigationMode = enabled,
                statusMessage = if (enabled) "Navigation mode ON" else "Navigation mode OFF"
            )
        }
        if (!enabled) {
            queuedNavigationTarget = null
            navigationDispatchJob?.cancel()
            navigationDispatchJob = null
            stopPositionPolling()
        } else {
            startPositionPollingIfNeeded()
        }
    }

    fun selectAreaType(areaType: ZoneAreaType) {
        _uiState.update {
            clampSelectedIndex(
                it.copy(
                    selectedAreaType = areaType,
                    statusMessage = null
                )
            )
        }
    }

    fun selectZoneIndex(index: Int) {
        _uiState.update { current ->
            val zones = selectedZones(current)
            if (zones.isEmpty()) {
                return@update current
            }
            current.copy(
                selectedZoneIndex = index.coerceIn(0, zones.lastIndex),
                statusMessage = null
            )
        }
    }

    fun selectZone(areaType: ZoneAreaType, index: Int) {
        _uiState.update { current ->
            val zones = if (areaType == ZoneAreaType.AVAILABLE) {
                current.availableZones
            } else {
                current.noGoZones
            }
            if (zones.isEmpty()) {
                return@update current
            }
            current.copy(
                selectedAreaType = areaType,
                selectedZoneIndex = index.coerceIn(0, zones.lastIndex),
                statusMessage = null
            )
        }
    }

    fun addAvailableZone() {
        addZone(ZoneAreaType.AVAILABLE)
    }

    fun addNoGoZone() {
        addZone(ZoneAreaType.NO_GO)
    }

    private fun addZone(areaType: ZoneAreaType) {
        _uiState.update { current ->
            val isAvailable = areaType == ZoneAreaType.AVAILABLE
            val existingZones = if (isAvailable) current.availableZones else current.noGoZones
            val nextIndex = existingZones.size
            val nextZone = ZoneDraft(
                id = if (isAvailable) defaultAvailableId(nextIndex) else defaultNoGoId(nextIndex),
                vertices = if (isAvailable) defaultRectangleVertices(nextIndex) else defaultNoGoVertices(nextIndex)
            )

            val nextState = if (isAvailable) {
                current.copy(
                    selectedAreaType = ZoneAreaType.AVAILABLE,
                    availableZones = existingZones + nextZone,
                    selectedZoneIndex = nextIndex
                )
            } else {
                current.copy(
                    selectedAreaType = ZoneAreaType.NO_GO,
                    noGoZones = existingZones + nextZone,
                    selectedZoneIndex = nextIndex
                )
            }

            nextState.copy(statusMessage = null)
        }
        persistSelectedZonesDraftDebounced()
    }

    fun deleteSelectedZone() {
        _uiState.update { current ->
            val isAvailable = current.selectedAreaType == ZoneAreaType.AVAILABLE
            val zones = selectedZones(current)
            if (zones.isEmpty()) {
                return@update current
            }

            if (isAvailable && zones.size <= 1) {
                return@update current.copy(statusMessage = "At least one available zone is required")
            }

            val deleteIndex = current.selectedZoneIndex.coerceIn(0, zones.lastIndex)
            val nextZones = zones.filterIndexed { index, _ -> index != deleteIndex }
            val nextSelectedIndex = when {
                nextZones.isEmpty() -> 0
                else -> deleteIndex.coerceAtMost(nextZones.lastIndex)
            }

            if (isAvailable) {
                current.copy(
                    availableZones = nextZones,
                    selectedZoneIndex = nextSelectedIndex,
                    statusMessage = "Zone deleted. Press Save to apply"
                )
            } else {
                val nextNoGoZones = if (nextZones.isEmpty()) {
                    emptyList()
                } else {
                    nextZones
                }
                current.copy(
                    noGoZones = nextNoGoZones,
                    selectedZoneIndex = nextSelectedIndex,
                    statusMessage = "Zone deleted. Press Save to apply"
                )
            }
        }
        persistSelectedZonesDraftDebounced()
    }

    fun moveCorner(cornerIndex: Int, point: Point2dMm) {
        _uiState.update { current ->
            val zones = selectedZones(current)
            if (zones.isEmpty()) {
                return@update current
            }

            val selectedIndex = current.selectedZoneIndex.coerceIn(0, zones.lastIndex)
            val activeZone = zones[selectedIndex]
            if (cornerIndex !in activeZone.vertices.indices) {
                return@update current
            }

            val nextVertices = activeZone.vertices.toMutableList().apply {
                this[cornerIndex] = point
            }
            val nextZone = activeZone.copy(vertices = nextVertices)
            val nextZones = zones.toMutableList().apply { this[selectedIndex] = nextZone }
            updateSelectedTypeZones(current, nextZones).copy(statusMessage = null)
        }
        persistSelectedZonesDraftDebounced()
    }

    fun moveSelectedZoneBy(deltaXMm: Int, deltaYMm: Int) {
        if (deltaXMm == 0 && deltaYMm == 0) {
            return
        }

        _uiState.update { current ->
            val zones = selectedZones(current)
            if (zones.isEmpty()) {
                return@update current
            }

            val selectedIndex = current.selectedZoneIndex.coerceIn(0, zones.lastIndex)
            val activeZone = zones[selectedIndex]
            if (activeZone.vertices.isEmpty()) {
                return@update current
            }

            val movedVertices = activeZone.vertices.map {
                Point2dMm(
                    xMm = it.xMm + deltaXMm,
                    yMm = it.yMm + deltaYMm
                )
            }
            val nextZone = activeZone.copy(vertices = movedVertices)
            val nextZones = zones.toMutableList().apply { this[selectedIndex] = nextZone }
            updateSelectedTypeZones(current, nextZones)
        }
        persistSelectedZonesDraftDebounced()
    }

    fun resetDraftToCurrentOrDefault() {
        _uiState.update { current ->
            val zones = selectedZones(current)
            if (zones.isEmpty()) {
                return@update current
            }

            val selectedIndex = current.selectedZoneIndex.coerceIn(0, zones.lastIndex)
            val resetVertices = if (current.selectedAreaType == ZoneAreaType.AVAILABLE) {
                defaultRectangleVertices(selectedIndex)
            } else {
                defaultNoGoVertices(selectedIndex)
            }

            val nextZones = zones.toMutableList().apply {
                this[selectedIndex] = this[selectedIndex].copy(vertices = resetVertices)
            }
            updateSelectedTypeZones(current, nextZones).copy(statusMessage = null)
        }
        persistSelectedZonesDraftDebounced()
    }

    fun resetAllZones() {
        _uiState.update { current ->
            current.copy(
                selectedAreaType = ZoneAreaType.AVAILABLE,
                selectedZoneIndex = 0,
                availableZones = listOf(
                    ZoneDraft(id = defaultAvailableId(0), vertices = defaultRectangleVertices(0))
                ),
                noGoZones = listOf(
                    ZoneDraft(id = defaultNoGoId(0), vertices = defaultNoGoVertices(0))
                ),
                statusMessage = "All zones reset"
            )
        }
        persistAllZonesDraftDebounced()
    }

    fun confirmZone() {
        viewModelScope.launch {
            val state = _uiState.value
            val availableZonesToSave = state.availableZones.map { it.vertices }
            val noGoZonesToSave = state.noGoZones.map { it.vertices }

            _uiState.update { it.copy(isSaving = true, statusMessage = null) }

            defineZoneUseCase.saveAvailableZonesLocally(availableZonesToSave)
            defineZoneUseCase.saveNoGoZonesLocally(noGoZonesToSave)

            _uiState.update {
                it.copy(
                    isSaving = false,
                    statusMessage = "Zones saved locally"
                )
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

            val mower = _uiState.value.mowerPosition
            if (mower == null) {
                _uiState.update { it.copy(statusMessage = "Cannot stop: mower position unknown") }
                return@launch
            }

            val holdPosition = Point2dMm(mower.xMm, mower.yMm)
            when (val result = moveMowerUseCase.moveTo(sessionId = sessionId, target = holdPosition, zone = null)) {
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

    private fun queueNavigationTarget(
        point: Point2dMm,
        forceImmediate: Boolean,
        allowWhenNavigationModeOff: Boolean = false
    ) {
        if (!_uiState.value.isNavigationMode && !allowWhenNavigationModeOff) {
            return
        }
        val target = point
        val nowMs = System.currentTimeMillis()
        val elapsedMs = nowMs - lastNavigationCommandAtMs

        if (forceImmediate || elapsedMs >= NAVIGATION_THROTTLE_MS) {
            lastNavigationCommandAtMs = nowMs
            viewModelScope.launch {
                sendMoveToTarget(target)
            }
            return
        }

        queuedNavigationTarget = target
        if (navigationDispatchJob?.isActive == true) {
            return
        }

        val waitMs = (NAVIGATION_THROTTLE_MS - elapsedMs).coerceAtLeast(1L)
        navigationDispatchJob = viewModelScope.launch {
            delay(waitMs)
            val queuedTarget = queuedNavigationTarget
            queuedNavigationTarget = null
            if (queuedTarget != null && (_uiState.value.isNavigationMode || allowWhenNavigationModeOff)) {
                lastNavigationCommandAtMs = System.currentTimeMillis()
                sendMoveToTarget(queuedTarget)
            }
            navigationDispatchJob = null
        }
    }

    private suspend fun sendMoveToTarget(target: Point2dMm) {
        val sessionId = activeSessionId
        if (sessionId == null) {
            _uiState.update { it.copy(statusMessage = "Not connected") }
            return
        }

        val mower = _uiState.value.mowerPosition
        val availablePolygons = _uiState.value.availableZones.map { it.vertices }.filter { it.size >= 3 }
        val noGoPolygons = _uiState.value.noGoZones.map { it.vertices }.filter { it.size >= 3 }
        val availableClampedTarget = if (mower != null) {
            clampTargetInsideAvailable(start = mower, desiredTarget = target, availablePolygons = availablePolygons)
        } else {
            target
        }
        val noGoClampedTarget = if (mower != null) {
            clampTargetBeforeNoGo(start = mower, desiredTarget = availableClampedTarget, noGoPolygons = noGoPolygons)
        } else {
            availableClampedTarget
        }
        val movementTarget = noGoClampedTarget
        val clampedByAvailable = availableClampedTarget != target
        val clampedByNoGo = noGoClampedTarget != availableClampedTarget

        val vectorDxMm = mower?.let { movementTarget.xMm - it.xMm }
        val vectorDyMm = mower?.let { movementTarget.yMm - it.yMm }
        val distanceMm = if (vectorDxMm != null && vectorDyMm != null) {
            hypot(vectorDxMm.toDouble(), vectorDyMm.toDouble()).roundToInt()
        } else {
            null
        }

        if (mower != null && distanceMm != null && distanceMm <= 1) {
            _uiState.update {
                it.copy(
                    destinationMarker = mower,
                    lastMoveVectorDxMm = 0,
                    lastMoveVectorDyMm = 0,
                    lastMoveDistanceMm = 0,
                    statusMessage = "No-Go boundary reached"
                )
            }
            return
        }

        when (val result = moveMowerUseCase.moveTo(sessionId = sessionId, target = movementTarget, zone = null)) {
            MoveMowerResult.Success -> {
                _uiState.update {
                    it.copy(
                        destinationMarker = movementTarget,
                        lastMoveVectorDxMm = vectorDxMm,
                        lastMoveVectorDyMm = vectorDyMm,
                        lastMoveDistanceMm = distanceMm,
                        statusMessage = if (clampedByNoGo) {
                            "No-Go boundary reached"
                        } else if (clampedByAvailable) {
                            "Available zone boundary reached"
                        } else if (distanceMm != null) {
                            "MOVE_TO sent: distance ${distanceMm} mm"
                        } else {
                            "MOVE_TO sent"
                        }
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

    private suspend fun waitForRangingFeedback(previous: Point2dMm?): Point2dMm? {
        return withTimeoutOrNull(1_500L) {
            var latest: Point2dMm?
            do {
                latest = startRangingUseCase.state.value.latestPosition?.let {
                    Point2dMm(it.xMm, it.yMm)
                }

                if (latest == null || latest == previous) {
                    delay(100L)
                }
            } while (latest == null || latest == previous)

            latest
        }
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
                nearestT = if (nearestT == null) intersectionT else minOf(nearestT ?: intersectionT, intersectionT)
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

    private fun doesSegmentIntersectPolygon(start: Point2dMm, end: Point2dMm, polygon: List<Point2dMm>): Boolean {
        if (polygon.size < 3) {
            return false
        }

        var previous = polygon.last()
        polygon.forEach { current ->
            if (segmentsIntersect(start, end, previous, current)) {
                return true
            }
            previous = current
        }
        return false
    }

    private fun segmentsIntersect(a: Point2dMm, b: Point2dMm, c: Point2dMm, d: Point2dMm): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)

        if (o1 != o2 && o3 != o4) {
            return true
        }

        if (o1 == 0 && onSegment(a, c, b)) return true
        if (o2 == 0 && onSegment(a, d, b)) return true
        if (o3 == 0 && onSegment(c, a, d)) return true
        if (o4 == 0 && onSegment(c, b, d)) return true
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

    private fun startPositionPollingIfNeeded() {
        if (positionPollingJob?.isActive == true) {
            return
        }

        positionPollingJob = viewModelScope.launch {
            while (isActive && activeSessionId != null && _uiState.value.isNavigationMode) {
                readCurrentMowerPositionUseCase.read().getOrNull()?.let { position ->
                    updateMowerPosition(position)
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }

    private fun ensureAutoConnectLoop() {
        if (autoConnectJob?.isActive == true) {
            return
        }

        autoConnectJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                val state = connectMowerUseCase.connectionState.value
                if (state is ConnectionState.Connected) {
                    break
                }
                if (state is ConnectionState.Connecting) {
                    delay(200L)
                    continue
                }
                attempt += 1
                Log.i(TAG, "auto-connect: attempt=$attempt")
                connectMowerUseCase.connect()
                Log.i(TAG, "auto-connect: attempt=$attempt completed state=${connectMowerUseCase.connectionState.value}")
                delay(AUTO_CONNECT_RETRY_MS)
            }
        }
    }

    private fun stopAutoConnectLoop() {
        autoConnectJob?.cancel()
        autoConnectJob = null
    }

    private suspend fun refreshCurrentMowerPosition() {
        readCurrentMowerPositionUseCase.read().getOrNull()?.let { position ->
            updateMowerPosition(position)
        }
    }

    private fun persistSelectedZonesDraftDebounced() {
        draftPersistenceJob?.cancel()
        val snapshot = _uiState.value
        val areaType = snapshot.selectedAreaType
        val zoneVertices = if (areaType == ZoneAreaType.AVAILABLE) {
            snapshot.availableZones.map { it.vertices }
        } else {
            snapshot.noGoZones.map { it.vertices }
        }

        draftPersistenceJob = viewModelScope.launch {
            delay(DRAFT_PERSIST_DEBOUNCE_MS)
            if (areaType == ZoneAreaType.AVAILABLE) {
                defineZoneUseCase.saveAvailableZonesLocally(zoneVertices)
            } else {
                defineZoneUseCase.saveNoGoZonesLocally(zoneVertices)
            }
        }
    }

    private fun persistAllZonesDraftDebounced() {
        draftPersistenceJob?.cancel()
        val snapshot = _uiState.value
        val available = snapshot.availableZones.map { it.vertices }
        val noGo = snapshot.noGoZones.map { it.vertices }

        draftPersistenceJob = viewModelScope.launch {
            delay(DRAFT_PERSIST_DEBOUNCE_MS)
            defineZoneUseCase.saveAvailableZonesLocally(available)
            defineZoneUseCase.saveNoGoZonesLocally(noGo)
        }
    }

    private fun zoneToRectangle(vertices: List<Point2dMm>?): List<Point2dMm>? {
        if (vertices.isNullOrEmpty() || vertices.size < 3) {
            return null
        }

        // Keep exact polygon shape as authored by corner dragging.
        return vertices.toList()
    }

    private fun defaultRectangleVertices(index: Int): List<Point2dMm> {
        val offset = (index * 220)
        val left = (800 + offset).coerceAtMost(MAP_MAX_X_MM - 600)
        val top = (600 + offset).coerceAtMost(MAP_MAX_Y_MM - 600)
        val right = (left + 1800).coerceAtMost(MAP_MAX_X_MM)
        val bottom = (top + 1300).coerceAtMost(MAP_MAX_Y_MM)
        return listOf(
            Point2dMm(left, top),
            Point2dMm(right, top),
            Point2dMm(right, bottom),
            Point2dMm(left, bottom)
        )
    }

    private fun defaultNoGoVertices(index: Int): List<Point2dMm> {
        val offset = (index * 180)
        val left = (2200 + offset).coerceAtMost(MAP_MAX_X_MM - 500)
        val top = (1600 + offset).coerceAtMost(MAP_MAX_Y_MM - 500)
        val right = (left + 900).coerceAtMost(MAP_MAX_X_MM)
        val bottom = (top + 900).coerceAtMost(MAP_MAX_Y_MM)
        return listOf(
            Point2dMm(left, top),
            Point2dMm(right, top),
            Point2dMm(right, bottom),
            Point2dMm(left, bottom)
        )
    }

    private fun selectedZones(state: ZoneUiState): List<ZoneDraft> {
        return if (state.selectedAreaType == ZoneAreaType.AVAILABLE) {
            state.availableZones
        } else {
            state.noGoZones
        }
    }

    private fun selectedZone(state: ZoneUiState): ZoneDraft? {
        val zones = selectedZones(state)
        if (zones.isEmpty()) {
            return null
        }
        val index = state.selectedZoneIndex.coerceIn(0, zones.lastIndex)
        return zones[index]
    }

    private fun selectedVertices(state: ZoneUiState): List<Point2dMm> {
        return selectedZone(state)?.vertices ?: defaultRectangleVertices(0)
    }

    private fun updateSelectedTypeZones(state: ZoneUiState, nextZones: List<ZoneDraft>): ZoneUiState {
        return if (state.selectedAreaType == ZoneAreaType.AVAILABLE) {
            clampSelectedIndex(state.copy(availableZones = nextZones))
        } else {
            clampSelectedIndex(state.copy(noGoZones = nextZones))
        }
    }

    private fun clampSelectedIndex(state: ZoneUiState): ZoneUiState {
        val zones = selectedZones(state)
        if (zones.isEmpty()) {
            return state.copy(selectedZoneIndex = 0)
        }
        return state.copy(selectedZoneIndex = state.selectedZoneIndex.coerceIn(0, zones.lastIndex))
    }

    private fun hasActiveMovementTarget(state: ZoneUiState): Boolean {
        val destination = state.destinationMarker ?: return false
        val mower = state.mowerPosition ?: return true
        val distanceMm = hypot(
            (destination.xMm - mower.xMm).toDouble(),
            (destination.yMm - mower.yMm).toDouble()
        )
        return distanceMm > ARRIVAL_THRESHOLD_MM
    }

    private fun updateMowerPosition(position: Point2dMm) {
        _uiState.update { current ->
            val destination = current.destinationMarker
            if (destination == null) {
                return@update current.copy(mowerPosition = position)
            }

            val distanceMm = hypot(
                (destination.xMm - position.xMm).toDouble(),
                (destination.yMm - position.yMm).toDouble()
            )

            if (distanceMm <= ARRIVAL_THRESHOLD_MM) {
                current.copy(
                    mowerPosition = position,
                    destinationMarker = null,
                    statusMessage = "Destination reached"
                )
            } else {
                current.copy(mowerPosition = position)
            }
        }
    }

    private fun defaultAvailableId(index: Int): String = "$AVAILABLE_ZONE_PREFIX$index"

    private fun defaultNoGoId(index: Int): String = "$NO_GO_ZONE_PREFIX$index"

    private data class RectangleDraft(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun moveCorner(cornerIndex: Int, target: Point2dMm, minSizeMm: Int): RectangleDraft {
            var nextLeft = left
            var nextTop = top
            var nextRight = right
            var nextBottom = bottom

            when (cornerIndex) {
                0 -> {
                    nextLeft = target.xMm.coerceAtMost(right - minSizeMm)
                    nextTop = target.yMm.coerceAtMost(bottom - minSizeMm)
                }

                1 -> {
                    nextRight = target.xMm.coerceAtLeast(left + minSizeMm)
                    nextTop = target.yMm.coerceAtMost(bottom - minSizeMm)
                }

                2 -> {
                    nextRight = target.xMm.coerceAtLeast(left + minSizeMm)
                    nextBottom = target.yMm.coerceAtLeast(top + minSizeMm)
                }

                3 -> {
                    nextLeft = target.xMm.coerceAtMost(right - minSizeMm)
                    nextBottom = target.yMm.coerceAtLeast(top + minSizeMm)
                }
            }

            return RectangleDraft(nextLeft, nextTop, nextRight, nextBottom)
        }

        fun toVertices(): List<Point2dMm> = listOf(
            Point2dMm(left, top),
            Point2dMm(right, top),
            Point2dMm(right, bottom),
            Point2dMm(left, bottom)
        )

        companion object {
            fun fromVertices(vertices: List<Point2dMm>): RectangleDraft? {
                if (vertices.size < 4) {
                    return null
                }

                val minX = vertices.minOf { it.xMm }
                val maxX = vertices.maxOf { it.xMm }
                val minY = vertices.minOf { it.yMm }
                val maxY = vertices.maxOf { it.yMm }
                return RectangleDraft(minX, minY, maxX, maxY)
            }
        }
    }

    private companion object {
        const val TAG = "ZoneViewModel"
        const val MAP_MIN_X_MM = 0
        const val MAP_MIN_Y_MM = 0
        const val MAP_MAX_X_MM = 6000
        const val MAP_MAX_Y_MM = 4500
        const val RECT_MIN_SIZE_MM = 200
        const val NAVIGATION_THROTTLE_MS = 150L
        const val POSITION_POLL_INTERVAL_MS = 200L
        const val DRAFT_PERSIST_DEBOUNCE_MS = 250L
        const val NO_GO_STOP_MARGIN_T = 0.002
        const val AVAILABLE_STOP_MARGIN_T = 0.002
        const val PATH_SAMPLE_STEP_MM = 120.0
        const val ARRIVAL_THRESHOLD_MM = 100.0
        const val AUTO_CONNECT_RETRY_MS = 2_000L
        const val AVAILABLE_ZONE_PREFIX = "zone-available-"
        const val NO_GO_ZONE_PREFIX = "zone-no-go-"
    }
}
