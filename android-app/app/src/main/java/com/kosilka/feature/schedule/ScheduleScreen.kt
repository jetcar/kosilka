package com.kosilka.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    viewModel: ScheduleViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Schedules", style = MaterialTheme.typography.headlineSmall)

        uiState.statusMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.startTimeInput,
            onValueChange = viewModel::updateStartTime,
            label = { Text("Start time (HH:MM)") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..6).forEach { day ->
                val selected = day in uiState.selectedDays
                Button(onClick = { viewModel.toggleDay(day) }) {
                    Text(if (selected) "[$day]" else "$day")
                }
            }
        }

        Button(onClick = viewModel::createSchedule, enabled = !uiState.isSaving) {
            Text("Create Schedule")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(uiState.schedules, key = { it.scheduleId }) { schedule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${schedule.startTimeUtcHhmm} | ${schedule.daysOfWeek}")
                    Button(onClick = { viewModel.deleteSchedule(schedule.scheduleId) }) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}
