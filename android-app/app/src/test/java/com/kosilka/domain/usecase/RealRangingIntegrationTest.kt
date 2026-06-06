package com.kosilka.domain.usecase

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import com.kosilka.data.device.protocol.CoverageSegmentPayload
import com.kosilka.data.device.protocol.Envelope
import com.kosilka.data.device.protocol.IncomingMessage
import com.kosilka.data.device.protocol.ProtocolConstants
import com.kosilka.domain.model.Point2dMm
import com.kosilka.domain.model.Zone
import com.kosilka.testing.EmulatorContainerSupport
import com.kosilka.testing.RestEmulatorMowerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * End-to-end ranging + coverage integration test against the live emulator.
 *
 *  ── Field layout ──
 *  Available zone: 10 × 10 m square (0,0) → (10000,10000)
 *  No-go zone (top-middle): (3000,7500) → (7000,9000) — 4 × 1.5 m = 6 m²
 *  Coverable area: 100 − 6 = 94 m²
 *
 *  ── Mower model ──
 *  Sweep width: 500 mm (0.5 m)
 *  Speed:       1300 mm/s
 *  Position report cadence: every ≤50 mm of travel
 *
 *  ── Expected path budget ──
 *  Total spiral path length ≈ 94 m² ÷ 0.5 m = 188 m  (+ inter-region transitions ≈ 200–250 m)
 *  Expected runtime ≈ 200 m ÷ 1.3 m/s ≈ 150–200 s
 *  Expected COVERAGE_UPDATE segments ≈ 200 m ÷ 0.05 m = 4000+
 *
 *  ── Verified properties ──
 *  • No segment ever crosses the no-go zone interior
 *  • Path direction is counter-clockwise (per-vertex turn sum is CCW)
 *  • Final covered area ≥ 90 % of the available zone (using 500 mm sweep model)
 *  • Reports come in 5 cm increments
 *  • Duration matches the 1300 mm/s physics
 */
class RealRangingIntegrationTest {

    private val availableZone = Zone(
        id = "zone-available-10x10",
        vertices = listOf(
            Point2dMm(0, 0),
            Point2dMm(10000, 0),
            Point2dMm(10000, 10000),
            Point2dMm(0, 10000)
        )
    )

    private val topNoGoZone = Zone(
        id = "zone-no-go-top",
        vertices = listOf(
            Point2dMm(3000, 7500),
            Point2dMm(7000, 7500),
            Point2dMm(7000, 9000),
            Point2dMm(3000, 9000)
        )
    )

    private val cornerTags = listOf(
        Triple("UWB-BL", Point2dMm(-500, -500),   20000),
        Triple("UWB-BR", Point2dMm(10500, -500),  20000),
        Triple("UWB-TR", Point2dMm(10500, 10500), 20000),
        Triple("UWB-TL", Point2dMm(-500, 10500),  20000)
    )

    // 10 tiny no-go zones: 2 rows × 5 columns, each 50 × 50 mm
    // Row y-centres: 2000, 6000 — Column x-centres: 1000, 3000, 5000, 7000, 9000
    private val tinyNoGoZones: List<Zone> = run {
        val rows = listOf(2000, 6000)
        val cols = listOf(1000, 3000, 5000, 7000, 9000)
        rows.flatMapIndexed { ri, cy ->
            cols.mapIndexed { ci, cx ->
                Zone(
                    id = "zone-tiny-no-go-${ri * 5 + ci + 1}",
                    vertices = listOf(
                        Point2dMm(cx - 25, cy - 25),
                        Point2dMm(cx + 25, cy - 25),
                        Point2dMm(cx + 25, cy + 25),
                        Point2dMm(cx - 25, cy + 25)
                    )
                )
            }
        }
    }

    private fun prepareFieldWithoutTags(mowerPosition: Point2dMm): String {
        return EmulatorContainerSupport.prepareTestMap(
            mowerPosition = mowerPosition,
            availableZones = listOf(availableZone),
            noGoZones = listOf(topNoGoZone),
            speedMmPerSec = 1300,
            rotationSpeedDegPerSec = 720
        )
    }

    private fun prepareFieldWithTags(mowerPosition: Point2dMm): String {
        val baseUrl = prepareFieldWithoutTags(mowerPosition)
        EmulatorContainerSupport.addUwbTags(baseUrl = baseUrl, tags = cornerTags)
        return baseUrl
    }

