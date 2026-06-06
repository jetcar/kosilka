package com.kosilka.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kosilka.core.ui.StatusMessageSlot
import com.kosilka.core.ui.design.KosilkaDesign
import com.kosilka.core.ui.design.KosilkaScreenScaffold

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    MapScreenContent(
        uiState = uiState,
        modifier = modifier,
        onStartRanging = viewModel::startRanging,
        onStopRanging = viewModel::stopRanging,
        onSelectZone = viewModel::selectAvailableZone,
        onStopMower = viewModel::stopMower,
        onDecreaseSweepWidth = viewModel::decreaseSweepWidth,
        onIncreaseSweepWidth = viewModel::increaseSweepWidth,
        onMapTapped = viewModel::onMapTapped,
        onToggleUwbTag = viewModel::toggleUwbTag
    )
}

@Composable
internal fun MapScreenContent(
    uiState: MapUiState,
    modifier: Modifier = Modifier,
    onStartRanging: () -> Unit = {},
    onStopRanging: () -> Unit = {},
    onSelectZone: (Int) -> Unit = {},
    onStopMower: () -> Unit = {},
    onDecreaseSweepWidth: () -> Unit = {},
    onIncreaseSweepWidth: () -> Unit = {},
    onMapTapped: (com.kosilka.domain.model.Point2dMm) -> Unit = {},
    onToggleUwbTag: (String) -> Unit = {}
) {
    var isNavigateToMode by remember { mutableStateOf(false) }
    val spacing = KosilkaDesign.spacing

    KosilkaScreenScaffold(
        title = "Live Map",
        subtitle = "Ranging, coverage and manual control",
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small.dp)
        ) {
            Button(onClick = onStartRanging) {
                Text("Start Ranging")
            }
            Button(onClick = onStopRanging) {
                Text("Stop Ranging")
            }
        }

        val availableZoneCount = uiState.availableZones.size
        val selectedZoneIndex = if (availableZoneCount == 0) {
            0
        } else {
            uiState.selectedAvailableZoneIndex.coerceIn(0, availableZoneCount - 1)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small.dp)
        ) {
            Button(
                enabled = availableZoneCount > 0 && selectedZoneIndex > 0,
                onClick = { onSelectZone(selectedZoneIndex - 1) }
            ) {
                Text("Zone -")
            }
            Button(
                enabled = availableZoneCount > 0 && selectedZoneIndex < availableZoneCount - 1,
                onClick = { onSelectZone(selectedZoneIndex + 1) }
            ) {
                Text("Zone +")
            }
            Text(
                text = if (availableZoneCount == 0) {
                    "Selected zone: none"
                } else {
                    "Selected zone: ${selectedZoneIndex + 1}/$availableZoneCount"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = if (isNavigateToMode) {
                "Navigate To ON: tap map to send movement target."
            } else {
                "Navigate To OFF: map is inspect-only."
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small.dp)
        ) {
            Button(onClick = { isNavigateToMode = !isNavigateToMode }) {
                Text(if (isNavigateToMode) "Navigate To: ON" else "Navigate To: OFF")
            }
            Button(onClick = onStopMower) {
                Text("Stop Mower")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small.dp)
        ) {
            Button(onClick = onDecreaseSweepWidth) {
                Text("Path -")
            }
            Button(onClick = onIncreaseSweepWidth) {
                Text("Path +")
            }
            Text(
                text = "Path width: ${uiState.sweepWidthMm} mm",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = "Coverage: ${"%.1f".format(uiState.coveragePercent)}%",
            style = MaterialTheme.typography.bodyMedium
        )

        val tagCount = uiState.visibleTagCount
        val totalTags = uiState.uwbTags.count { it.enabled }
        Text(
            text = if (uiState.isRangingActive) {
                if (tagCount < 3) "UWB tags: $tagCount/$totalTags (need 3)" else "UWB tags: $tagCount/$totalTags"
            } else {
                "UWB tags: ${uiState.uwbTags.size} configured (ranging off)"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.isRangingActive && tagCount < 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )

        val statusText = when {
            uiState.isPositionLost -> "Position lost"
            !uiState.statusMessage.isNullOrBlank() -> uiState.statusMessage
            else -> null
        }
        StatusMessageSlot(message = statusText)

        MapCanvas(
            state = uiState,
            modifier = Modifier.weight(1f),
            tapEnabled = isNavigateToMode,
            onTapMap = onMapTapped,
            onTapUwbTag = { tag -> onToggleUwbTag(tag.id) }
        )
    }
}
