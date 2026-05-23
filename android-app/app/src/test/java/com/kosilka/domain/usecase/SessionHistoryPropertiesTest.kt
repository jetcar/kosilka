package com.kosilka.domain.usecase

import com.kosilka.data.local.dao.SessionHistoryDao
import com.kosilka.data.local.entity.SessionHistoryEntity
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryPropertiesTest {

    @Test
    fun `Property 18 - Session History Sort Order`() = runBlocking {
        repeat(100) { seed ->
            val random = Random(seed)
            val dao = FakeSessionHistoryDao()
            val count = random.nextInt(1, 40)

            val inserted = (0 until count).map { index ->
                SessionHistoryEntity(
                    sessionId = "s-$seed-$index",
                    startTimestampUtcMs = random.nextLong(1_700_000_000_000L, 1_900_000_000_000L),
                    endTimestampUtcMs = random.nextLong(1_900_000_000_001L, 2_000_000_000_000L),
                    durationSeconds = random.nextLong(1L, 10_000L),
                    totalDistanceMm = random.nextLong(0L, 1_000_000L),
                    coveragePercent = random.nextFloat() * 100f
                )
            }

            inserted.shuffled(random).forEach { dao.insertSession(it) }
            val sorted = dao.getAllSessionsSortedByStartDesc().first()

            assertTrue(sorted.zipWithNext().all { (a, b) -> a.startTimestampUtcMs >= b.startTimestampUtcMs })
            assertEquals(
                inserted.map { it.sessionId }.toSet(),
                sorted.map { it.sessionId }.toSet()
            )
        }
    }

    @Test
    fun `Property 19 - Session History 90-Day Retention`() = runBlocking {
        repeat(100) { seed ->
            val random = Random(seed + 10_000)
            val dao = FakeSessionHistoryDao()
            val nowMs = random.nextLong(1_900_000_000_000L, 2_200_000_000_000L)
            val retentionMs = 90L * 24L * 60L * 60L * 1000L
            val cutoff = nowMs - retentionMs

            val samples = (0 until 50).map { index ->
                SessionHistoryEntity(
                    sessionId = "r-$seed-$index",
                    startTimestampUtcMs = random.nextLong(cutoff - retentionMs, cutoff + retentionMs),
                    endTimestampUtcMs = nowMs,
                    durationSeconds = 300L,
                    totalDistanceMm = 10_000L,
                    coveragePercent = 50f
                )
            }

            samples.forEach { dao.insertSession(it) }
            dao.deleteSessionsOlderThan(cutoff)

            val remaining = dao.getAllSessionsSortedByStartDesc().first()
            assertTrue(remaining.all { it.startTimestampUtcMs >= cutoff })

            val expectedRemainingIds = samples
                .filter { it.startTimestampUtcMs >= cutoff }
                .map { it.sessionId }
                .toSet()
            assertEquals(expectedRemainingIds, remaining.map { it.sessionId }.toSet())
        }
    }
}

private class FakeSessionHistoryDao : SessionHistoryDao {
    private val state = MutableStateFlow<List<SessionHistoryEntity>>(emptyList())

    override suspend fun insertSession(session: SessionHistoryEntity) {
        val next = state.value.filterNot { it.sessionId == session.sessionId } + session
        state.value = next
    }

    override fun getAllSessionsSortedByStartDesc(): Flow<List<SessionHistoryEntity>> {
        return MutableStateFlow(state.value.sortedByDescending { it.startTimestampUtcMs }).asStateFlow()
    }

    override fun getMostRecentSession(): Flow<SessionHistoryEntity?> {
        return MutableStateFlow(state.value.maxByOrNull { it.startTimestampUtcMs }).asStateFlow()
    }

    override suspend fun deleteSessionsOlderThan(cutoffMs: Long) {
        state.value = state.value.filter { it.startTimestampUtcMs >= cutoffMs }
    }
}
