package org.iz.navigation.integration

import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round

data class HeatmapTraceLine(val coordinates: List<GeoCoordinate>, val journeyCount: Int)
data class HeatmapTracePoint(val coordinate: GeoCoordinate, val journeyCount: Int)
data class HeatmapData(
    val lines: List<HeatmapTraceLine> = emptyList(),
    val isolatedPoints: List<HeatmapTracePoint> = emptyList(),
    val journeyCount: Int = 0,
)

private const val CELL_METERS = 30.0
private const val METERS_PER_DEGREE = 111_195.0
private const val MAX_MAP_LATITUDE = 85.05112878
private data class CellKey(val row: Int, val column: Int)

/**
 * Count distinct saved journeys in approximate 30 m regions, then color the actual
 * recorded geometry with those counts. Every crossed region contributes, including
 * between sparse fixes. Returning or reversing within a journey adds no extra weight.
 * This function does no I/O; callers can run it on a worker and supply cancellation.
 */
fun buildHeatmapData(
    journeys: List<Journey>,
    points: List<TrackPoint>,
    transport: Transport? = null,
    now: Long,
    checkActive: () -> Unit = {},
): HeatmapData {
    checkActive()
    var work = 0
    fun checkpoint() { if (++work % 256 == 0) checkActive() }
    val eligibleIds = journeys.filter {
        checkpoint()
        it.status == JourneyStatus.CONFIRMED && (it.expiresAt == null || it.expiresAt > now) &&
            (transport == null || it.transport == transport)
    }.map { it.id }.toHashSet()
    if (eligibleIds.isEmpty()) return HeatmapData()

    val grouped = mutableMapOf<String, MutableList<TrackPoint>>()
    points.forEach { point ->
        checkpoint()
        if (point.journeyId in eligibleIds) grouped.getOrPut(point.journeyId) { mutableListOf() }.add(point)
    }
    val sampleOrder = compareBy<TrackPoint> { it.recordedAt }.thenBy { it.latitude }.thenBy { it.longitude }
        .thenBy { it.accuracy }.thenBy { it.speed }.thenBy { it.altitude }.thenBy { it.breakBefore }
    val pathsByJourney = linkedMapOf<String, List<List<GeoCoordinate>>>()
    grouped.keys.sorted().forEach { id ->
        checkActive()
        val sorted = grouped.getValue(id).sortedWith { a, b -> checkpoint(); sampleOrder.compare(a, b) }
        // Duplicate imports/fixes must not create a zero-time break. Distinct fixes at
        // the same timestamp remain separate and obey the shared recording rules.
        val unique = ArrayList<TrackPoint>(sorted.size)
        sorted.forEach { point ->
            checkpoint()
            if (unique.lastOrNull()?.let { sampleOrder.compare(it, point) == 0 } != true) unique += point
        }
        val paths = mutableListOf<List<GeoCoordinate>>()
        recordedPointPaths(unique).forEach { recorded ->
            var path = mutableListOf<GeoCoordinate>()
            fun finish() { if (path.isNotEmpty()) paths += path; path = mutableListOf() }
            recorded.forEach { point ->
                checkpoint()
                // A fix outside Mercator is a break, not a removed interior vertex.
                if (point.latitude !in -MAX_MAP_LATITUDE..MAX_MAP_LATITUDE) finish()
                // Both names of the dateline must share traversal cells, including
                // a trace lying exactly on that meridian. Crossing edge vertices
                // are introduced later, where +180 is needed for the eastern half.
                else path += GeoCoordinate(point.latitude, if (point.longitude == 180.0) -180.0 else point.longitude)
            }
            finish()
        }
        if (paths.isNotEmpty()) pathsByJourney[id] = paths
    }

    val counts = mutableMapOf<CellKey, Int>()
    pathsByJourney.values.forEach { paths ->
        val visited = hashSetOf<CellKey>()
        paths.forEach { path ->
            path.forEach { checkpoint(); visited += cellAt(it) }
            for (index in 1 until path.size) {
                checkpoint()
                visitSegmentCells(path[index - 1], path[index], ::checkpoint) { _, _, cell -> visited += cell }
            }
        }
        visited.forEach { checkpoint(); counts[it] = (counts[it] ?: 0) + 1 }
    }

    val lines = mutableListOf<HeatmapTraceLine>()
    val isolated = linkedSetOf<HeatmapTracePoint>()
    pathsByJourney.values.forEach { paths ->
        paths.forEach { path ->
            var coordinates = mutableListOf<GeoCoordinate>()
            var count = 0
            var hasLine = false
            fun finish() {
                if (coordinates.size > 1) lines += HeatmapTraceLine(coordinates.toList(), count)
                coordinates = mutableListOf()
            }
            for (index in 1 until path.size) {
                checkpoint()
                visitSegmentCells(path[index - 1], path[index], ::checkpoint) { from, to, cell ->
                    hasLine = true
                    val nextCount = counts.getValue(cell)
                    if (nextCount != count || coordinates.lastOrNull() != from) finish()
                    if (coordinates.isEmpty()) { coordinates += from; count = nextCount }
                    if (coordinates.last() != to) {
                        while (coordinates.size >= 2 && collinearBetween(coordinates[coordinates.lastIndex - 1], coordinates.last(), to)) {
                            coordinates.removeAt(coordinates.lastIndex)
                        }
                        coordinates += to
                    }
                }
            }
            finish()
            if (!hasLine) path.forEach { coordinate ->
                checkpoint()
                val normalized = if (coordinate.longitude == 180.0) coordinate.copy(longitude = -180.0) else coordinate
                isolated += HeatmapTracePoint(normalized, counts.getValue(cellAt(coordinate)))
            }
        }
    }
    checkActive()
    return HeatmapData(lines.distinct(), isolated.toList(), pathsByJourney.size)
}

