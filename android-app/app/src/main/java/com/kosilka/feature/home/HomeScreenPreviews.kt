package com.kosilka.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kosilka.core.ui.design.KosilkaTheme

@Preview(name = "Home – disconnected", showBackground = true)
@Composable
private fun HomeDisconnectedPreview() {
    KosilkaTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                title = "Kosilka",
                connectionLabel = "Disconnected",
                canConnect = true,
                canDisconnect = false
            )
        )
    }
}

@Preview(name = "Home – connected", showBackground = true)
@Composable
private fun HomeConnectedPreview() {
    KosilkaTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                title = "Kosilka",
                connectionLabel = "Connected",
                canConnect = false,
                canDisconnect = true,
                mostRecentSessionSummary = "2024-06-01 | 45 min | 82.3%"
            )
        )
    }
}

@Preview(name = "Home – error", showBackground = true)
@Composable
private fun HomeErrorPreview() {
    KosilkaTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                title = "Kosilka",
                connectionLabel = "Disconnected",
                errorMessage = "Connection timed out after 10 s"
            )
        )
    }
}
