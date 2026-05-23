package com.kosilka.domain.usecase

import com.kosilka.domain.model.SessionRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class LoadHistoryUseCase @Inject constructor(
    private val sessionHistoryRepository: SessionHistoryRepository
) {
    fun loadHistory(): Flow<List<SessionRecord>> = sessionHistoryRepository.observeHistory()
}
