package com.kosilka.data.local.mapper

import com.kosilka.data.local.entity.ScheduleEntity
import com.kosilka.domain.model.Schedule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun ScheduleEntity.toDomain(): Schedule {
    val days = json.decodeFromString(ListSerializer(Int.serializer()), daysOfWeekJson)
    return Schedule(
        scheduleId = scheduleId,
        startTimeUtcHhmm = startTimeUtcHhmm,
        daysOfWeek = days,
        zoneId = zoneId,
        pendingSync = pendingSync
    )
}

fun Schedule.toEntity(): ScheduleEntity {
    val daysJson = json.encodeToString(ListSerializer(Int.serializer()), daysOfWeek)
    return ScheduleEntity(
        scheduleId = scheduleId,
        startTimeUtcHhmm = startTimeUtcHhmm,
        daysOfWeekJson = daysJson,
        zoneId = zoneId,
        pendingSync = pendingSync
    )
}
