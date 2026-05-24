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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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

        if (uiState.isPositionLost) {
            Text(
                text = "Position lost",
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(
            text = "Coverage: ${"%.1f".format(uiState.coveragePercent)}%",
            style = MaterialTheme.typography.bodyMedium
        )

        uiState.statusMessage?.let { status ->
            Text(
                text = status,
                color = MaterialTheme.colorScheme.error
            )
        }

        MapCanvas(
            state = uiState,
            modifier = Modifier.weight(1f),
            tapEnabled = false,
            onTapMap = {}
        )
    }
}
