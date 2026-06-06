package com.kosilka.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kosilka.core.ui.design.KosilkaTheme
import com.kosilka.domain.model.Anchor
import com.kosilka.domain.model.MowerPosition
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone

@Preview(name = "Map – idle", showBackground = true)
@Composable
private fun MapIdlePreview() {
    KosilkaTheme {
        MapScreenContent(uiState = MapUiState())
    }
}

@Preview(name = "Map – ranging active", showBackground = true)
@Composable
private fun MapRangingPreview() {
    KosilkaTheme {
        MapScreenContent(
            uiState = MapUiState(
                isRangingActive = true,
                isConnected = true,
                anchors = listOf(
                    Anchor("a1", 0, 0, "A"),
                    Anchor("a2", 6000, 0, "B"),
                    Anchor("a3", 0, 4500, "C")
                ),
                mowerPosition = MowerPosition(xMm = 1500, yMm = 2000, timestampMs = 0),
                sweepWidthMm = 350,
                coveragePercent = 34.5f
            )
        )
    }
}

@Preview(name = "Map – with zone selected", showBackground = true)
@Composable
private fun MapWithZonePreview() {
    KosilkaTheme {
        MapScreenContent(
            uiState = MapUiState(
                isConnected = true,
                availableZones = listOf(
                    Zone(
                        id = "z1",
                        vertices = listOf(
                            Point2dMm(500, 500),
                            Point2dMm(3000, 500),
                            Point2dMm(3000, 3000),
                            Point2dMm(500, 3000)
                        )
                    )
                ),
                selectedAvailableZoneIndex = 0,
                coveragePercent = 12.0f,
                statusMessage = "Navigating to target…"
            )
        )
    }
}

@Preview(name = "Map – position lost", showBackground = true)
@Composable
private fun MapPositionLostPreview() {
    KosilkaTheme {
        MapScreenContent(
            uiState = MapUiState(
                isConnected = true,
                isRangingActive = true,
                isPositionLost = true
            )
        )
    }
}
