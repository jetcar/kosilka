package com.kosilka.data.local.mapper

import com.kosilka.data.local.entity.AnchorEntity
import com.kosilka.domain.model.Anchor

fun AnchorEntity.toDomain(): Anchor = Anchor(
    id = id,
    xMm = xMm,
    yMm = yMm,
    label = label
)

fun Anchor.toEntity(): AnchorEntity = AnchorEntity(
    id = id,
    xMm = xMm,
    yMm = yMm,
    label = label
)
