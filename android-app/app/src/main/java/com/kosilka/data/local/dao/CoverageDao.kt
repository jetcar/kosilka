package com.kosilka.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kosilka.data.local.entity.CoverageSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoverageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<CoverageSegmentEntity>)

    @Query("SELECT * FROM coverage_segments WHERE sessionId = :sessionId")
    fun getSegmentsForSession(sessionId: String): Flow<List<CoverageSegmentEntity>>

    @Query("DELETE FROM coverage_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: String)
}