private fun rowAt(latitude: Double): Int = floor((latitude + 90.0) * METERS_PER_DEGREE / CELL_METERS).toInt()

private fun longitudeScale(row: Int): Double {
    val latitude = (row + 0.5) * CELL_METERS / METERS_PER_DEGREE - 90.0
    return METERS_PER_DEGREE * cos(Math.toRadians(latitude))
}

private fun cellAt(coordinate: GeoCoordinate): CellKey {
    val row = rowAt(coordinate.latitude)
    val longitude = if (coordinate.longitude == 180.0) -180.0 else coordinate.longitude
    return CellKey(row, floor((longitude + 180.0) * longitudeScale(row) / CELL_METERS).toInt())
}

/** Split the short dateline crossing before any interpolation or cell traversal. */
private fun visitSegmentCells(
    from: GeoCoordinate,
    to: GeoCoordinate,
    checkpoint: () -> Unit,
    visit: (GeoCoordinate, GeoCoordinate, CellKey) -> Unit,
) {
    if (from == to) return
    val delta = to.longitude - from.longitude
    if (abs(delta) <= 180.0) {
        visitUnwrappedCells(from, to, checkpoint, visit)
        return
    }
    val unwrappedEnd = to.longitude + if (delta > 180.0) -360.0 else 360.0
    val edge = if (unwrappedEnd >= 180.0) 180.0 else -180.0
    val ratio = (edge - from.longitude) / (unwrappedEnd - from.longitude)
    val latitude = canonical(from.latitude + (to.latitude - from.latitude) * ratio)
    visitUnwrappedCells(from, GeoCoordinate(latitude, edge), checkpoint, visit)
    visitUnwrappedCells(GeoCoordinate(latitude, -edge), to, checkpoint, visit)
}

/** Latitude bands keep the longitude cell width near 30 ground metres at each latitude. */
private fun visitUnwrappedCells(
    from: GeoCoordinate,
    to: GeoCoordinate,
    checkpoint: () -> Unit,
    visit: (GeoCoordinate, GeoCoordinate, CellKey) -> Unit,
) {
    if (from == to) return
    val rowCuts = boundaryFractions((from.latitude + 90.0) * METERS_PER_DEGREE,
        (to.latitude + 90.0) * METERS_PER_DEGREE, checkpoint)
    for (rowIndex in 1 until rowCuts.size) {
        checkpoint()
        val start = rowCuts[rowIndex - 1]
        val end = rowCuts[rowIndex]
        val row = rowAt(from.latitude + (to.latitude - from.latitude) * ((start + end) / 2))
        val scale = longitudeScale(row)
        val startLongitude = from.longitude + (to.longitude - from.longitude) * start
        val endLongitude = from.longitude + (to.longitude - from.longitude) * end
        val columns = boundaryFractions((startLongitude + 180.0) * scale, (endLongitude + 180.0) * scale, checkpoint)
        for (columnIndex in 1 until columns.size) {
            checkpoint()
            val first = start + (end - start) * columns[columnIndex - 1]
            val last = start + (end - start) * columns[columnIndex]
            val middleLongitude = from.longitude + (to.longitude - from.longitude) * ((first + last) / 2)
            val column = floor((middleLongitude + 180.0) * scale / CELL_METERS).toInt()
            val a = interpolate(from, to, first)
            val b = interpolate(from, to, last)
            if (a != b) visit(a, b, CellKey(row, column))
        }
    }
}

private fun boundaryFractions(from: Double, to: Double, checkpoint: () -> Unit): List<Double> {
    val cuts = mutableListOf(0.0, 1.0)
    if (from == to) return cuts
    var boundary = (floor(minOf(from, to) / CELL_METERS) + 1) * CELL_METERS
    while (boundary < maxOf(from, to)) {
        checkpoint()
        val fraction = (boundary - from) / (to - from)
        if (fraction > 0.0 && fraction < 1.0) cuts += fraction
        boundary += CELL_METERS
    }
    cuts.sort()
    return cuts
}

private fun canonical(value: Double): Double = round(value * 1e12) / 1e12

private fun interpolate(from: GeoCoordinate, to: GeoCoordinate, ratio: Double): GeoCoordinate = when {
    ratio <= 0.0 -> from
    ratio >= 1.0 -> to
    else -> GeoCoordinate(canonical(from.latitude + (to.latitude - from.latitude) * ratio),
        canonical(from.longitude + (to.longitude - from.longitude) * ratio))
}

/** Only discard numerically collinear interior vertices; never simplify a turn or return. */
private fun collinearBetween(a: GeoCoordinate, b: GeoCoordinate, c: GeoCoordinate): Boolean {
    val x = c.longitude - a.longitude
    val y = c.latitude - a.latitude
    val bx = b.longitude - a.longitude
    val by = b.latitude - a.latitude
    val lengthSquared = x * x + y * y
    if (lengthSquared == 0.0 || abs(x) > 180.0) return false
    val fraction = (bx * x + by * y) / lengthSquared
    return fraction in 0.0..1.0 && (bx * y - by * x).let { it * it <= 1e-20 * lengthSquared }
}
