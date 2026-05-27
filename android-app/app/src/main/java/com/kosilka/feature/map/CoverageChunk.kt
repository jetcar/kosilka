package com.kosilka.feature.map

import com.kosilka.domain.model.CoverageSegment

/**
 * A rectangular grid cell of the coverage area that can be swept independently.
 *
 * Cell boundaries align with no-go zone bounding box edges, which guarantees that
 * within-cell sweep transitions (diagonal moves between adjacent scan lines) never
 * cross a no-go zone.  Only inter-chunk moves may need detour routing.
 */
data class CoverageChunk(
    val id: Int,
    val sweepSegments: List<CoverageSegment>,
    val cellMinXMm: Int,
    val cellMaxXMm: Int,
    val cellMinYMm: Int,
    val cellMaxYMm: Int
)
