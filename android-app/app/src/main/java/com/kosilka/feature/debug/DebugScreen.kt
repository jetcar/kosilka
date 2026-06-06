package com.kosilka.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.kosilka.data.device.TransportMode

@Composable
fun DebugScreen(
    viewModel: TransportModeDebugViewModel = hiltViewModel()
) {
    val mode by viewModel.currentMode.collectAsState()
    val serviceEndpoint by viewModel.serviceEndpoint.collectAsState()
    DebugScreenContent(
        mode = mode,
        serviceEndpoint = serviceEndpoint,
        onSetMode = viewModel::setMode,
        onApplyEndpoint = viewModel::setServiceEndpoint,
        onResetEndpoint = viewModel::resetServiceEndpoint
    )
}

@Composable
internal fun DebugScreenContent(
    mode: TransportMode,
    serviceEndpoint: String,
    onSetMode: (TransportMode) -> Unit = {},
    onApplyEndpoint: (String) -> Unit = {},
    onResetEndpoint: () -> Unit = {}
) {
    var serviceEndpointInput by remember(serviceEndpoint) { mutableStateOf(serviceEndpoint) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Debug Transport", style = MaterialTheme.typography.titleLarge)
            Text("Current mode: ${mode.name}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSetMode(TransportMode.USB) }) {
                    Text("Use USB")
                }
                Button(onClick = { onSetMode(TransportMode.SERVICE) }) {
                    Text("Use Service")
                }
            }

            Text("Both modes use the same MowerDevice interface.")
            OutlinedTextField(
                value = serviceEndpointInput,
                onValueChange = { serviceEndpointInput = it },
                label = { Text("Service Endpoint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApplyEndpoint(serviceEndpointInput) }) {
                    Text("Apply Endpoint")
                }
                Button(onClick = onResetEndpoint) {
                    Text("Reset Default")
                }
            }

            Text("Current service URL: $serviceEndpoint")
        }
    }
}
