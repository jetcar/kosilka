package com.kosilka.feature.home

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
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDebug: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenZone: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    HomeScreenContent(
        uiState = uiState,
        modifier = modifier,
        onOpenDebug = onOpenDebug,
        onOpenMap = onOpenMap,
        onOpenZone = onOpenZone,
        onOpenSchedule = onOpenSchedule,
        onOpenHistory = onOpenHistory,
        onDisconnect = viewModel::disconnect
    )
}

@Composable
internal fun HomeScreenContent(
    uiState: HomeUiState,
    modifier: Modifier = Modifier,
    onOpenDebug: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    onOpenZone: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onDisconnect: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Status: ${uiState.connectionLabel}",
            style = MaterialTheme.typography.bodyLarge
        )

        uiState.errorMessage?.let { error ->
            Text(
                text = "Error: $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        uiState.mostRecentSessionSummary?.let { summary ->
            Text(
                text = "Most recent session: $summary",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = uiState.canDisconnect,
                onClick = onDisconnect
            ) {
                Text("Disconnect")
            }
        }

        Button(onClick = onOpenDebug) {
            Text("Open Debug")
        }

        Button(onClick = onOpenMap) {
            Text("Open Map")
        }

        Button(onClick = onOpenZone) {
            Text("Open Zone")
        }

        Button(onClick = onOpenSchedule) {
            Text("Open Schedule")
        }

        Button(onClick = onOpenHistory) {
            Text("Open History")
        }
    }
}