    private fun prepareFieldWith10TinyNoGoZonesAndTags(mowerPosition: Point2dMm): String {
        val baseUrl = EmulatorContainerSupport.prepareTestMap(
            mowerPosition = mowerPosition,
            availableZones = listOf(availableZone),
            noGoZones = tinyNoGoZones,
            speedMmPerSec = 1300,
            rotationSpeedDegPerSec = 720
        )
        EmulatorContainerSupport.addUwbTags(baseUrl = baseUrl, tags = cornerTags)
        return baseUrl
    }

    private fun collectCoverageSegments(
        device: RestEmulatorMowerDevice
    ): Pair<CoverageCollector, Job> {
        val collector = CoverageCollector()
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launch {
            device.incomingMessages.collect { msg ->
                if (msg is IncomingMessage.CoverageUpdate) {
                    collector.addAll(msg.segments)
                }
            }
        }
        return collector to job
    }

    // ─── Test 1: strict no-tag gate ──────────────────────────────────────────

    @Test
    fun `without any uwb tags the mower must not move at all`() = runBlocking {
        val startPos = Point2dMm(2000, 2000)
        val baseUrl = prepareFieldWithoutTags(mowerPosition = startPos)

        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(200L)

        val (collector, job) = collectCoverageSegments(device)

        val moveUseCase = MoveMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers()
        )
        moveUseCase.moveTo(sessionId = device.sessionId(), target = Point2dMm(8000, 8000), zone = null)

        // If movement was happening, the mower would have travelled several metres.
        delay(2_000L)

        val pos = device.readCurrentPosition().getOrThrow()
        assertEquals("Mower xMm must equal starting xMm", startPos.xMm, pos.xMm)
        assertEquals("Mower yMm must equal starting yMm", startPos.yMm, pos.yMm)
        assertTrue(
            "No COVERAGE_UPDATE segments should be emitted without tags (got ${collector.size()})",
            collector.size() == 0
        )

