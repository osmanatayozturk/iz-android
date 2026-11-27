package org.iz.navigation.integration

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class OsmDirectedPoint(val latitude: Double, val longitude: Double)

/**
 * Geometry of the account's submitted GPX plus what this plan has retained so far.
 * Use one instance per plan. Candidate times order the claims, while matching
 * and the committed coverage use geometry alone. Bounds failures invalidate the plan.
 */
class OsmDirectedCoverage(history: List<List<OsmDirectedPoint>>) {
    private val coverage = CoverageGrid()

    init {
        require(history.size <= MAX_SEGMENTS) { "OSM coverage segment limit exceeded" }
        var inputPoints = 0L
        for (segment in history) {
            inputPoints += segment.size
            require(inputPoints <= MAX_INDEX_EDGES) { "OSM coverage point limit exceeded" }
            val edges = mutableListOf<DirectedEdge>()
            var previous: Position? = null
            for (point in segment) {
                val position = toPosition(point.latitude, point.longitude)
                previous?.let { appendHistoricalEdges(it, position, edges) }
                previous = position
            }
            for (index in edges.indices) {
                edges[index].travel = smoothedHeading(edges, index, 0, edges.size) ?: edges[index].rawHeading
            }
            edges.forEach(coverage::add)
        }
    }

    fun filter(trace: PreparedOsmGpxTrace): PreparedOsmGpxTrace? {
        val pending = CoverageGrid()
        val result = filterOne(trace, pending)
        coverage.addAll(pending)
        return result
    }

    /** Claims each original leg in passage order. Returned artifacts retain the caller's order. */
    fun filterChronologically(traces: List<PreparedOsmGpxTrace>): List<PreparedOsmGpxTrace> {
        if (traces.isEmpty()) return emptyList()
        require(traces.sumOf { trace -> trace.coverageSegments.sumOf { it.size.toLong() } } <= MAX_INDEX_EDGES) {
            "OSM coverage batch point limit exceeded"
        }
        val byTime = traces.indices.sortedWith(
            compareBy<Int> { traces[it].firstFixTime() }.thenBy { traces[it].journeyId },
        )
        val results = arrayOfNulls<PreparedOsmGpxTrace>(traces.size)
        val pending = CoverageGrid()
        val overlapsInTime = byTime.zipWithNext().any { (earlier, later) ->
            traces[earlier].lastFixTime() > traces[later].firstFixTime()
        }
        if (overlapsInTime) {
            filterOverlapping(traces, pending, results)
        } else {
            for (index in byTime) results[index] = filterOne(traces[index], pending)
        }
        coverage.addAll(pending)
        return results.filterNotNull()
    }

    private fun filterOne(trace: PreparedOsmGpxTrace, pending: CoverageGrid): PreparedOsmGpxTrace? {
        val input = checkedInput(trace)
        val budget = ComparisonBudget()
        val retained = input.flatMap { segment ->
            val cursor = SegmentCursor(segment, budget)
            while (cursor.hasNext) cursor.advance(pending)
            cursor.retained
        }
        return filteredTrace(trace, retained)
    }

    private fun filterOverlapping(
        traces: List<PreparedOsmGpxTrace>,
        pending: CoverageGrid,
        results: Array<PreparedOsmGpxTrace?>,
    ) {
        val provisional = CoverageGrid()
        val queue = PriorityQueue(compareBy<QueuedSegment> { it.cursor.nextPassageAt }
            .thenBy { traces[it.traceIndex].journeyId }.thenBy { it.segmentIndex }.thenBy { it.traceIndex })
        val segments = traces.mapIndexed { traceIndex, trace ->
            val budget = ComparisonBudget()
            checkedInput(trace).mapIndexed { index, segment ->
                QueuedSegment(traceIndex, index, SegmentCursor(segment, budget, provisional)).also(queue::add)
            }
        }
        while (queue.isNotEmpty()) {
            val next = queue.remove()
            next.cursor.advance(pending)
            if (next.cursor.hasNext) queue.add(next)
        }
        for (index in traces.indices) {
            results[index] = filteredTrace(traces[index], segments[index].flatMap { it.cursor.retained })
        }
    }

    private data class QueuedSegment(val traceIndex: Int, val segmentIndex: Int, val cursor: SegmentCursor)

