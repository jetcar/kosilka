package com.kosilka.domain.model

data class UwbTag(
    val id: String,
    val xMm: Int,
    val yMm: Int,
    val label: String,
    val enabled: Boolean,
    val maxRangeMm: Int
)
