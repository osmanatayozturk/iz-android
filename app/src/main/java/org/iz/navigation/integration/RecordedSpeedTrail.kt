package org.iz.navigation.integration

import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.DiaryRules
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

internal data class JourneySpeedScale(val journeyId: String, val minKmh: Double, val maxKmh: Double)
internal data class SpeedTrailLine(val journeyId: String, val coordinates: List<GeoCoordinate>, val color: String)
internal data class RecordedSpeedTrail(
    val paths: List<List<GeoCoordinate>> = emptyList(),
    val lines: List<SpeedTrailLine> = emptyList(),
    val scales: List<JourneySpeedScale> = emptyList(),
)

private const val UNKNOWN_SPEED_COLOR = "#87939B"
private const val BLUE_SPEED_COLOR = "#2563EB"
private val speedPalette = intArrayOf(0x2563EB, 0x06B6D4, 0xEAB308, 0xDC2626)

/** Shared phone/Auto geometry. Invalid metadata is a break, never a reason to reconnect fixes. */
internal fun recordedPointPaths(points: List<TrackPoint>): List<List<TrackPoint>> = buildList {
    points.groupBy { it.journeyId }.values.forEach { journeyPoints ->
        var path = mutableListOf<TrackPoint>()
        fun finish() { if (path.isNotEmpty()) add(path.toList()); path = mutableListOf() }
        journeyPoints.sortedBy { it.recordedAt }.forEach { point ->
            if (!DiaryRules.isUsablePoint(point)) finish()
            else {
                if (point.breakBefore || path.lastOrNull()?.let { !DiaryRules.connects(it, point) } == true) finish()
                path += point
            }
        }
        finish()
    }
}

private fun TrackPoint.reportedKmh(): Double? = speed?.takeIf { it.isFinite() && it in 0f..100f }?.toDouble()?.times(3.6)
private fun TrackPoint.coordinate() = GeoCoordinate(latitude, longitude)
private fun drawable(a: TrackPoint, b: TrackPoint) = a.latitude != b.latitude || a.longitude != b.longitude

internal fun recordedSpeedTrail(points: List<TrackPoint>, ensureActive: () -> Unit = {}): RecordedSpeedTrail {
    ensureActive()
    val paths = recordedPointPaths(points)
    val bounds = linkedMapOf<String, Pair<Double, Double>>()
    paths.forEach { path ->
        path.zipWithNext().forEach { (a, b) ->
            val from = a.reportedKmh(); val to = b.reportedKmh()
            if (from != null && to != null && drawable(a, b)) {
                val old = bounds[a.journeyId]
                bounds[a.journeyId] = minOf(old?.first ?: from, from, to) to maxOf(old?.second ?: to, from, to)
            }
        }
    }
    val scales = bounds.map { (id, range) -> JourneySpeedScale(id, range.first, range.second) }
    val lines = mutableListOf<SpeedTrailLine>()
    // An adaptive subdivision budget prevents a noisy long track multiplying into millions of
    // features. Color endpoints remain represented; adjacent equal colors coalesce and simplify.
    val subdivisions = (80_000 / points.size.coerceAtLeast(1)).coerceIn(2, 32)
    var processed = 0
    paths.forEach { path ->
        var color: String? = null
        var coordinates = mutableListOf<GeoCoordinate>()
        fun finish() {
            if (coordinates.size > 1) lines += SpeedTrailLine(path.first().journeyId, coordinates.toList(), color ?: UNKNOWN_SPEED_COLOR)
            coordinates = mutableListOf(); color = null
        }
        fun append(from: GeoCoordinate, to: GeoCoordinate, nextColor: String) {
            if (color != nextColor || coordinates.lastOrNull() != from) finish()
            if (coordinates.isEmpty()) { coordinates += from; color = nextColor }
            if (coordinates.last() != to) {
                // Simplify only inside the same color run. This cannot remove speed transitions,
                // missing-data boundaries, extrema, or join two disconnected recording paths.
                while (coordinates.size >= 2 && redundantMiddle(coordinates[coordinates.lastIndex - 1], coordinates.last(), to)) {
                    coordinates.removeAt(coordinates.lastIndex)
                }
                coordinates += to
            }
        }
        path.zipWithNext().forEach { (a, b) ->
            if (++processed % 256 == 0) ensureActive()
            if (!drawable(a, b)) return@forEach
            val from = a.reportedKmh(); val to = b.reportedKmh()
            val range = bounds[a.journeyId]
            if (from == null || to == null || range == null) append(a.coordinate(), b.coordinate(), UNKNOWN_SPEED_COLOR)
            else if (range.second - range.first < 1.0) append(a.coordinate(), b.coordinate(), BLUE_SPEED_COLOR)
            else {
                val start = ((from - range.first) / (range.second - range.first)).coerceIn(0.0, 1.0)
                val end = ((to - range.first) / (range.second - range.first)).coerceIn(0.0, 1.0)
                val pieces = if (abs(start - end) < .001) 1 else ceil(abs(start - end) * 32).toInt().coerceIn(2, subdivisions)
                repeat(pieces) { part ->
                    val fraction = if (pieces == 1) .5 else part.toDouble() / (pieces - 1)
                    val value = start + (end - start) * fraction
                    append(interpolateCoordinate(a.coordinate(), b.coordinate(), part.toDouble() / pieces),
                        interpolateCoordinate(a.coordinate(), b.coordinate(), (part + 1.0) / pieces), speedColor(value))
                }
            }
        }
        finish()
    }
    ensureActive()
    return RecordedSpeedTrail(paths.map { path -> path.map { it.coordinate() } }, lines, scales)
}