        job.cancel()
        device.disconnect()
    }

    // ─── Test 2: 5 cm reporting cadence ──────────────────────────────────────

    @Test
    fun `movement is reported every 5 cm or less`() = runBlocking {
        val startPos = Point2dMm(1000, 1000)
        val target   = Point2dMm(5000, 1000)
        val baseUrl = prepareFieldWithTags(mowerPosition = startPos)
        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(300L)

        val (collector, job) = collectCoverageSegments(device)

        val moveUseCase = MoveMowerUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers()
        )
        val result = moveUseCase.moveTo(sessionId = device.sessionId(), target = target, zone = null)
        assertTrue(result is MoveMowerResult.Success)

        val arrived = withTimeoutOrNull(10_000L) {
            while (true) {
                val pos = device.readCurrentPosition().getOrThrow()
                if (hypot((target.xMm - pos.xMm).toDouble(), (target.yMm - pos.yMm).toDouble()) < 200.0) {
                    return@withTimeoutOrNull true
                }
                delay(50L)
            }
            false
        }
        assertTrue("Mower should reach target", arrived == true)
        delay(200L)
        job.cancel()

        val segments = collector.snapshot()
        assertTrue("Expected ≥50 coverage segments, got ${segments.size}", segments.size >= 50)

        val MAX_SEGMENT_MM = 55.0
        val tooLong = segments
            .map { hypot((it.toXMm - it.fromXMm).toDouble(), (it.toYMm - it.fromYMm).toDouble()) }
            .filter { it > MAX_SEGMENT_MM }
        assertTrue(
            "All segments must be ≤${MAX_SEGMENT_MM} mm — ${tooLong.size}/${segments.size} were too long",
            tooLong.isEmpty()
        )

        device.disconnect()
    }

    // ─── Test 3: trilateration on real emulator ──────────────────────────────

    @Test
    fun `trilateration resolves position in 10x10m field with 4 corner tags`() = runBlocking {
        val mowerPos = Point2dMm(3000, 2500)
        val baseUrl = prepareFieldWithTags(mowerPosition = mowerPos)
        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(300L)

        val rangingUseCase = StartRangingUseCase(
            mowerDevice = device,
            messageIdGenerator = MessageIdGenerator(),
            dispatchers = CoroutineDispatchers(),
            trilaterationSolver = TrilaterationSolver()
        )

        val startResult = rangingUseCase.start(
            sessionId = device.sessionId(),
            sampleRateHz = 8,
            anchorsById = mapOf(
                "p1" to Point2dMm(0, 0),
                "p2" to Point2dMm(5000, 0),
                "p3" to Point2dMm(0, 5000)
            )
        )
        assertTrue(startResult.isSuccess)

        val resolved = withTimeoutOrNull(3_000L) {
            while (true) {
                val pos = rangingUseCase.state.value.latestPosition
                if (pos != null &&
                    pos.xMm in (mowerPos.xMm - 400)..(mowerPos.xMm + 400) &&
                    pos.yMm in (mowerPos.yMm - 400)..(mowerPos.yMm + 400)) {
                    return@withTimeoutOrNull pos
                }
                delay(100L)
            }
            null
        }
        assertNotNull("Trilateration should resolve within ±400 mm of (3000,2500)", resolved)
        assertTrue(rangingUseCase.state.value.visibleTagCount >= 3)

        rangingUseCase.stop()
        device.disconnect()
    }

    // ─── Test 4: full CCW spiral coverage of 10×10 m field ───────────────────

    @Test
    fun `ccw spiral covers 10x10m field avoiding no-go and reaches at least 90 percent`() = runBlocking {
        val sweepMm = 500          // 50 cm mower
        val startPos = Point2dMm(250, 250)
        val baseUrl = prepareFieldWithTags(mowerPosition = startPos)
        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(500L)

        val sessionId = device.sessionId()
        val (collector, collectorJob) = collectCoverageSegments(device)

        val waypoints = buildCcwSpiralAvoidingNoGo(sweepMm)
        // Sanity check: spiral must not have any leg entering the no-go.
        for (i in 0 until waypoints.size - 1) {
            assertFalse(
                "Planned spiral leg ${waypoints[i]} → ${waypoints[i + 1]} crosses no-go",
                segmentIntersectsRect(
                    waypoints[i], waypoints[i + 1],
                    topNoGoZone.vertices
                )
            )
        }

        // Send waypoints sequentially, wait for arrival between each.
        var msgId = 100_000L
        val startMs = System.currentTimeMillis()
        for ((idx, wp) in waypoints.withIndex()) {
            device.send(
                Envelope(
                    protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                    messageType = ProtocolConstants.TYPE_MOVE_TO,
                    messageId = msgId++,
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    payload = mapOf("targetXMm" to wp.xMm, "targetYMm" to wp.yMm)
                )
            )
            val arrived = withTimeoutOrNull(20_000L) {
                while (true) {
                    val pos = device.readCurrentPosition().getOrThrow()
                    val d = hypot((wp.xMm - pos.xMm).toDouble(), (wp.yMm - pos.yMm).toDouble())
                    if (d < 100.0) return@withTimeoutOrNull true
                    delay(80L)
                }
                false
            }
            assertTrue("Failed to reach waypoint #$idx $wp", arrived == true)
        }
        val durationMs = System.currentTimeMillis() - startMs
        delay(500L)
        collectorJob.cancel()

        val segments = collector.snapshot()

        // ── Assertion 1: enough segments (~5 cm cadence) ──
        // Expected ~ 4000+ for ~200 m path. Be generous with a lower bound.
        assertTrue(
            "Expected ≥1500 coverage segments (5 cm reporting on ~200 m path), got ${segments.size}",
            segments.size >= 1500
        )

        // ── Assertion 2: no segment crosses the no-go zone ──
        val noGoVerts = topNoGoZone.vertices
        val crossing = segments.filter { seg ->
            segmentIntersectsRect(
                Point2dMm(seg.fromXMm, seg.fromYMm),
                Point2dMm(seg.toXMm,   seg.toYMm),
                noGoVerts
            )
        }
        assertTrue(
            "No segment may cross no-go (${crossing.size} did)",
            crossing.isEmpty()
        )

        // ── Assertion 3: path is counter-clockwise ──
        // Sum signed cross products at each interior point.
        // CCW polygons in standard math coords (y up) have positive signed area.
        val ccwScore = signedTurnSum(segments)
        assertTrue(
            "Path must be predominantly CCW (signed turn sum = $ccwScore should be > 0)",
            ccwScore > 0
        )

        // ── Assertion 4: coverage ≥ 90 % with 500 mm sweep model ──
        val coveragePercent = computeCoverageWithSweep(
            zone = availableZone,
            segments = segments,
            sweepWidthMm = sweepMm,
            gridStepMm = 100
        )
        assertTrue(
            "Coverage %.1f%% must be ≥ 90%% — path covered too little".format(coveragePercent),
            coveragePercent >= 90.0
        )

        // ── Assertion 5: duration consistent with 1300 mm/s on 10×10 m field ──
        // 200 m ÷ 1.3 m/s ≈ 154 s. Lower bound enforces that we didn't cheat;
        // upper bound catches catastrophic slowdowns.
        val durationSec = durationMs / 1000
        assertTrue(
            "Duration ${durationSec}s — must be 60–300 s for a 10×10 m spiral at 1300 mm/s",
            durationSec in 60..300
        )

        device.disconnect()
    }

    // ─── Test 5: 10×10 m field with 10 tiny equally-distributed no-go zones ────
    //
    //  No-go zones: 10 squares of 50 mm × 50 mm in a 2 × 5 grid
    //    x-centres: 1000, 3000, 5000, 7000, 9000
    //    y-centres: 2000, 6000
    //  Spiral sweep lanes (y = 250, 750, 1250, …) never hit y ∈ [1975, 2025] or [5975, 6025],
    //  so no waypoint crosses any zone — no special routing required.
    @Test
    fun `ccw spiral covers 10x10m field with 10 tiny no-go zones equally distributed`() = runBlocking {
        val sweepMm = 500
        val startPos = Point2dMm(250, 250)
        val baseUrl = prepareFieldWith10TinyNoGoZonesAndTags(mowerPosition = startPos)

        val device = RestEmulatorMowerDevice(baseUrl = baseUrl)
        device.connect()
        delay(500L)

        val sessionId = device.sessionId()
        val (collector, collectorJob) = collectCoverageSegments(device)

        val waypoints = ccwSpiral(0, 10000, 0, 10000, sweepMm)

        // Pre-check: no planned spiral leg crosses any tiny no-go zone.
        for (i in 0 until waypoints.size - 1) {
            for (zone in tinyNoGoZones) {
                assertFalse(
                    "Planned spiral leg ${waypoints[i]} → ${waypoints[i + 1]} crosses ${zone.id}",
                    segmentIntersectsRect(waypoints[i], waypoints[i + 1], zone.vertices)
                )
            }
        }

        var msgId = 200_000L
        val startMs = System.currentTimeMillis()
        for ((idx, wp) in waypoints.withIndex()) {
            device.send(
                Envelope(
                    protocolVersion = ProtocolConstants.SUPPORTED_VERSION,
                    messageType = ProtocolConstants.TYPE_MOVE_TO,
                    messageId = msgId++,
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    payload = mapOf("targetXMm" to wp.xMm, "targetYMm" to wp.yMm)
                )
            )
            val arrived = withTimeoutOrNull(20_000L) {
                while (true) {
                    val pos = device.readCurrentPosition().getOrThrow()
                    val d = hypot((wp.xMm - pos.xMm).toDouble(), (wp.yMm - pos.yMm).toDouble())
                    if (d < 100.0) return@withTimeoutOrNull true
                    delay(80L)
                }
                false
            }
            assertTrue("Failed to reach waypoint #$idx $wp", arrived == true)
        }
        val durationMs = System.currentTimeMillis() - startMs
        delay(500L)
        collectorJob.cancel()

        val segments = collector.snapshot()

        // ── Assertion 1: 5 cm reporting cadence on ~200 m path ──
        assertTrue(
            "Expected ≥1500 coverage segments (5 cm reporting on ~200 m spiral), got ${segments.size}",
            segments.size >= 1500
        )

        // ── Assertion 2: no segment crosses any of the 10 tiny no-go zones ──
        for (zone in tinyNoGoZones) {
            val crossing = segments.filter { seg ->
                segmentIntersectsRect(
                    Point2dMm(seg.fromXMm, seg.fromYMm),
                    Point2dMm(seg.toXMm, seg.toYMm),
                    zone.vertices
                )
            }
            assertTrue(
                "No segment may cross ${zone.id} (${crossing.size} did)",
                crossing.isEmpty()
            )
        }

        // ── Assertion 3: coverage ≥ 90 % ──
        val coveragePercent = computeCoverageWithSweep(
            zone = availableZone,
            segments = segments,
            sweepWidthMm = sweepMm,
            gridStepMm = 100
        )
        assertTrue(
            "Coverage %.1f%% must be ≥ 90%%".format(coveragePercent),
            coveragePercent >= 90.0
        )

        // ── Assertion 4: duration sanity at 1300 mm/s ──
        val durationSec = durationMs / 1000
        assertTrue(
            "Duration ${durationSec}s — must be 60–300 s for a 10×10 m spiral at 1300 mm/s",
            durationSec in 60..300
        )

        device.disconnect()
    }
}

