package com.kosilka.feature.zone

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kosilka.core.ui.design.KosilkaTheme
import com.kosilka.domain.model.Point2dMm

private fun rectZone(id: String, x0: Int, y0: Int, x1: Int, y1: Int) = ZoneDraft(
    id = id,
    vertices = listOf(
        Point2dMm(x0, y0),
        Point2dMm(x1, y0),
        Point2dMm(x1, y1),
        Point2dMm(x0, y1)
    )
)

@Preview(name = "Zone – empty", showBackground = true)
@Composable
private fun ZoneEmptyPreview() {
    KosilkaTheme {
        ZoneScreenContent(uiState = ZoneUiState())
    }
}

@Preview(name = "Zone – with zones", showBackground = true)
@Composable
private fun ZoneWithZonesPreview() {
    KosilkaTheme {
        ZoneScreenContent(
            uiState = ZoneUiState(
                availableZones = listOf(rectZone("a1", 300, 300, 4000, 3500)),
                noGoZones = listOf(rectZone("n1", 1500, 1500, 2500, 2500)),
                selectedAreaType = ZoneAreaType.AVAILABLE,
                selectedZoneIndex = 0,
                mowerPosition = Point2dMm(1000, 1000)
            )
        )
    }
}

@Preview(name = "Zone – edit mode with zones", showBackground = true)
@Composable
private fun ZoneEditModePreview() {
    KosilkaTheme {
        ZoneScreenContent(
            uiState = ZoneUiState(
                availableZones = listOf(rectZone("a1", 300, 300, 4000, 3500)),
                noGoZones = listOf(rectZone("n1", 1500, 1500, 2500, 2500)),
                circleNoGoZones = listOf(
                    CircleNoGoDraft("c1", Point2dMm(3200, 3000), 400)
                ),
                selectedAreaType = ZoneAreaType.AVAILABLE,
                selectedZoneIndex = 0,
                mowerPosition = Point2dMm(1000, 1000)
            ),
            initialEditMode = true
        )
    }
}

@Preview(name = "Zone – circle selected in edit mode", showBackground = true)
@Composable
private fun ZoneCircleSelectedPreview() {
    KosilkaTheme {
        ZoneScreenContent(
            uiState = ZoneUiState(
                availableZones = listOf(rectZone("a1", 300, 300, 4000, 3500)),
                circleNoGoZones = listOf(
                    CircleNoGoDraft("c1", Point2dMm(2000, 2000), 600),
                    CircleNoGoDraft("c2", Point2dMm(4000, 1000), 300)
                ),
                selectedAreaType = ZoneAreaType.NO_GO,
                selectedNoGoIsCircle = true,
                selectedZoneIndex = 0
            ),
            initialEditMode = true
        )
    }
}

@Preview(name = "Zone – navigation mode", showBackground = true)
@Composable
private fun ZoneNavigationModePreview() {
    KosilkaTheme {
        ZoneScreenContent(
            uiState = ZoneUiState(
                availableZones = listOf(rectZone("a1", 300, 300, 4000, 3500)),
                isNavigationMode = true,
                mowerPosition = Point2dMm(800, 800),
                destinationMarker = Point2dMm(3000, 2000),
                lastMoveVectorDxMm = 120,
                lastMoveVectorDyMm = -50,
                lastMoveDistanceMm = 130,
                statusMessage = "Moving…"
            )
        )
    }
}
