package com.kosilka.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kosilka.data.local.entity.ZoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ZoneDao {

    @Upsert
    suspend fun upsertZone(zone: ZoneEntity)

    @Query("SELECT * FROM zones WHERE id = :id LIMIT 1")
    fun getZoneById(id: String): Flow<ZoneEntity?>

    @Query("SELECT * FROM zones WHERE id LIKE :prefix || '%' ORDER BY id ASC")
    fun getZonesByPrefix(prefix: String): Flow<List<ZoneEntity>>

    @Query("SELECT id FROM zones WHERE id LIKE :prefix || '%'")
    suspend fun getZoneIdsByPrefix(prefix: String): List<String>

    @Query("SELECT * FROM zones LIMIT 1")
    fun getZone(): Flow<ZoneEntity?>

    @Query("DELETE FROM zones WHERE id = :id")
    suspend fun deleteZone(id: String)

    @Query("DELETE FROM zones WHERE id LIKE :prefix || '%'")
    suspend fun deleteZonesByPrefix(prefix: String)
}
