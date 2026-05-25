package com.kosilka.feature.zone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kosilka.domain.model.Point2dMm
import kotlin.math.roundToInt

@Composable
fun ZoneScreen(
    modifier: Modifier = Modifier,
    viewModel: ZoneViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isEditMode by remember { mutableStateOf(false) }

    val selectedZones = if (uiState.selectedAreaType == ZoneAreaType.AVAILABLE) {
        uiState.availableZones
    } else {
        uiState.noGoZones
    }
    val selectedZoneIndex = if (selectedZones.isEmpty()) 0 else uiState.selectedZoneIndex.coerceIn(0, selectedZones.lastIndex)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Zone Definition", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (uiState.isNavigationMode) {
                "Navigation ON: tap or hold map to send movement, map is locked."
            } else {
                "Navigation OFF: map is scrollable/zoomable. Edit mode enables drag of selected zone and corners."
            }
        )

        uiState.mowerPosition?.let { mower ->
            Text("Mower: (${mower.xMm}, ${mower.yMm})")
        } ?: Text("Mower: unknown")

        if (uiState.lastMoveDistanceMm != null) {
            Text(
                "Vector: dx=${uiState.lastMoveVectorDxMm ?: 0} mm, dy=${uiState.lastMoveVectorDyMm ?: 0} mm, distance=${uiState.lastMoveDistanceMm} mm"
            )
        }

        uiState.statusMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        ZoneRectangleEditor(
            availableZones = uiState.availableZones,
            noGoZones = uiState.noGoZones,
            selectedAreaType = uiState.selectedAreaType,
            selectedZoneIndex = selectedZoneIndex,
            mowerPosition = uiState.mowerPosition,
            destinationMarker = uiState.destinationMarker,
            isNavigationMode = uiState.isNavigationMode,
            isEditMode = isEditMode,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(Color(0xFFF4F7F5))
                .border(1.dp, Color(0xFFCFD8DC)),
            onMoveCorner = viewModel::moveCorner,
            onMoveZone = viewModel::moveSelectedZoneBy,
            onSelectZone = viewModel::selectZone,
            onNavigationTap = viewModel::onMapTapped,
            onNavigationTapAndHold = viewModel::onMapTapAndHold
        )

        if (isEditMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        enabled = !uiState.isNavigationMode,
                        onClick = viewModel::addAvailableZone
                    ) { Text("Add Available Zone") }
                    FilledTonalButton(
                        enabled = !uiState.isNavigationMode,
                        onClick = viewModel::addNoGoZone
                    ) { Text("Add No-Go Zone") }
                }
                Row {
                    FilledTonalButton(
                        enabled = !uiState.isNavigationMode,
                        onClick = viewModel::deleteSelectedZone
                    ) { Text("Delete Selected") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    viewModel.setNavigationMode(!uiState.isNavigationMode)
                }
            ) {
                Text(if (uiState.isNavigationMode) "Stop Navigation" else "Start Navigation")
            }
            FilledTonalButton(
                enabled = !uiState.isNavigationMode,
                onClick = { isEditMode = !isEditMode }
            ) {
                Text(if (isEditMode) "Edit Zone: ON" else "Edit Zone: OFF")
            }
            Button(onClick = viewModel::stopMower) {
                Text("Stop Mower")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = viewModel::resetAllZones) { Text("Reset") }
            Button(onClick = viewModel::confirmZone, enabled = !uiState.isSaving) { Text("Save") }
        }

        Text("${uiState.selectedAreaType}: zone ${selectedZoneIndex + 1} / ${selectedZones.size}")
    }
}

