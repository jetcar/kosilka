package com.kosilka.data.local.mapper

import com.kosilka.data.local.entity.ZoneEntity
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class VertexJson(val xMm: Int, val yMm: Int)

private val json = Json { ignoreUnknownKeys = true }

fun ZoneEntity.toDomain(): Zone {
    val decodedVertices: List<VertexJson> = json.decodeFromString(
        ListSerializer(VertexJson.serializer()),
        verticesJson
    )
    val vertices = decodedVertices.map { Point2dMm(it.xMm, it.yMm) }
    return Zone(id = id, vertices = vertices)
}

fun Zone.toEntity(): ZoneEntity {
    val encodedVertices: List<VertexJson> = vertices.map { VertexJson(it.xMm, it.yMm) }
    val verticesJson = json.encodeToString(
        ListSerializer(VertexJson.serializer()),
        encodedVertices
    )
    return ZoneEntity(id = id, verticesJson = verticesJson)
}
