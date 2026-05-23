package com.kosilka.feature.zone

import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone

data class ZoneUiState(
    val draftVertices: List<Point2dMm> = emptyList(),
    val currentZone: Zone? = null,
    val statusMessage: String? = null,
    val isSaving: Boolean = false
)