// ───────────────────── helpers (file-private) ────────────────────────────────

private class CoverageCollector {
    private val segments = CopyOnWriteArrayList<CoverageSegmentPayload>()
    fun addAll(items: List<CoverageSegmentPayload>) { segments.addAll(items) }
    fun size(): Int = segments.size
    fun snapshot(): List<CoverageSegmentPayload> = segments.toList()
}

/**
 * Builds a counter-clockwise spiral that covers the 10×10 m available zone
 * while staying outside the (3000,7500)–(7000,9000) no-go rectangle.
 *
 * Decomposition (each sub-rectangle is spiralled CCW; transitions go around
 * the no-go via the left edge):
 *
 *   ─────────────────────────────────────────
 *   │  TL strip │ above-no-go │  TR strip   │   (y = 7500–10000)
 *   │           ├─────────────┤             │
 *   │           │   NO-GO     │             │
 *   ├───────────┴─────────────┴─────────────┤
 *   │              LOWER  BAND              │   (y = 0–7500)
 *   │                                       │
 *   ─────────────────────────────────────────
 */
private fun buildCcwSpiralAvoidingNoGo(sweepMm: Int): List<Point2dMm> {
    val pts = mutableListOf<Point2dMm>()
    // 1) Lower band: full width, below no-go
    pts += ccwSpiral(0, 10000, 0, 7500, sweepMm)
    // Transition along left edge up to top-left strip
    pts += Point2dMm(sweepMm / 2, 7500 + sweepMm / 2)
    // 2) Top-left strip (left of no-go)
    pts += ccwSpiral(0, 3000, 7500, 10000, sweepMm)
    // Transition along top edge to above-no-go strip
    pts += Point2dMm(3000 + sweepMm / 2, 10000 - sweepMm / 2)
    // 3) Above-no-go strip (just above no-go)
    pts += ccwSpiral(3000, 7000, 9000, 10000, sweepMm)
    // Transition along top edge to top-right strip
    pts += Point2dMm(7000 + sweepMm / 2, 10000 - sweepMm / 2)
    // 4) Top-right strip
    pts += ccwSpiral(7000, 10000, 7500, 10000, sweepMm)
    return pts
}

