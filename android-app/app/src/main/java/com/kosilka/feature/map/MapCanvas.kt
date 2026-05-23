package com.kosilka.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.kosilka.domain.model.Point2dMm

@Composable
fun MapCanvas(
    state: MapUiState,
    modifier: Modifier = Modifier,
    tapEnabled: Boolean = true,
    onTapMap: (Point2dMm) -> Unit = {}
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7F5))
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

        // Anchors
        state.anchors.forEach { anchor ->
            val offset = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(anchor.xMm, anchor.yMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            drawCircle(color = Color(0xFF2E7D32), radius = 10f, center = offset)
        }

        // Zone polygon
        state.zone?.let { zone ->
            if (zone.vertices.size >= 3) {
                val path = Path()
                zone.vertices.forEachIndexed { index, point ->
                    val offset = MapCoordinateConverter.mapMmToScreen(
                        mapPoint = point,
                        panOffsetPx = pan,
                        zoom = zoom,
                        pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
                    )
                    if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                }
                path.close()
                drawPath(color = Color(0x552196F3), path = path)
                drawPath(color = Color(0xFF1976D2), path = path)
            }
        }

        // Coverage overlay (segments)
        state.coverageSegments.forEach { segment ->
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

        // Destination marker
        state.destinationMarker?.let { destination ->
            val offset = MapCoordinateConverter.mapMmToScreen(
                mapPoint = destination,
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            drawCircle(color = Color(0xFFFF9800), radius = 12f, center = offset)
        }

        // Mower marker
        state.mowerPosition?.let { mower ->
            val offset = MapCoordinateConverter.mapMmToScreen(
                mapPoint = Point2dMm(mower.xMm, mower.yMm),
                panOffsetPx = pan,
                zoom = zoom,
                pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
            )
            drawCircle(color = Color(0xFFD32F2F), radius = 14f, center = offset)
        }
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
