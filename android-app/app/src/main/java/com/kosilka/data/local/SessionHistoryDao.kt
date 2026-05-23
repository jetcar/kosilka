package com.kosilka.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionHistoryEntity)

    @Query("SELECT * FROM session_history ORDER BY startTimestampUtcMs DESC")
    fun getAllSessionsSortedByStartDesc(): Flow<List<SessionHistoryEntity>>

    @Query("SELECT * FROM session_history ORDER BY startTimestampUtcMs DESC LIMIT 1")
    fun getMostRecentSession(): Flow<SessionHistoryEntity?>

    @Query("DELETE FROM session_history WHERE startTimestampUtcMs < :cutoffMs")
    suspend fun deleteSessionsOlderThan(cutoffMs: Long)
}
