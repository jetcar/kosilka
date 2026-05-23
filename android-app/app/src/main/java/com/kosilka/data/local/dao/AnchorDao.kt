package com.kosilka.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kosilka.data.local.entity.AnchorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnchorDao {

    @Upsert
    suspend fun upsertAnchor(anchor: AnchorEntity)

    @Query("SELECT * FROM anchors")
    fun getAllAnchors(): Flow<List<AnchorEntity>>

    @Query("DELETE FROM anchors WHERE id = :id")
    suspend fun deleteAnchor(id: String)
}
