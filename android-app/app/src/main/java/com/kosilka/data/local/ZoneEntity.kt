package com.kosilka.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val id: String,
    val verticesJson: String  // JSON array of {xMm, yMm}
)
