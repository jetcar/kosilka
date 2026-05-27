package com.kosilka.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isNavigateToMode by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = viewModel::startRanging) {
                Text("Start Ranging")
            }
            Button(onClick = viewModel::stopRanging) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = availableZoneCount > 0 && selectedZoneIndex > 0,
                onClick = { viewModel.selectAvailableZone(selectedZoneIndex - 1) }
            ) {
                Text("Zone -")
            }
            Button(
                enabled = availableZoneCount > 0 && selectedZoneIndex < availableZoneCount - 1,
                onClick = { viewModel.selectAvailableZone(selectedZoneIndex + 1) }
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { isNavigateToMode = !isNavigateToMode }) {
                Text(if (isNavigateToMode) "Navigate To: ON" else "Navigate To: OFF")
            }
            Button(onClick = viewModel::stopMower) {
                Text("Stop Mower")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = viewModel::decreaseSweepWidth) {
                Text("Path -")
            }
            Button(onClick = viewModel::increaseSweepWidth) {
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
            onTapMap = viewModel::onMapTapped
        )
    }
}