    private inner class SegmentCursor(
        private val segment: List<OsmGpxFix>,
        private val budget: ComparisonBudget,
        private val provisional: CoverageGrid? = null,
    ) {
        val retained = mutableListOf<MutableList<OsmGpxFix>>()
        private val retainedTail = ArrayDeque<DirectedEdge>(HEADING_LEGS)
        private var activeRun: MutableList<OsmGpxFix>? = null
        private var previousWasCovered = false
        private var delayedEdge: DirectedEdge? = null
        private val positions = segment.map { toPosition(it.point.latitude, it.point.longitude) }
        private val edges = List(segment.lastIndex) { i ->
            candidateEdge(segment[i], segment[i + 1], positions[i], positions[i + 1])
        }
        private var runStart = 0
        private var runEnd = 0
        private var i = 0
        val hasNext: Boolean get() = i < edges.size
        val nextPassageAt: Long
            get() {
                val start = segment[i].point.recordedAt
                val end = segment[i + 1].point.recordedAt
                return if (edges[i] == null) end else start + (end - start) / 2
            }

        // Wait one edge before retaining a leg so that neither endpoint of a
        // covered leg (and thus neither of its times) leaks through a neighbor.
        private fun finishEdge(end: Int, nextWasCovered: Boolean, pending: CoverageGrid) {
            val edge = delayedEdge
            if (edge == null || nextWasCovered) {
                activeRun = null
                retainedTail.clear()
                return
            }
            require(coverage.size + pending.size < MAX_INDEX_EDGES) { "OSM coverage edge limit exceeded" }
            val retainedEdge = DirectedEdge(edge.start, edge.end)
            if (provisional != null) {
                // A surviving claim keeps the direction used to arbitrate its timestamp.
                retainedEdge.travel = edge.travel
            } else {
                if (retainedTail.size == HEADING_LEGS) retainedTail.removeFirst()
                retainedTail.addLast(retainedEdge)
                coherentHeading(retainedTail, 0, retainedTail.size)?.let { heading ->
                    retainedTail.forEach { it.travel = heading }
                }
            }
            pending.add(retainedEdge)
            val run = activeRun ?: mutableListOf(segment[end - 1]).also {
                retained.add(it)
                activeRun = it
            }
            run.add(segment[end])
        }

        fun advance(pending: CoverageGrid) {
            // This cursor's previous leg awaits the existing neighbor privacy
            // guard. Other cursors can see that earlier claim until it resolves.
            val waiting = delayedEdge
            if (waiting != null) provisional?.remove(waiting)
            val index = i + 1
            val previous = segment[i]
            val current = segment[index]
            val edge = edges[i]
            val crossing = if (edge == null) {
                null
            } else {
                if (i >= runEnd) {
                    runStart = i
                    runEnd = i + 1
                    while (runEnd < edges.size && edges[runEnd] != null) runEnd++
                }
                edge.travel = smoothedHeading(edges, i, runStart, runEnd) ?: edge.rawHeading
                CrossingContext(positions[maxOf(runStart, i - CROSSING_LEGS)],
                    positions[minOf(runEnd, index + CROSSING_LEGS)])
            }
            val corridor = maxOf(MIN_CORRIDOR_METERS, maxOf(previous.accuracy, current.accuracy).toDouble())
            val covered = edge != null && crossing != null &&
                (coverage.overlaps(edge, corridor, crossing, budget) ||
                    pending.overlaps(edge, corridor, crossing, budget) ||
                    provisional?.overlaps(edge, corridor, crossing, budget) == true)
            finishEdge(index - 1, covered, pending)
            val claim = edge?.takeUnless { covered || previousWasCovered }
            delayedEdge = claim
            previousWasCovered = covered
            i = index
            if (!hasNext) {
                finishEdge(index, nextWasCovered = false, pending)
            } else if (claim != null && provisional != null) {
                require(coverage.size + pending.size + provisional.size < MAX_INDEX_EDGES) {
                    "OSM coverage edge limit exceeded"
                }
                provisional.add(claim)
            }
        }
    }

    private fun appendHistoricalEdges(start: Position, end: Position, edges: MutableList<DirectedEdge>) {
        val offset = end - start
        val distance = offset.length()
        if (distance <= LONGITUDINAL_EPSILON_METERS) return
        require(distance <= MAX_HISTORICAL_STEP_METERS) { "OSM coverage history has an excessive gap" }
        val steps = ceil(distance / HISTORY_INDEX_EDGE_METERS).toInt()
        var from = start
        for (step in 1..steps) {
            val to = if (step == steps) end else (start + offset * (step.toDouble() / steps)).onEarth()
            require(coverage.size + edges.size < MAX_INDEX_EDGES) { "OSM coverage edge limit exceeded" }
            edges.add(DirectedEdge(from, to))
            from = to
        }
    }
}

private fun PreparedOsmGpxTrace.firstFixTime() = coverageSegments.first().first().point.recordedAt
private fun PreparedOsmGpxTrace.lastFixTime() = coverageSegments.last().last().point.recordedAt

