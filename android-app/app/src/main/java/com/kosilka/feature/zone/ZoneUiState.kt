package com.kosilka.feature.zone

import com.kosilka.domain.model.Point2dMm

data class ZoneDraft(
    val id: String,
    val vertices: List<Point2dMm>
)

enum class ZoneAreaType {
    AVAILABLE,
    NO_GO
}

data class CircleNoGoDraft(
    val id: String,
    val center: Point2dMm,
    val radiusMm: Int
)

data class ZoneUiState(
    val availableZones: List<ZoneDraft> = emptyList(),
    val noGoZones: List<ZoneDraft> = emptyList(),
    val circleNoGoZones: List<CircleNoGoDraft> = emptyList(),
    val selectedAreaType: ZoneAreaType = ZoneAreaType.AVAILABLE,
    val selectedZoneIndex: Int = 0,
    val selectedNoGoIsCircle: Boolean = false,
    val mowerPosition: Point2dMm? = null,
    val isNavigationMode: Boolean = false,
    val destinationMarker: Point2dMm? = null,
    val lastMoveVectorDxMm: Int? = null,
    val lastMoveVectorDyMm: Int? = null,
    val lastMoveDistanceMm: Int? = null,
    val statusMessage: String? = null,
    val isSaving: Boolean = false
)