/**
 * CCW spiral inward, starting at the bottom-left inset corner.
 * Order: east (bottom) → north (right) → west (top) → south (left) → repeat shrunk.
 */
private fun ccwSpiral(left: Int, right: Int, bottom: Int, top: Int, sweepMm: Int): List<Point2dMm> {
    val pts = mutableListOf<Point2dMm>()
    val inset = sweepMm / 2
    var l = left + inset
    var r = right - inset
    var b = bottom + inset
    var t = top - inset
    if (l >= r || b >= t) return pts

    pts += Point2dMm(l, b) // starting corner
    while (l < r && b < t) {
        pts += Point2dMm(r, b)               // → east
        pts += Point2dMm(r, t)               // ↑ north
        pts += Point2dMm(l, t)               // ← west
        val nextB = b + sweepMm
        if (nextB >= t - 1) break
        pts += Point2dMm(l, nextB)           // ↓ south, one sweep above bottom
        val nextL = l + sweepMm
        if (nextL >= r - 1) break
        pts += Point2dMm(nextL, nextB)       // → step into next lap
        l = nextL
        b = nextB
        r -= sweepMm
        t -= sweepMm
    }
    return pts
}

/**
 * Tests whether segment p1→p2 intersects the interior of an axis-aligned
 * rectangular polygon (any convex polygon works — uses Liang–Barsky-style
 * Cohen–Sutherland fallback via segment-segment checks).
 */
