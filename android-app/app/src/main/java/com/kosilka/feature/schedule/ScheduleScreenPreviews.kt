package com.kosilka.feature.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kosilka.core.ui.design.KosilkaTheme
import com.kosilka.domain.model.Schedule

@Preview(name = "Schedule – empty", showBackground = true)
@Composable
private fun ScheduleEmptyPreview() {
    KosilkaTheme {
        ScheduleScreenContent(uiState = ScheduleUiState())
    }
}

@Preview(name = "Schedule – with entries", showBackground = true)
@Composable
private fun ScheduleWithEntriesPreview() {
    KosilkaTheme {
        ScheduleScreenContent(
            uiState = ScheduleUiState(
                schedules = listOf(
                    Schedule("s1", "07:30", listOf(1, 3, 5), null),
                    Schedule("s2", "09:00", listOf(0, 6), null),
                    Schedule("s3", "14:00", listOf(2, 4), "zone-abc")
                ),
                startTimeInput = "08:00",
                selectedDays = setOf(1, 3, 5)
            )
        )
    }
}

@Preview(name = "Schedule – saving", showBackground = true)
@Composable
private fun ScheduleSavingPreview() {
    KosilkaTheme {
        ScheduleScreenContent(
            uiState = ScheduleUiState(
                startTimeInput = "10:00",
                selectedDays = setOf(0, 2, 4, 6),
                isSaving = true
            )
        )
    }
}

@Preview(name = "Schedule – error", showBackground = true)
@Composable
private fun ScheduleErrorPreview() {
    KosilkaTheme {
        ScheduleScreenContent(
            uiState = ScheduleUiState(
                statusMessage = "Invalid time format — use HH:MM"
            )
        )
    }
}