private fun speedColor(value: Double): String {
    val quantized = (value.coerceIn(0.0, 1.0) * 63).roundToInt() / 63.0
    val position = quantized * speedPalette.lastIndex
    val index = position.toInt().coerceAtMost(speedPalette.lastIndex - 1)
    val ratio = position - index
    fun channel(shift: Int): Int {
        val a = speedPalette[index] shr shift and 255
        val b = speedPalette[index + 1] shr shift and 255
        return (a + (b - a) * ratio).roundToInt().coerceIn(0, 255)
    }
    return "#%02X%02X%02X".format(java.util.Locale.ROOT, channel(16), channel(8), channel(0))
}

private fun interpolateCoordinate(a: GeoCoordinate, b: GeoCoordinate, ratio: Double): GeoCoordinate {
    if (ratio <= 0.0) return a
    if (ratio >= 1.0) return b
    val longitudeDelta = ((b.longitude - a.longitude + 540) % 360) - 180
    val longitude = ((a.longitude + longitudeDelta * ratio + 540) % 360) - 180
    return GeoCoordinate(a.latitude + (b.latitude - a.latitude) * ratio, longitude)
}

private fun redundantMiddle(a: GeoCoordinate, b: GeoCoordinate, c: GeoCoordinate): Boolean {
    if (abs(c.longitude - a.longitude) > 180) return false
    val x = (c.longitude - a.longitude) * cos(Math.toRadians(a.latitude)) * 111_320
    val y = (c.latitude - a.latitude) * 111_320
    val bx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude)) * 111_320
    val by = (b.latitude - a.latitude) * 111_320
    val length2 = x * x + y * y
    if (length2 == 0.0) return false
    val progress = (bx * x + by * y) / length2
    if (progress !in 0.0..1.0) return false
    val dx = bx - x * progress; val dy = by - y * progress
    return dx * dx + dy * dy <= 4.0
}

internal fun RecordedSpeedTrail.geoJson(): String = featureCollection(lines.map { line ->
    JSONObject().put("type", "Feature").put("properties", JSONObject().put("color", line.color).put("journeyId", line.journeyId))
        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray().apply {
            line.coordinates.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
        }))
})