private fun segmentIntersectsRect(p1: Point2dMm, p2: Point2dMm, vertices: List<Point2dMm>): Boolean {
    if (vertices.size < 3) return false
    // Check segment vs each polygon edge
    for (i in vertices.indices) {
        val a = vertices[i]
        val b = vertices[(i + 1) % vertices.size]
        if (segmentsIntersect(
                p1.xMm.toDouble(), p1.yMm.toDouble(),
                p2.xMm.toDouble(), p2.yMm.toDouble(),
                a.xMm.toDouble(),  a.yMm.toDouble(),
                b.xMm.toDouble(),  b.yMm.toDouble()
            )
        ) return true
    }
    // Also true if both endpoints sit strictly inside the polygon
    return pointStrictlyInsideRect(p1, vertices) && pointStrictlyInsideRect(p2, vertices)
}

private fun segmentsIntersect(
    ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double
): Boolean {
    val ex = bx - ax; val ey = by - ay
    val fx = dx - cx; val fy = dy - cy
    val denom = ex * fy - ey * fx
    if (kotlin.math.abs(denom) < 1e-9) return false
    val t = ((cx - ax) * fy - (cy - ay) * fx) / denom
    val u = ((cx - ax) * ey - (cy - ay) * ex) / denom
    return t > 1e-9 && t < 1 - 1e-9 && u > 1e-9 && u < 1 - 1e-9
}

private fun pointStrictlyInsideRect(p: Point2dMm, vertices: List<Point2dMm>): Boolean {
    val minX = vertices.minOf { it.xMm }
    val maxX = vertices.maxOf { it.xMm }
    val minY = vertices.minOf { it.yMm }
    val maxY = vertices.maxOf { it.yMm }
    return p.xMm > minX && p.xMm < maxX && p.yMm > minY && p.yMm < maxY
}

/**
 * Sum of signed cross products of consecutive segment direction vectors.
 * For a path that turns predominantly CCW in standard math coords (y up),
 * this sum is positive.
 */
private fun signedTurnSum(segments: List<CoverageSegmentPayload>): Long {
    if (segments.size < 2) return 0
    var sum = 0L
    for (i in 0 until segments.size - 1) {
        val a = segments[i]
        val b = segments[i + 1]
        val ax = (a.toXMm - a.fromXMm).toLong()
        val ay = (a.toYMm - a.fromYMm).toLong()
        val bx = (b.toXMm - b.fromXMm).toLong()
        val by = (b.toYMm - b.fromYMm).toLong()
        sum += ax * by - ay * bx
    }
    return sum
}

/**
 * Custom coverage calculator using the actual mower sweep width.
 * A 100 mm grid cell is "covered" if any segment passes within sweepWidthMm / 2
 * of its centre — i.e. the cell lies within the mower's swept band.
 */
private fun computeCoverageWithSweep(
    zone: Zone,
    segments: List<CoverageSegmentPayload>,
    sweepWidthMm: Int,
    gridStepMm: Int
): Double {
    val halfSweep = sweepWidthMm / 2.0
    val halfSweepSq = halfSweep * halfSweep
    val minX = zone.vertices.minOf { it.xMm }
    val minY = zone.vertices.minOf { it.yMm }
    val maxX = zone.vertices.maxOf { it.xMm }
    val maxY = zone.vertices.maxOf { it.yMm }

    var total = 0
    var covered = 0
    var y = minY
    while (y <= maxY) {
        var x = minX
        while (x <= maxX) {
            if (pointStrictlyInsideRect(Point2dMm(x, y), zone.vertices)) {
                total++
                if (anySegmentWithin(x.toDouble(), y.toDouble(), segments, halfSweepSq)) {
                    covered++
                }
            }
            x += gridStepMm
        }
        y += gridStepMm
    }
    return if (total == 0) 0.0 else (covered.toDouble() / total.toDouble()) * 100.0
}

private fun anySegmentWithin(
    px: Double, py: Double,
    segments: List<CoverageSegmentPayload>,
    halfSweepSq: Double
): Boolean {
    for (s in segments) {
        val x1 = s.fromXMm.toDouble(); val y1 = s.fromYMm.toDouble()
        val x2 = s.toXMm.toDouble();   val y2 = s.toYMm.toDouble()
        val dx = x2 - x1; val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq < 1e-9) 0.0
                else max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / lenSq))
        val cx = x1 + t * dx
        val cy = y1 + t * dy
        val ddx = px - cx; val ddy = py - cy
        if (ddx * ddx + ddy * ddy <= halfSweepSq) return true
    }
    return false
}