private fun checkedInput(trace: PreparedOsmGpxTrace): List<List<OsmGpxFix>> {
    val input = trace.coverageSegments
    require(input.sumOf { it.size.toLong() } <= MAX_CANDIDATE_POINTS) {
        "OSM coverage candidate point limit exceeded"
    }
    return input
}

private fun filteredTrace(trace: PreparedOsmGpxTrace, retained: List<List<OsmGpxFix>>): PreparedOsmGpxTrace? {
    if (retained.isEmpty()) return null
    return if (retained == trace.coverageSegments) trace else trace.withCoverageSegments(retained)
}

private fun smoothedHeading(edges: List<DirectedEdge?>, index: Int, start: Int, end: Int): Position? {
    val count = minOf(HEADING_LEGS, end - start)
    val first = (index - count / 2).coerceIn(start, end - count)
    return coherentHeading(edges, first, first + count)
}

/** A short chord suppresses fix-to-fix jitter only when each leg supports forward progress. */
private fun coherentHeading(edges: List<DirectedEdge?>, first: Int, end: Int): Position? {
    val offset = checkNotNull(edges[end - 1]).end - checkNotNull(edges[first]).start
    val span = offset.length()
    if (span < MIN_MOVEMENT_METERS || span > MAX_HEADING_SPAN_METERS) return null
    val heading = offset * (1.0 / span)
    var length = 0.0
    for (i in first until end) {
        val edge = checkNotNull(edges[i])
        if ((heading dot edge.rawHeading) < MIN_LEG_AGREEMENT) return null
        length += edge.length
    }
    return heading.takeIf { span >= MIN_CHORD_RATIO * length }
}

private fun candidateEdge(a: OsmGpxFix, b: OsmGpxFix, start: Position, end: Position): DirectedEdge? {
    if (a.accuracy <= 0f || a.accuracy > MAX_FIX_ACCURACY_METERS ||
        b.accuracy <= 0f || b.accuracy > MAX_FIX_ACCURACY_METERS ||
        b.point.recordedAt - a.point.recordedAt !in 1L..MAX_CANDIDATE_GAP_MILLIS ||
        b.distanceFromStart <= a.distanceFromStart
    ) return null
    val minimum = maxOf(MIN_MOVEMENT_METERS, maxOf(a.accuracy, b.accuracy) * 0.5)
    if ((end - start).length() !in minimum..MAX_CANDIDATE_STEP_METERS) return null
    return DirectedEdge(start, end)
}

/** Earth-centered meters keep the same corridor at high latitudes and across the date line. */
private data class Position(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Position) = Position(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Position) = Position(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Double) = Position(x * factor, y * factor, z * factor)
    infix fun dot(other: Position): Double = x * other.x + y * other.y + z * other.z
    infix fun cross(other: Position) =
        Position(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun length(): Double = sqrt(this dot this)
    fun onEarth(): Position = this * (EARTH_RADIUS_METERS / length())
}

private fun toPosition(latitude: Double, longitude: Double): Position {
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid OSM coverage coordinate" }
    val lat = Math.toRadians(latitude)
    val lon = Math.toRadians(longitude)
    val horizontal = EARTH_RADIUS_METERS * cos(lat)
    return Position(horizontal * cos(lon), horizontal * sin(lon), EARTH_RADIUS_METERS * sin(lat))
}

private data class CrossingContext(val before: Position, val after: Position)

private class DirectedEdge(val start: Position, val end: Position) {
    private val offset = end - start
    val length = offset.length()
    val rawHeading = offset * (1.0 / length)
    var travel = rawHeading
    val cell = GridCell.of(start + offset * 0.5)

    fun overlaps(history: DirectedEdge, corridor: Double, crossing: CrossingContext): Boolean {
        if ((travel dot history.travel) < MIN_HEADING_ALIGNMENT) return false

        // Clip to the historical leg's longitudinal extent. An endpoint touch
        // and adjacent forward legs must not count as another traversal.
        val delta = start - history.start
        val projectedStart = delta dot history.rawHeading
        val rate = rawHeading dot history.rawHeading
        var lower = 0.0
        var upper = length
        if (abs(rate) > 1e-9) {
            val enter = -projectedStart / rate
            val exit = (history.length - projectedStart) / rate
            lower = maxOf(0.0, minOf(enter, exit))
            upper = minOf(length, maxOf(enter, exit))
        } else if (projectedStart !in 0.0..history.length) {
            return false
        }
        if (upper - lower <= LONGITUDINAL_EPSILON_METERS) return false

        val lateralStart = delta - history.rawHeading * projectedStart
        val lateralHeading = rawHeading - history.rawHeading * rate
        val lateralRateSquared = lateralHeading dot lateralHeading
        val closestAlong = if (lateralRateSquared < 1e-12) lower else
            (-(lateralStart dot lateralHeading) / lateralRateSquared).coerceIn(lower, upper)
        val closest = lateralStart + lateralHeading * closestAlong
        return (closest dot closest) <= corridor * corridor &&
            !crosses(history, corridor, crossing)
    }

