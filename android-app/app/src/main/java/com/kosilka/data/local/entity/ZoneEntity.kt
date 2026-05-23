package com.kosilka.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val id: String,
    // JSON-serialised array of {xMm, yMm} vertex objects
    val verticesJson: String
)
