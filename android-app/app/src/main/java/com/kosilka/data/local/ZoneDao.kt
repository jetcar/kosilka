package com.kosilka.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ZoneDao {

    @Upsert
    suspend fun upsertZone(zone: ZoneEntity)

    @Query("SELECT * FROM zones LIMIT 1")
    fun getZone(): Flow<ZoneEntity?>

    @Query("DELETE FROM zones WHERE id = :id")
    suspend fun deleteZone(id: String)
}
