package com.kosilka.domain.usecase

import com.kosilka.data.local.dao.AnchorDao
import com.kosilka.data.local.entity.AnchorEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadAnchorsUseCaseTest {

    @Test
    fun `Property 21 - Offline Data Availability`() = runBlocking {
        val dao = FakeAnchorDao()
        val useCase = LoadAnchorsUseCase(dao)

        useCase.ensureDefaultAnchors()
        val defaults = useCase.observeAnchors().first()

        assertEquals(3, defaults.size)
        assertTrue(defaults.any { it.id == "a1" })
        assertTrue(defaults.any { it.id == "a2" })
        assertTrue(defaults.any { it.id == "a3" })

        dao.upsertAnchor(AnchorEntity(id = "persisted", xMm = 111, yMm = 222, label = "P"))
        useCase.ensureDefaultAnchors()
        val persisted = useCase.observeAnchors().first()

        assertTrue(persisted.any { it.id == "persisted" })
    }
}

private class FakeAnchorDao : AnchorDao {
    private val state = MutableStateFlow<List<AnchorEntity>>(emptyList())

    override suspend fun upsertAnchor(anchor: AnchorEntity) {
        state.value = state.value.filterNot { it.id == anchor.id } + anchor
    }

    override fun getAllAnchors(): Flow<List<AnchorEntity>> = state.asStateFlow()

    override suspend fun deleteAnchor(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}
