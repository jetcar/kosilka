package com.kosilka.feature.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kosilka.core.ui.design.KosilkaTheme
import com.kosilka.domain.model.SessionRecord

private val sampleRecords = listOf(
    SessionRecord("s1", 1717200000000L, 1717202700000L, 2700L, 48000L, 82.3f),
    SessionRecord("s2", 1717113600000L, 1717116000000L, 2400L, 42000L, 71.0f),
    SessionRecord("s3", 1717027200000L, 1717028800000L, 1600L, 28000L, 45.5f)
)

@Preview(name = "History – empty", showBackground = true)
@Composable
private fun HistoryEmptyPreview() {
    KosilkaTheme {
        HistoryScreenContent(uiState = HistoryUiState())
    }
}

@Preview(name = "History – list", showBackground = true)
@Composable
private fun HistoryListPreview() {
    KosilkaTheme {
        HistoryScreenContent(
            uiState = HistoryUiState(records = sampleRecords)
        )
    }
}

@Preview(name = "History – session selected", showBackground = true)
@Composable
private fun HistorySelectedPreview() {
    KosilkaTheme {
        HistoryScreenContent(
            uiState = HistoryUiState(
                records = sampleRecords,
                selectedRecord = sampleRecords.first(),
                selectedCoverageSegments = emptyList()
            )
        )
    }
}
