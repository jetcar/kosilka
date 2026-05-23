package com.kosilka.feature.map

import androidx.compose.ui.geometry.Offset
import com.kosilka.domain.model.Point2dMm
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCoordinateConverterTest {

    @Test
    fun `Property 5 - Coordinate Conversion Round-Trip`() {
        val pan = Offset(120f, 80f)
        val zoom = 1.75f
        val pxPerMeterAtZoom1 = 220f
        val original = Point2dMm(xMm = 3_250, yMm = 1_875)

        val screen = MapCoordinateConverter.mapMmToScreen(
            mapPoint = original,
            panOffsetPx = pan,
            zoom = zoom,
            pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
        )

        val roundTrip = MapCoordinateConverter.screenToMapMm(
            screenPoint = screen,
            panOffsetPx = pan,
            zoom = zoom,
            pixelsPerMeterAtZoom1 = pxPerMeterAtZoom1
        )

        assertTrue(kotlin.math.abs(original.xMm - roundTrip.xMm) <= 1)
        assertTrue(kotlin.math.abs(original.yMm - roundTrip.yMm) <= 1)
    }
}
