package com.kosilka.core.emulator

import com.kosilka.domain.model.Point2dMm

object DefaultEmulatorPath {
    val waypoints: List<Point2dMm> = listOf(
        Point2dMm(0, 0),
        Point2dMm(5_000, 0),
        Point2dMm(5_000, 4_000),
        Point2dMm(0, 4_000),
        Point2dMm(0, 0)
    )
}
