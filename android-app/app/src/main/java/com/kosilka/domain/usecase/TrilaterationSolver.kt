package com.kosilka.domain.usecase

import com.kosilka.domain.model.MowerPosition
import com.kosilka.domain.model.Point2dMm
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class TrilaterationSolver @Inject constructor() {

    /**
     * Solves 2D position from 3+ anchor distance measurements (in millimeters)
     * using linearized least-squares.
     */
    fun solve(anchorDistancesMm: Map<Point2dMm, Int>): Result<MowerPosition> {
        if (anchorDistancesMm.size < 3) {
            return Result.failure(IllegalArgumentException("At least 3 anchors are required"))
        }

        val entries = anchorDistancesMm.entries.toList()
        val reference = entries.first()

        val x1 = reference.key.xMm.toDouble()
        val y1 = reference.key.yMm.toDouble()
        val d1 = reference.value.toDouble()

        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var b1 = 0.0
        var b2 = 0.0

        for (index in 1 until entries.size) {
            val anchor = entries[index]
            val xi = anchor.key.xMm.toDouble()
            val yi = anchor.key.yMm.toDouble()
            val di = anchor.value.toDouble()

            val ai1 = 2.0 * (xi - x1)
            val ai2 = 2.0 * (yi - y1)
            val bi = (d1 * d1) - (di * di) - (x1 * x1) + (xi * xi) - (y1 * y1) + (yi * yi)

            a11 += ai1 * ai1
            a12 += ai1 * ai2
            a22 += ai2 * ai2
            b1 += ai1 * bi
            b2 += ai2 * bi
        }

        val det = a11 * a22 - a12 * a12
        if (abs(det) < 1e-9) {
            return Result.failure(IllegalStateException("Ill-conditioned anchor geometry"))
        }

        val x = ((b1 * a22) - (a12 * b2)) / det
        val y = ((a11 * b2) - (b1 * a12)) / det

        return Result.success(
            MowerPosition(
                xMm = x.toInt(),
                yMm = y.toInt(),
                timestampMs = System.currentTimeMillis()
            )
        )
    }
}
