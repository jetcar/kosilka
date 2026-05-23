package com.kosilka.feature.zone

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
import com.kosilka.domain.model.Point2dMm

@Composable
fun ZoneScreen(
    modifier: Modifier = Modifier,
    viewModel: ZoneViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Zone Definition", style = MaterialTheme.typography.headlineSmall)

        uiState.statusMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        Text("Draft vertices: ${uiState.draftVertices.size}")
        uiState.currentZone?.let { zone ->
            Text("Current zone vertices: ${zone.vertices.size}")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.addVertex(Point2dMm(0, 0)) }) { Text("Add (0,0)") }
            Button(onClick = { viewModel.addVertex(Point2dMm(5000, 0)) }) { Text("Add (5000,0)") }
            Button(onClick = { viewModel.addVertex(Point2dMm(2500, 4000)) }) { Text("Add (2500,4000)") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = viewModel::removeLastVertex) { Text("Undo") }
            Button(onClick = viewModel::clearDraft) { Text("Clear") }
            Button(onClick = viewModel::confirmZone, enabled = !uiState.isSaving) { Text("Confirm") }
        }
    }
}
