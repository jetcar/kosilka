package com.kosilka.domain.usecase

import com.kosilka.data.local.dao.CoverageDao
import com.kosilka.data.local.mapper.toDomain
import com.kosilka.domain.model.CoverageSegment
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class LoadSessionCoverageUseCase @Inject constructor(
    private val coverageDao: CoverageDao
) {
    fun load(sessionId: String): Flow<List<CoverageSegment>> =
        coverageDao.getSegmentsForSession(sessionId).map { rows -> rows.map { it.toDomain() } }
}
