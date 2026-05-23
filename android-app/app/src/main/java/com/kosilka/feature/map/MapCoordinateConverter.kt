package com.kosilka.feature.map

import androidx.compose.ui.geometry.Offset
import com.kosilka.domain.model.Point2dMm

/**
 * Converts between on-screen pixel coordinates and map millimeter coordinates.
 */
object MapCoordinateConverter {

    fun screenToMapMm(
        screenPoint: Offset,
        panOffsetPx: Offset,
        zoom: Float,
        pixelsPerMeterAtZoom1: Float
    ): Point2dMm {
        val pxPerMm = (pixelsPerMeterAtZoom1 * zoom) / 1_000f
        val mapXmm = ((screenPoint.x - panOffsetPx.x) / pxPerMm).toInt()
        val mapYmm = ((screenPoint.y - panOffsetPx.y) / pxPerMm).toInt()
        return Point2dMm(xMm = mapXmm, yMm = mapYmm)
    }

    fun mapMmToScreen(
        mapPoint: Point2dMm,
        panOffsetPx: Offset,
        zoom: Float,
        pixelsPerMeterAtZoom1: Float
    ): Offset {
        val pxPerMm = (pixelsPerMeterAtZoom1 * zoom) / 1_000f
        val screenX = panOffsetPx.x + (mapPoint.xMm * pxPerMm)
        val screenY = panOffsetPx.y + (mapPoint.yMm * pxPerMm)
        return Offset(x = screenX, y = screenY)
    }
}
