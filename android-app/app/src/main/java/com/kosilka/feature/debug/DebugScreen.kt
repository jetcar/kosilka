package com.kosilka.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kosilka.BuildConfig
import com.kosilka.data.device.TransportMode

@Composable
fun DebugScreen(
    viewModel: TransportModeDebugViewModel = hiltViewModel()
) {
    val mode by viewModel.currentMode.collectAsState()

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
                Button(onClick = { viewModel.setMode(TransportMode.USB) }) {
                    Text("Use USB")
                }
                Button(onClick = { viewModel.setMode(TransportMode.SERVICE) }) {
                    Text("Use Service")
                }
            }

            Text("Both modes use the same MowerDevice interface.")
            Text("Service URL: ${BuildConfig.MOWER_SERVICE_BASE_URL}")
        }
    }
}
