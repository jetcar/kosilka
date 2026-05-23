package com.kosilka.domain.usecase

import com.kosilka.data.local.dao.AnchorDao
import com.kosilka.data.local.entity.AnchorEntity
import com.kosilka.data.local.mapper.toDomain
import com.kosilka.domain.model.Anchor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class LoadAnchorsUseCase @Inject constructor(
    private val anchorDao: AnchorDao
) {
    fun observeAnchors(): Flow<List<Anchor>> =
        anchorDao.getAllAnchors().map { rows -> rows.map { it.toDomain() } }

    suspend fun ensureDefaultAnchors() {
        val existing = anchorDao.getAllAnchors().first()
        if (existing.isNotEmpty()) {
            return
        }

        val defaults = listOf(
            AnchorEntity(id = "a1", xMm = 0, yMm = 0, label = "A1"),
            AnchorEntity(id = "a2", xMm = 5_000, yMm = 0, label = "A2"),
            AnchorEntity(id = "a3", xMm = 2_500, yMm = 4_000, label = "A3")
        )

        defaults.forEach { anchor ->
            anchorDao.upsertAnchor(anchor)
        }
    }
}
