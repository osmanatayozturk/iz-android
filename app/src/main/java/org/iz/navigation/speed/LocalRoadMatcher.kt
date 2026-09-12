package org.iz.navigation.speed

import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import kotlin.math.*

/** Conservative 2D matching: another plausible road, including an overpass, makes the limit unknown. */
internal class LocalRoadMatcher {
    private var previous: NavigationFix? = null
    private var candidate: RoadMatch? = null
    private var stableCount = 0
    private var since = 0L
    private var heading: Double? = null
    private var result: RoadMatch? = null

    fun reset() { previous = null; candidate = null; stableCount = 0; heading = null; result = null }

    fun match(fix: NavigationFix, roads: List<OsmRoad>): RoadMatch? {
        if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..25f) { reset(); return null }
        val last = previous
        if (last != null && fix.recordedAt <= last.recordedAt) return result.takeIf { fix == last }
        val moved = last?.let { distance(it.coordinate, fix.coordinate) } ?: 0.0
        if (last != null && (fix.recordedAt - last.recordedAt > 5_000 || moved > max(80.0,
                (fix.recordedAt - last.recordedAt) / 1000.0 * (fix.speedMps?.toDouble()?.coerceIn(0.0, 100.0) ?: 30.0) * 2 + fix.accuracyMeters * 2))) reset()
        val gpsHeading = fix.bearingDegrees?.takeIf { it.isFinite() && it in 0f..360f && (fix.speedMps ?: 0f) >= 2f }?.toDouble()
        heading = gpsHeading ?: if (last != null && moved >= 8) bearing(last.coordinate, fix.coordinate) else heading
        previous = fix
        val direction = heading ?: return null.also { result = null }
        val corridor = max(12.0, fix.accuracyMeters * 1.5).coerceAtMost(30.0)
        val possibilities = roads.mapNotNull { road ->
            val hits = road.points.zipWithNext().mapIndexed { index, (a, b) -> project(fix.coordinate, a, b, index) }
                .filter { it.distance <= corridor }.sortedBy { it.distance }
            val nearest = hits.firstOrNull() ?: return@mapNotNull null
            // Dense consecutive vertices describe one corridor. Disjoint hits or folded geometry
            // describe competing sections, even when OSM gives them the same way identifier.
            if (hits.sortedBy { it.index }.zipWithNext().any { (a, b) -> b.index > a.index + 1 } ||
                hits.any { angularDifference(it.bearing, nearest.bearing) > 100 }) return clearCandidate()
            val delta = angularDifference(direction, nearest.bearing)
            RoadMatch(road, delta <= 90, nearest.index, nearest.distance)
        }
        if (possibilities.size != 1) return clearCandidate()
        val match = possibilities.single()
        val segmentBearing = bearing(match.road.points[match.segment], match.road.points[match.segment + 1])
        if (min(angularDifference(direction, segmentBearing), angularDifference(direction, segmentBearing + 180)) > 35) return clearCandidate()
        val oneway = match.road.tags["oneway"]
        if ((oneway in setOf("yes", "1", "true") && !match.forward) || oneway == "-1" && match.forward) return clearCandidate()
        val old = candidate
        if (old?.identity != match.identity) {
            // Connected roads still reacquire at junctions; disconnected roads never inherit confidence.
            val connected = old?.road?.nodeIds?.any { it in match.road.nodeIds } == true
            stableCount = 1
            since = fix.recordedAt
            if (!connected) result = null
        } else stableCount++
        candidate = match
        result = match.takeIf { stableCount >= 3 && fix.recordedAt - since >= 2_000 }
        return result
    }

    private fun clearCandidate(): RoadMatch? { candidate = null; stableCount = 0; result = null; return null }
}

internal fun distance(a: WeatherCoordinate, b: WeatherCoordinate) = WeatherEngine.distanceMeters(a, b)
internal fun angularDifference(a: Double, b: Double) = abs((a - b + 540) % 360 - 180)
internal fun bearing(a: WeatherCoordinate, b: WeatherCoordinate): Double =
    (Math.toDegrees(atan2(((b.longitude - a.longitude + 540) % 360 - 180) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), b.latitude - a.latitude)) + 360) % 360

internal data class RoadProjection(val index: Int, val fraction: Double, val distance: Double, val bearing: Double)
internal fun project(point: WeatherCoordinate, a: WeatherCoordinate, b: WeatherCoordinate, index: Int): RoadProjection {
    val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(.000001)
    fun delta(lon: Double) = (lon - a.longitude + 540) % 360 - 180
    val dx = delta(b.longitude) * scale
    val dy = b.latitude - a.latitude
    val denominator = dx * dx + dy * dy
    val fraction = (if (denominator > 0) (delta(point.longitude) * scale * dx + (point.latitude - a.latitude) * dy) / denominator else 0.0).coerceIn(0.0, 1.0)
    val projected = WeatherCoordinate(a.latitude + dy * fraction, (a.longitude + delta(b.longitude) * fraction + 540) % 360 - 180)
    return RoadProjection(index, fraction, distance(point, projected), bearing(a, b))
}
