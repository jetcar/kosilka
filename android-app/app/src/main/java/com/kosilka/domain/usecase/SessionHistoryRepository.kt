package com.kosilka.domain.usecase

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kosilka.data.local.dao.SessionHistoryDao
import com.kosilka.data.local.mapper.toDomain
import com.kosilka.data.local.mapper.toEntity
import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.SessionRecord
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.hypot

@Singleton
class SessionHistoryRepository @Inject constructor(
    private val sessionHistoryDao: SessionHistoryDao,
    private val connectMowerUseCase: ConnectMowerUseCase,
    private val trackCoverageUseCase: TrackCoverageUseCase,
    private val workManager: WorkManager
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var trackerJob: Job? = null

    private var activeSessionId: String? = null
    private var activeSessionStartMs: Long = 0L

    fun observeHistory(): Flow<List<SessionRecord>> =
        sessionHistoryDao.getAllSessionsSortedByStartDesc().map { rows -> rows.map { it.toDomain() } }

    fun observeMostRecentSession(): Flow<SessionRecord?> =
        sessionHistoryDao.getMostRecentSession().map { it?.toDomain() }

    fun startTracking() {
        if (trackerJob?.isActive == true) {
            return
        }

        enqueueRetentionCleanup()

        trackerJob = scope.launch {
            connectMowerUseCase.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        activeSessionId = state.sessionId
                        activeSessionStartMs = System.currentTimeMillis()
                    }

                    ConnectionState.Disconnected,
                    is ConnectionState.Failed -> {
                        persistIfNeeded()
                    }

                    ConnectionState.Connecting -> Unit
                }
            }
        }
    }

    private suspend fun persistIfNeeded() {
        val sessionId = activeSessionId ?: return
        val startMs = activeSessionStartMs
        if (startMs <= 0L) {
            activeSessionId = null
            return
        }

        val endMs = System.currentTimeMillis()
        val coverageState = trackCoverageUseCase.state.value
        val segments = coverageState.segments

        val record = SessionRecord(
            sessionId = sessionId,
            startTimestampUtcMs = startMs,
            endTimestampUtcMs = endMs,
            durationSeconds = ((endMs - startMs) / 1000L).coerceAtLeast(0L),
            totalDistanceMm = totalDistance(segments),
            coveragePercent = coverageState.coveragePercent
        )

        sessionHistoryDao.insertSession(record.toEntity())
        activeSessionId = null
        activeSessionStartMs = 0L
    }

    private fun totalDistance(segments: List<CoverageSegment>): Long {
        return segments.sumOf { segment ->
            hypot(
                (segment.toXMm - segment.fromXMm).toDouble(),
                (segment.toYMm - segment.fromYMm).toDouble()
            ).toLong()
        }
    }

    private fun enqueueRetentionCleanup() {
        val workRequest = PeriodicWorkRequestBuilder<SessionHistoryCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            HISTORY_CLEANUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    companion object {
        private const val HISTORY_CLEANUP_WORK_NAME = "session-history-cleanup"
    }
}
