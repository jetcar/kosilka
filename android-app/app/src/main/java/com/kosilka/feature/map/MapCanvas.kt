package com.kosilka.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone

@Composable
fun MapCanvas(
    state: MapUiState,
    modifier: Modifier = Modifier,
    tapEnabled: Boolean = true,
    onTapMap: (Point2dMm) -> Unit = {}
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7F5))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.5f, 4f)
                        pan += panChange
                    }
                }
                .pointerInput(state.destinationMarker, zoom, pan) {
                    detectTapToMove(
                        tapEnabled = tapEnabled,
                        panOffsetPx = pan,
                        zoom = zoom,
                        onTapMap = onTapMap
                    )
                }
        ) {
            val pxPerMeterAtZoom1 = 120f

        // Available zones (bottom layer)
        drawZoneLayer(
            zones = state.availableZones,
            fillColor = Color(0x552196F3),
            strokeColor = Color(0xFF1976D2),
            pan = pan,
            zoom = zoom,
            pxPerMeterAtZoom1 = pxPerMeterAtZoom1
        )

        // No-go zones (drawn above available)
        drawZoneLayer(
            zones = state.noGoZones,
            fillColor = Color(0x55EF5350),
            strokeColor = Color(0xFFD32F2F),
            pan = pan,
            zoom = zoom,
            pxPerMeterAtZoom1 = pxPerMeterAtZoom1
        )

        // Coverage chunk outlines (drawn above no-go to show the decomposition grid)
        state.coverageChunks.forEach { chunk ->
            val topLeft = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(chunk.cellMinXMm, chunk.cellMaxYMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            val bottomRight = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(chunk.cellMaxXMm, chunk.cellMinYMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            val w = bottomRight.x - topLeft.x
            val h = bottomRight.y - topLeft.y
            if (w > 0 && h > 0) {
                drawRect(
                    color = Color(0xCC4CAF50),
                    topLeft = topLeft,
                    size = Size(w, h),
                    style = Stroke(width = 3f)
                )
            }
        }

        // Planned sweep path inside zones (shown before live coverage)
        val expectedToRender = selectExpectedSegmentsForRender(state.expectedCoverageSegments)
        expectedToRender.forEach { segment ->
            val from = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(segment.fromXMm, segment.fromYMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            val to = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(segment.toXMm, segment.toYMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            drawLine(
                color = Color(0x8890A4AE),
                start = from,
                end = to,
                strokeWidth = 4f
            )
        }

        // Coverage overlay can become very large; render recent tail to keep gestures smooth.
        val coverageToRender = selectCoverageSegmentsForRender(state.coverageSegments)
        coverageToRender.forEach { segment ->
            val from = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(segment.fromXMm, segment.fromYMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            val to = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(segment.toXMm, segment.toYMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            drawLine(
                color = Color(0xAA4CAF50),
                start = from,
                end = to,
                strokeWidth = 8f
            )
        }

        val mowerOffset = state.mowerPosition?.let { mower ->
            MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(mower.xMm, mower.yMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
        }

        val destinationOffset = state.destinationMarker?.let { destination ->
            MapCoordinateConverter.mapMmToScreen(
                mapPoint = destination,
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
        }

        if (mowerOffset != null && destinationOffset != null) {
            drawLine(
                color = Color(0xCCFB8C00),
                start = mowerOffset,
                end = destinationOffset,
                strokeWidth = 3f
            )
        }

        // Destination marker
        destinationOffset?.let { offset ->
            drawCircle(color = Color(0xFFFF9800), radius = 12f, center = offset)
        }

            // Mower marker
            mowerOffset?.let { offset ->
                drawCircle(color = Color(0xFFD32F2F), radius = 14f, center = offset)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = { zoom = (zoom * 1.2f).coerceIn(0.5f, 4f) }) {
                Text("+")
            }
            FilledTonalButton(onClick = { zoom = (zoom / 1.2f).coerceIn(0.5f, 4f) }) {
                Text("-")
            }
        }
    }
}

private fun selectCoverageSegmentsForRender(segments: List<CoverageSegment>): List<CoverageSegment> {
    if (segments.size <= MAX_RENDERED_COVERAGE_SEGMENTS) {
        return segments
    }
    val startIndex = (segments.size - MAX_RENDERED_COVERAGE_SEGMENTS).coerceAtLeast(0)
    return segments.subList(startIndex, segments.size)
}

private fun selectExpectedSegmentsForRender(segments: List<CoverageSegment>): List<CoverageSegment> {
    if (segments.size <= MAX_RENDERED_EXPECTED_SEGMENTS) {
        return segments
    }
    val startIndex = (segments.size - MAX_RENDERED_EXPECTED_SEGMENTS).coerceAtLeast(0)
    return segments.subList(startIndex, segments.size)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawZoneLayer(
    zones: List<Zone>,
    fillColor: Color,
    strokeColor: Color,
    pan: Offset,
    zoom: Float,
    pxPerMeterAtZoom1: Float
) {
    zones.forEach { zone ->
        if (zone.vertices.size < 3) {
            return@forEach
        }

        val path = Path()
        zone.vertices.forEachIndexed { index, point ->
            val offset = MapCoordinateConverter.mapMmToScreen(
                mapPoint = point,
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            if (index == 0) {
                path.moveTo(offset.x, offset.y)
            } else {
                path.lineTo(offset.x, offset.y)
            }
        }
        path.close()

        drawPath(color = fillColor, path = path)
        drawPath(color = strokeColor, path = path)
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapToMove(
    tapEnabled: Boolean,
    panOffsetPx: Offset,
    zoom: Float,
    onTapMap: (Point2dMm) -> Unit
) {
    awaitPointerEventScope {
        while (true) {
            val downEvent = awaitPointerEvent(PointerEventPass.Main)
            val downChange = downEvent.changes.firstOrNull { it.pressed } ?: continue

            val pointerId = downChange.id
            var releasePosition: Offset? = null

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val trackedChange = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!trackedChange.pressed) {
                    releasePosition = trackedChange.position
                    break
                }
            }

            if (!tapEnabled || releasePosition == null) {
                continue
            }

            val point = MapCoordinateConverter.screenToMapMm(
                screenPoint = releasePosition,
                panOffsetPx = panOffsetPx,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = 120f
            )
            onTapMap(point)
        }
    }
}

private const val MAX_RENDERED_COVERAGE_SEGMENTS = 1200
private const val MAX_RENDERED_EXPECTED_SEGMENTS = 4000