@Composable
private fun ZoneRectangleEditor(
    availableZones: List<ZoneDraft>,
    noGoZones: List<ZoneDraft>,
    selectedAreaType: ZoneAreaType,
    selectedZoneIndex: Int,
    mowerPosition: Point2dMm?,
    destinationMarker: Point2dMm?,
    isNavigationMode: Boolean,
    isEditMode: Boolean,
    modifier: Modifier = Modifier,
    onMoveCorner: (Int, Point2dMm) -> Unit,
    onMoveZone: (Int, Int) -> Unit,
    onSelectZone: (ZoneAreaType, Int) -> Unit,
    onNavigationTap: (Point2dMm) -> Unit,
    onNavigationTapAndHold: (Point2dMm) -> Unit
) {
    val latestMoveCorner by rememberUpdatedState(onMoveCorner)
    val latestMoveZone by rememberUpdatedState(onMoveZone)
    val latestSelectZone by rememberUpdatedState(onSelectZone)
    val latestOnNavigationTap by rememberUpdatedState(onNavigationTap)
    val latestOnNavigationTapAndHold by rememberUpdatedState(onNavigationTapAndHold)

    val activeHandle = remember { mutableStateOf<Int?>(null) }
    var isDraggingMap by remember { mutableStateOf(false) }
    var activeZoneIndex by remember { mutableIntStateOf(-1) }
    var activeZoneType by remember { mutableStateOf<ZoneAreaType?>(null) }
    var lastDragMapPoint by remember { mutableStateOf<Point2dMm?>(null) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var lastCameraCenter by remember { mutableStateOf(DEFAULT_CAMERA_CENTER) }

    val activeZones = if (selectedAreaType == ZoneAreaType.AVAILABLE) availableZones else noGoZones
    val safeSelectedZoneIndex = if (activeZones.isEmpty()) 0 else selectedZoneIndex.coerceIn(0, activeZones.lastIndex)
    val activeVertices = activeZones.getOrNull(safeSelectedZoneIndex)?.vertices ?: emptyList()
    val latestActiveZones by rememberUpdatedState(activeZones)
    val latestSelectedZoneIndex by rememberUpdatedState(safeSelectedZoneIndex)
    val latestAvailableZones by rememberUpdatedState(availableZones)
    val latestNoGoZones by rememberUpdatedState(noGoZones)
    val latestSelectedAreaType by rememberUpdatedState(selectedAreaType)

    val cameraCenter = remember(mowerPosition, isNavigationMode, lastCameraCenter) {
        if (isNavigationMode) mowerPosition ?: lastCameraCenter else DEFAULT_CAMERA_CENTER
    }

    if (isNavigationMode && mowerPosition != null && mowerPosition != lastCameraCenter) {
        lastCameraCenter = mowerPosition
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isNavigationMode) {
                    if (isNavigationMode) {
                        return@pointerInput
                    }
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.6f, 5f)
                        pan += panChange
                    }
                }
                .pointerInput(isNavigationMode, isEditMode, zoom, pan, cameraCenter, selectedAreaType, mowerPosition) {
                    if (isNavigationMode || !isEditMode) {
                        return@pointerInput
                    }
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            isDraggingMap = false

                        val selectedVertices = latestActiveZones.getOrNull(latestSelectedZoneIndex)?.vertices ?: emptyList()
                        activeHandle.value = findNearestHandle(
                            vertices = selectedVertices,
                            size = size,
                            position = startOffset,
                            cameraCenter = cameraCenter,
                            panPx = pan,
                            zoom = zoom,
                            thresholdPx = 48f
                        )

                        if (activeHandle.value == null) {
                            val nearestAcrossTypes = findNearestHandleAcrossTypes(
                                availableZones = latestAvailableZones,
                                noGoZones = latestNoGoZones,
                                size = size,
                                position = startOffset,
                                cameraCenter = cameraCenter,
                                panPx = pan,
                                zoom = zoom,
                                thresholdPx = 48f
                            )
                            if (nearestAcrossTypes != null) {
                                activeHandle.value = nearestAcrossTypes.handleIndex
                                activeZoneType = nearestAcrossTypes.areaType
                                activeZoneIndex = nearestAcrossTypes.zoneIndex
                                latestSelectZone(nearestAcrossTypes.areaType, nearestAcrossTypes.zoneIndex)
                            }
                        }

                        val startMapPoint = screenToMapMm(
                            position = startOffset,
                            size = size,
                            cameraCenter = cameraCenter,
                            panPx = pan,
                            zoom = zoom
                        )
                        lastDragMapPoint = startMapPoint

                        if (activeHandle.value == null) {
                            val tappedZoneSelection = findZoneSelectionAtPoint(
                                availableZones = latestAvailableZones,
                                noGoZones = latestNoGoZones,
                                point = startMapPoint
                            )
                            if (tappedZoneSelection != null) {
                                latestSelectZone(tappedZoneSelection.areaType, tappedZoneSelection.zoneIndex)
                                activeZoneType = tappedZoneSelection.areaType
                                activeZoneIndex = tappedZoneSelection.zoneIndex
                            } else {
                                activeZoneType = null
                                activeZoneIndex = -1
                                isDraggingMap = true
                            }
                        } else if (activeZoneType == null) {
                            activeZoneType = latestSelectedAreaType
                            activeZoneIndex = latestSelectedZoneIndex
                        }
                        },
                        onDragCancel = {
                            isDraggingMap = false
                            activeHandle.value = null
                            activeZoneType = null
                            activeZoneIndex = -1
                            lastDragMapPoint = null
                        },
                        onDragEnd = {
                            isDraggingMap = false
                            activeHandle.value = null
                            activeZoneType = null
                            activeZoneIndex = -1
                            lastDragMapPoint = null
                        }
                    ) { change, dragAmount ->
                        val mapPoint = screenToMapMm(
                            position = change.position,
                            size = size,
                            cameraCenter = cameraCenter,
                            panPx = pan,
                            zoom = zoom
                        )

                        if (isDraggingMap) {
                            pan += dragAmount
                            lastDragMapPoint = mapPoint
                            change.consume()
                            return@detectDragGestures
                        }

                        if (activeHandle.value != null) {
                            val zoneType = activeZoneType
                            if (zoneType != null && activeZoneIndex >= 0) {
                                latestSelectZone(zoneType, activeZoneIndex)
                            }
                            latestMoveCorner(activeHandle.value ?: 0, mapPoint)
                        } else if (activeZoneIndex >= 0) {
                            val zoneType = activeZoneType
                            if (zoneType != null) {
                                latestSelectZone(zoneType, activeZoneIndex)
                            }
                            val previousPoint = lastDragMapPoint
                            if (previousPoint != null) {
                                val deltaX = mapPoint.xMm - previousPoint.xMm
                                val deltaY = mapPoint.yMm - previousPoint.yMm
                                latestMoveZone(deltaX, deltaY)
                            }
                        }

                        lastDragMapPoint = mapPoint
                        change.consume()
                    }
                }
                .pointerInput(isNavigationMode, isEditMode, zoom, pan, cameraCenter, selectedAreaType) {
                    if (isNavigationMode) {
                        return@pointerInput
                    }
                    if (isEditMode) {
                        detectTapGestures { tapOffset ->
                            val mapPoint = screenToMapMm(
                                position = tapOffset,
                                size = size,
                                cameraCenter = cameraCenter,
                                panPx = pan,
                                zoom = zoom
                            )
                            findZoneSelectionAtPoint(
                                availableZones = latestAvailableZones,
                                noGoZones = latestNoGoZones,
                                point = mapPoint
                            )?.let { selection ->
                                latestSelectZone(selection.areaType, selection.zoneIndex)
                            }
                        }
                        return@pointerInput
                    }
                }
                .pointerInput(isNavigationMode, cameraCenter) {
                    if (!isNavigationMode) {
                        return@pointerInput
                    }
                    detectTapGestures { tapOffset ->
                        val mapPoint = screenToMapMm(
                            position = tapOffset,
                            size = size,
                            cameraCenter = cameraCenter,
                            panPx = Offset.Zero,
                            zoom = 1f
                        )
                        latestOnNavigationTap(mapPoint)
                    }
                }
                .pointerInput(isNavigationMode, cameraCenter) {
                    if (!isNavigationMode) {
                        return@pointerInput
                    }
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val startPoint = screenToMapMm(
                                position = startOffset,
                                size = size,
                                cameraCenter = cameraCenter,
                                panPx = Offset.Zero,
                                zoom = 1f
                            )
                            latestOnNavigationTapAndHold(startPoint)
                        }
                    ) { change, _ ->
                        val mapPoint = screenToMapMm(
                            position = change.position,
                            size = size,
                            cameraCenter = cameraCenter,
                            panPx = Offset.Zero,
                            zoom = 1f
                        )
                        latestOnNavigationTapAndHold(mapPoint)
                        change.consume()
                    }
                }
        ) {
            val activePan = if (isNavigationMode) Offset.Zero else pan
            val activeZoom = if (isNavigationMode) 1f else zoom

        drawGrid(
            size = size,
            cameraCenter = cameraCenter,
            panPx = activePan,
            zoom = activeZoom
        )

        drawZoneCollection(
            zones = availableZones,
            baseFill = Color(0x332196F3),
            baseStroke = Color(0xFF1976D2),
            selectedFill = Color(0x662196F3),
            selectedStroke = Color(0xFF0D47A1),
            isSelectedType = selectedAreaType == ZoneAreaType.AVAILABLE,
            selectedIndex = safeSelectedZoneIndex,
            cameraCenter = cameraCenter,
            panPx = activePan,
            zoom = activeZoom,
            size = size,
            drawHandles = !isNavigationMode && isEditMode && selectedAreaType == ZoneAreaType.AVAILABLE,
            activeHandleIndex = activeHandle.value
        )

        drawZoneCollection(
            zones = noGoZones,
            baseFill = Color(0x33EF5350),
            baseStroke = Color(0xFFD32F2F),
            selectedFill = Color(0x66EF5350),
            selectedStroke = Color(0xFFB71C1C),
            isSelectedType = selectedAreaType == ZoneAreaType.NO_GO,
            selectedIndex = safeSelectedZoneIndex,
            cameraCenter = cameraCenter,
            panPx = activePan,
            zoom = activeZoom,
            size = size,
            drawHandles = !isNavigationMode && isEditMode && selectedAreaType == ZoneAreaType.NO_GO,
            activeHandleIndex = activeHandle.value
        )

        destinationMarker?.let { destination ->
            val destinationScreen = mapToScreen(
                point = destination,
                size = size,
                cameraCenter = cameraCenter,
                panPx = activePan,
                zoom = activeZoom
            )
            drawCircle(color = Color(0xFFFF9800), center = destinationScreen, radius = 10f)
        }

            if (isNavigationMode) {
                val mowerScreen = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = Color(0xFFD84315), center = mowerScreen, radius = 12f)
                drawCircle(color = Color(0xFFFFCCBC), center = mowerScreen, radius = 4f)
            } else {
                mowerPosition?.let { mower ->
                    val mowerScreen = mapToScreen(
                        point = mower,
                        size = size,
                        cameraCenter = cameraCenter,
                        panPx = activePan,
                        zoom = activeZoom
                    )
                    drawCircle(color = Color(0xFFD84315), center = mowerScreen, radius = 12f)
                    drawCircle(color = Color(0xFFFFCCBC), center = mowerScreen, radius = 4f)
                }
            }
        }

        if (!isNavigationMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { zoom = (zoom * 1.2f).coerceIn(0.6f, 5f) }
                ) {
                    Text("+")
                }
                FilledTonalButton(
                    onClick = { zoom = (zoom / 1.2f).coerceIn(0.6f, 5f) }
                ) {
                    Text("-")
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawZoneCollection(
    zones: List<ZoneDraft>,
    baseFill: Color,
    baseStroke: Color,
    selectedFill: Color,
    selectedStroke: Color,
    isSelectedType: Boolean,
    selectedIndex: Int,
    cameraCenter: Point2dMm,
    panPx: Offset,
    zoom: Float,
    size: Size,
    drawHandles: Boolean,
    activeHandleIndex: Int?
) {
    zones.forEachIndexed { index, zone ->
        if (zone.vertices.size < 4) {
            return@forEachIndexed
        }

        val path = Path()
        zone.vertices.forEachIndexed { vertexIndex, vertex ->
            val screen = mapToScreen(
                point = vertex,
                size = size,
                cameraCenter = cameraCenter,
                panPx = panPx,
                zoom = zoom
            )
            if (vertexIndex == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        path.close()

        val isSelected = isSelectedType && index == selectedIndex
        drawPath(path = path, color = if (isSelected) selectedFill else baseFill)
        drawPath(path = path, color = if (isSelected) selectedStroke else baseStroke)

        if (isSelected && drawHandles) {
            zone.vertices.forEachIndexed { handleIndex, vertex ->
                val handleCenter = mapToScreen(
                    point = vertex,
                    size = size,
                    cameraCenter = cameraCenter,
                    panPx = panPx,
                    zoom = zoom
                )
                val isActive = activeHandleIndex == handleIndex
                drawCircle(
                    color = if (isActive) Color(0xFF0D47A1) else Color(0xFF1565C0),
                    center = handleCenter,
                    radius = if (isActive) 22f else 18f
                )
                drawCircle(
                    color = Color.White,
                    center = handleCenter,
                    radius = 8f
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    size: Size,
    cameraCenter: Point2dMm,
    panPx: Offset,
    zoom: Float
) {
    val spacingMm = 500

    var x = MAP_MIN_X_MM
    while (x <= MAP_MAX_X_MM) {
        val top = mapToScreen(
            point = Point2dMm(x, MAP_MIN_Y_MM),
            size = size,
            cameraCenter = cameraCenter,
            panPx = panPx,
            zoom = zoom
        )
        val bottom = mapToScreen(
            point = Point2dMm(x, MAP_MAX_Y_MM),
            size = size,
            cameraCenter = cameraCenter,
            panPx = panPx,
            zoom = zoom
        )
        drawLine(
            color = Color(0xFFE0E6EA),
            start = top,
            end = bottom,
            strokeWidth = 1f
        )
        x += spacingMm
    }

    var y = MAP_MIN_Y_MM
    while (y <= MAP_MAX_Y_MM) {
        val left = mapToScreen(
            point = Point2dMm(MAP_MIN_X_MM, y),
            size = size,
            cameraCenter = cameraCenter,
            panPx = panPx,
            zoom = zoom
        )
        val right = mapToScreen(
            point = Point2dMm(MAP_MAX_X_MM, y),
            size = size,
            cameraCenter = cameraCenter,
            panPx = panPx,
            zoom = zoom
        )
        drawLine(
            color = Color(0xFFE0E6EA),
            start = left,
            end = right,
            strokeWidth = 1f
        )
        y += spacingMm
    }
}

private fun mapToScreen(
    point: Point2dMm,
    size: Size,
    cameraCenter: Point2dMm,
    panPx: Offset,
    zoom: Float
): Offset {
    val basePointX = (point.xMm.toFloat() / MAP_WIDTH_MM.toFloat()) * size.width
    val basePointY = ((MAP_HEIGHT_MM.toFloat() - point.yMm.toFloat()) / MAP_HEIGHT_MM.toFloat()) * size.height
    val baseCenterX = (cameraCenter.xMm.toFloat() / MAP_WIDTH_MM.toFloat()) * size.width
    val baseCenterY = ((MAP_HEIGHT_MM.toFloat() - cameraCenter.yMm.toFloat()) / MAP_HEIGHT_MM.toFloat()) * size.height

    val centerX = size.width / 2f
    val centerY = size.height / 2f

    val dx = (basePointX - baseCenterX) * zoom
    val dy = (basePointY - baseCenterY) * zoom

    return Offset(centerX + dx + panPx.x, centerY + dy + panPx.y)
}

private fun screenToMapMm(
    position: Offset,
    size: IntSize,
    cameraCenter: Point2dMm,
    panPx: Offset,
    zoom: Float
): Point2dMm {
    val width = size.width.toFloat().coerceAtLeast(1f)
    val height = size.height.toFloat().coerceAtLeast(1f)

    val centerX = width / 2f
    val centerY = height / 2f

    val baseCenterX = (cameraCenter.xMm.toFloat() / MAP_WIDTH_MM.toFloat()) * width
    val baseCenterY = ((MAP_HEIGHT_MM.toFloat() - cameraCenter.yMm.toFloat()) / MAP_HEIGHT_MM.toFloat()) * height

    val safeZoom = zoom.coerceAtLeast(0.0001f)
    val basePointX = baseCenterX + ((position.x - centerX - panPx.x) / safeZoom)
    val basePointY = baseCenterY + ((position.y - centerY - panPx.y) / safeZoom)

    return Point2dMm(
        xMm = ((basePointX / width) * MAP_WIDTH_MM).roundToInt(),
        yMm = ((1f - (basePointY / height)) * MAP_HEIGHT_MM).roundToInt()
    )
}

private fun findNearestHandle(
    vertices: List<Point2dMm>,
    size: IntSize,
    position: Offset,
    cameraCenter: Point2dMm,
    panPx: Offset,
    zoom: Float,
    thresholdPx: Float
): Int? {
    if (vertices.isEmpty()) {
        return null
    }

    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    vertices.forEachIndexed { index, vertex ->
        val screen = mapToScreen(
            point = vertex,
            size = Size(size.width.toFloat(), size.height.toFloat()),
            cameraCenter = cameraCenter,
            panPx = panPx,
            zoom = zoom
        )
        val distance = (screen - position).getDistance()
        if (distance < bestDistance && distance <= thresholdPx) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

private fun findZoneAtPoint(zones: List<ZoneDraft>, point: Point2dMm): Int? {
    zones.forEachIndexed { index, zone ->
        if (zone.vertices.size < 3) {
            return@forEachIndexed
        }
        if (isPointInsidePolygon(point, zone.vertices)) {
            return index
        }
    }
    return null
}

private fun isPointInsidePolygon(point: Point2dMm, vertices: List<Point2dMm>): Boolean {
    if (vertices.size < 3) {
        return false
    }

    var inside = false
    var previous = vertices.last()
    vertices.forEach { current ->
        val pointY = point.yMm.toDouble()
        val pointX = point.xMm.toDouble()
        val currentY = current.yMm.toDouble()
        val previousY = previous.yMm.toDouble()
        val currentX = current.xMm.toDouble()
        val previousX = previous.xMm.toDouble()

        val intersects = ((currentY > pointY) != (previousY > pointY)) &&
            (pointX < ((previousX - currentX) * (pointY - currentY) / (previousY - currentY)) + currentX)
        if (intersects) {
            inside = !inside
        }
        previous = current
    }

    return inside
}

private data class ZoneSelection(
    val areaType: ZoneAreaType,
    val zoneIndex: Int
)

private data class ZoneHandleSelection(
    val areaType: ZoneAreaType,
    val zoneIndex: Int,
    val handleIndex: Int
)

private fun findZoneSelectionAtPoint(
    availableZones: List<ZoneDraft>,
    noGoZones: List<ZoneDraft>,
    point: Point2dMm
): ZoneSelection? {
    findZoneAtPoint(noGoZones, point)?.let { zoneIndex ->
        return ZoneSelection(ZoneAreaType.NO_GO, zoneIndex)
    }
    findZoneAtPoint(availableZones, point)?.let { zoneIndex ->
        return ZoneSelection(ZoneAreaType.AVAILABLE, zoneIndex)
    }
    return null
}

private fun findNearestHandleAcrossTypes(
    availableZones: List<ZoneDraft>,
    noGoZones: List<ZoneDraft>,
    size: IntSize,
    position: Offset,
    cameraCenter: Point2dMm,
    panPx: Offset,
    zoom: Float,
    thresholdPx: Float
): ZoneHandleSelection? {
    var best: ZoneHandleSelection? = null
    var bestDistance = Float.MAX_VALUE

    fun inspect(areaType: ZoneAreaType, zones: List<ZoneDraft>) {
        zones.forEachIndexed { zoneIndex, zone ->
            zone.vertices.forEachIndexed { handleIndex, vertex ->
                val screen = mapToScreen(
                    point = vertex,
                    size = Size(size.width.toFloat(), size.height.toFloat()),
                    cameraCenter = cameraCenter,
                    panPx = panPx,
                    zoom = zoom
                )
                val distance = (screen - position).getDistance()
                if (distance <= thresholdPx && distance < bestDistance) {
                    bestDistance = distance
                    best = ZoneHandleSelection(areaType, zoneIndex, handleIndex)
                }
            }
        }
    }

    inspect(ZoneAreaType.NO_GO, noGoZones)
    inspect(ZoneAreaType.AVAILABLE, availableZones)
    return best
}

private const val MAP_MIN_X_MM = 0
private const val MAP_MIN_Y_MM = 0
private const val MAP_MAX_X_MM = 6000
private const val MAP_MAX_Y_MM = 4500
private const val MAP_WIDTH_MM = 6000
private const val MAP_HEIGHT_MM = 4500
private val DEFAULT_CAMERA_CENTER = Point2dMm(MAP_WIDTH_MM / 2, MAP_HEIGHT_MM / 2)
