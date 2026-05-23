package com.kosilka.feature.history

import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.SessionRecord

data class HistoryUiState(
    val records: List<SessionRecord> = emptyList(),
    val selectedRecord: SessionRecord? = null,
    val selectedCoverageSegments: List<CoverageSegment> = emptyList()
)
