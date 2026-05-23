package com.kosilka.feature.debug

import com.kosilka.domain.model.Point2dMm

data class EmulatorUiState(
    val activeScenarioLabel: String = "Normal",
    val currentPosition: Point2dMm = Point2dMm(0, 0),
    val driftRateMmPerSec: String = "80",
    val durationMs: String = "5000"
)
