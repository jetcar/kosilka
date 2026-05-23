package com.kosilka.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kosilka.feature.map.MapCanvas
import com.kosilka.feature.map.MapUiState

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Session History", style = MaterialTheme.typography.headlineSmall)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(uiState.records, key = { it.sessionId }) { record ->
                Text(
                    text = "${record.startTimestampUtcMs} | ${record.durationSeconds}s | ${"%.1f".format(record.coveragePercent)}%",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectRecord(record) }
                )
            }
        }

        uiState.selectedRecord?.let { selected ->
            Text("Selected session: ${selected.sessionId}")
            Text("Coverage: ${"%.1f".format(selected.coveragePercent)}%")
            Text("Distance: ${selected.totalDistanceMm} mm")

            MapCanvas(
                state = MapUiState(
                    coverageSegments = uiState.selectedCoverageSegments,
                    coveragePercent = selected.coveragePercent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                tapEnabled = false
            )
        }
    }
}
