package com.kosilka.feature.map

import com.kosilka.domain.model.Anchor
import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.MowerPosition
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone

data class MapUiState(
    val anchors: List<Anchor> = emptyList(),
    val mowerPosition: MowerPosition? = null,
    val zone: Zone? = null,
    val coverageSegments: List<CoverageSegment> = emptyList(),
    val coveragePercent: Float = 0f,
    val destinationMarker: Point2dMm? = null,
    val isPositionLost: Boolean = false,
    val isConnected: Boolean = false,
    val isRangingActive: Boolean = false,
    val statusMessage: String? = null
)
