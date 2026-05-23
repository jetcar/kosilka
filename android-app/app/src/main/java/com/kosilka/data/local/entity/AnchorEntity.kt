package com.kosilka.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "anchors")
data class AnchorEntity(
    @PrimaryKey val id: String,
    val xMm: Int,
    val yMm: Int,
    val label: String
)