    /** An oblique crossing needs measured approach and departure outside both sides of the corridor. */
    private fun crosses(history: DirectedEdge, corridor: Double, context: CrossingContext): Boolean {
        val progression = context.after - context.before
        if ((progression dot rawHeading) < MIN_CROSSING_AGREEMENT * progression.length()) return false
        val transverse = history.travel cross history.start
        val across = transverse * (1.0 / transverse.length())
        val lateralRate = rawHeading dot across
        if (abs(lateralRate) < MIN_CROSSING_SLOPE) return false
        val sign = if (lateralRate > 0.0) 1.0 else -1.0
        val approach = ((context.before - history.start) dot across) * sign
        val departure = ((context.after - history.start) dot across) * sign
        return approach < -corridor - CROSSING_MARGIN_METERS && departure > corridor + CROSSING_MARGIN_METERS
    }
}

private data class GridCell(val x: Int, val y: Int, val z: Int) {
    companion object {
        fun of(point: Position) = GridCell(
            floor(point.x / GRID_METERS).toInt(),
            floor(point.y / GRID_METERS).toInt(),
            floor(point.z / GRID_METERS).toInt(),
        )
    }
}

private class CoverageGrid {
    private val cells = mutableMapOf<GridCell, MutableList<DirectedEdge>>()
    var size: Int = 0
        private set

    fun add(edge: DirectedEdge) {
        cells.getOrPut(edge.cell) { mutableListOf() }.add(edge)
        size++
    }

    fun remove(edge: DirectedEdge) {
        val edges = cells.getValue(edge.cell)
        check(edges.remove(edge))
        if (edges.isEmpty()) cells.remove(edge.cell)
        size--
    }

    fun addAll(other: CoverageGrid) {
        for ((cell, edges) in other.cells) cells.getOrPut(cell) { mutableListOf() }.addAll(edges)
        size += other.size
    }

    fun overlaps(edge: DirectedEdge, corridor: Double, crossing: CrossingContext, budget: ComparisonBudget): Boolean {
        if (cells.isEmpty()) return false
        val cell = edge.cell
        // Possible matches have center separation bounded by the two half-lengths
        // plus the corridor, which is strictly smaller than one grid cell.
        for (x in cell.x - 1..cell.x + 1) for (y in cell.y - 1..cell.y + 1) for (z in cell.z - 1..cell.z + 1) {
            val neighbors = cells[GridCell(x, y, z)] ?: continue
            for (other in neighbors) {
                budget.compare()
                if (edge.overlaps(other, corridor, crossing)) return true
            }
        }
        return false
    }
}

private class ComparisonBudget {
    private var remaining = MAX_COMPARISONS

    fun compare() {
        require(remaining-- > 0) { "OSM coverage comparison limit exceeded" }
    }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MAX_FIX_ACCURACY_METERS = 25f
private const val MAX_CANDIDATE_GAP_MILLIS = 30_000L
private const val MIN_MOVEMENT_METERS = 2.99 // Tolerates chord rounding at DiaryRules' 3 m threshold.
private const val MAX_CANDIDATE_STEP_METERS = 100.0
private const val MAX_HISTORICAL_STEP_METERS = 50_000.0
private const val HISTORY_INDEX_EDGE_METERS = 90.0
private const val MIN_CORRIDOR_METERS = 12.0
private const val MIN_HEADING_ALIGNMENT = 0.8660254037844386 // cos(30 degrees)
private const val HEADING_LEGS = 4
private const val MAX_HEADING_SPAN_METERS = 140.0
private const val MIN_LEG_AGREEMENT = 0.65
private const val MIN_CHORD_RATIO = 0.72
private const val CROSSING_LEGS = 30
private const val MIN_CROSSING_AGREEMENT = 0.9902680687415704 // cos(8 degrees)
private const val MIN_CROSSING_SLOPE = 0.0697564737441253 // sin(4 degrees)
private const val CROSSING_MARGIN_METERS = 2.0
private const val LONGITUDINAL_EPSILON_METERS = 0.05
private const val GRID_METERS = 128.0
private const val MAX_SEGMENTS = 50_000
private const val MAX_CANDIDATE_POINTS = 50_000
private const val MAX_INDEX_EDGES = 300_000
private const val MAX_COMPARISONS = 4_000_000
