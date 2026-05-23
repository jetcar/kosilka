package com.kosilka.feature.home

data class HomeUiState(
    val title: String = "Kosilka",
    val connectionLabel: String = "Disconnected",
    val isBusy: Boolean = false,
    val canConnect: Boolean = true,
    val canDisconnect: Boolean = false,
    val errorMessage: String? = null,
    val mostRecentSessionSummary: String? = null
)
