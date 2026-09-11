package com.atay.iz.integration

import com.atay.iz.data.DiaryRules
import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.TrackPoint
import com.atay.iz.data.Transport
import kotlin.math.cos
import kotlin.math.floor

data class HeatmapCell(val latitude: Double, val longitude: Double, val journeyCount: Int, val intensity: Double)
data class HeatmapData(val cells: List<HeatmapCell> = emptyList(), val journeyCount: Int = 0)

private const val CELL_METERS = 30.0
private const val METERS_PER_DEGREE = 111_195.0
private const val MAX_MAP_LATITUDE = 85.05112878
private data class CellKey(val row: Int, val column: Int)

/**
 * A cell records the number of distinct saved journeys that visited it. Repeated
 * fixes (including stationary GPS samples) within one journey add no extra weight.
 * Coordinates represent aggregated cells, never invented connecting route points.
 */
fun buildHeatmapData(
    journeys: List<Journey>,
    points: List<TrackPoint>,
    transport: Transport? = null,
    now: Long,
): HeatmapData {
    val eligibleIds = journeys.asSequence().filter {
        it.status == JourneyStatus.CONFIRMED && (it.expiresAt == null || it.expiresAt > now) &&
            (transport == null || it.transport == transport)
    }.map { it.id }.toHashSet()
    if (eligibleIds.isEmpty()) return HeatmapData()

    val cellJourneys = mutableMapOf<CellKey, MutableSet<String>>()
    val mappedJourneys = mutableSetOf<String>()
    points.forEach { point ->
        if (point.journeyId !in eligibleIds || !DiaryRules.isUsablePoint(point) ||
            point.latitude !in -MAX_MAP_LATITUDE..MAX_MAP_LATITUDE
        ) return@forEach
        val row = floor((point.latitude + 90.0) * METERS_PER_DEGREE / CELL_METERS).toInt()
        val centerLatitude = (row + 0.5) * CELL_METERS / METERS_PER_DEGREE - 90.0
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude))
        // +180 and -180 denote the same meridian.
        val longitude = if (point.longitude == 180.0) -180.0 else point.longitude
        val column = floor((longitude + 180.0) * longitudeScale / CELL_METERS).toInt()
        cellJourneys.getOrPut(CellKey(row, column)) { mutableSetOf() }.add(point.journeyId)
        mappedJourneys += point.journeyId
    }
    val maximum = cellJourneys.values.maxOfOrNull { it.size } ?: return HeatmapData()
    val cells = cellJourneys.entries.sortedWith(compareBy({ it.key.row }, { it.key.column })).map { (key, ids) ->
        val latitude = (key.row + 0.5) * CELL_METERS / METERS_PER_DEGREE - 90.0
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(latitude))
        HeatmapCell(
            latitude = latitude.coerceIn(-MAX_MAP_LATITUDE, MAX_MAP_LATITUDE),
            longitude = ((key.column + 0.5) * CELL_METERS / longitudeScale - 180.0).coerceIn(-180.0, 180.0),
            journeyCount = ids.size,
            intensity = ids.size.toDouble() / maximum,
        )
    }
    return HeatmapData(cells, mappedJourneys.size)
}
