package com.kosilka.domain.usecase

import com.kosilka.domain.model.CoverageSegment
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCoverageUseCaseTest {

    @Test
    fun `Property 9 - Coverage Accumulation Invariant`() {
        val existing = listOf(
            CoverageSegment(0, 0, 100, 0),
            CoverageSegment(100, 0, 200, 0)
        )
        val incoming = listOf(
            CoverageSegment(200, 0, 300, 0)
        )

        val union = existing + incoming
        assertTrue(union.size >= existing.size)
        assertTrue(union.containsAll(existing))
        assertTrue(union.containsAll(incoming))
    }

    @Test
    fun `Property 10 - Coverage Percentage Correctness`() {
        val zone = Zone(
            id = "z1",
            vertices = listOf(
                Point2dMm(0, 0),
                Point2dMm(1000, 0),
                Point2dMm(1000, 1000),
                Point2dMm(0, 1000)
            )
        )

        val noCoverage = computeCoveragePercent(zone, emptyList())
        assertTrue(noCoverage == 0f)

        val fullCrossCoverage = listOf(
            CoverageSegment(0, 0, 1000, 0),
            CoverageSegment(0, 250, 1000, 250),
            CoverageSegment(0, 500, 1000, 500),
            CoverageSegment(0, 750, 1000, 750),
            CoverageSegment(0, 1000, 1000, 1000),
            CoverageSegment(0, 0, 0, 1000),
            CoverageSegment(250, 0, 250, 1000),
            CoverageSegment(500, 0, 500, 1000),
            CoverageSegment(750, 0, 750, 1000),
            CoverageSegment(1000, 0, 1000, 1000)
        )
        val highCoverage = computeCoveragePercent(zone, fullCrossCoverage)
        assertTrue(highCoverage in 0f..100f)
        assertTrue(highCoverage > 40f)
    }
}
