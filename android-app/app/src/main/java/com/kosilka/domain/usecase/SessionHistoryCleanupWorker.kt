package com.kosilka.domain.usecase

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kosilka.data.local.dao.SessionHistoryDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SessionHistoryCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionHistoryDao: SessionHistoryDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val retentionMs = 90L * 24L * 60L * 60L * 1000L
        val cutoff = System.currentTimeMillis() - retentionMs
        sessionHistoryDao.deleteSessionsOlderThan(cutoff)
        return Result.success()
    }
}
