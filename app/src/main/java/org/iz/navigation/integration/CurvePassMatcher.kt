package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.lean.LeanJourneyReport
import org.iz.navigation.lean.LeanMount
import org.iz.navigation.speed.angularDifference
import org.iz.navigation.speed.bearing
import org.iz.navigation.speed.project
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import kotlin.math.abs
import kotlin.math.ceil

internal data class CurvePassLean(val mount: LeanMount, val leftDegrees: Double?, val rightDegrees: Double?)
internal data class CurvePassSummary(
    val durationMillis: Long, val sampledMinSpeedKmh: Double?, val sampledMaxSpeedKmh: Double?, val lean: CurvePassLean?,
)
internal data class CurvePassSample(
    val distanceMeters: Double,
    val selectedCoordinate: WeatherCoordinate, val selectedSpeedKmh: Double?,
    val previousCoordinate: WeatherCoordinate, val previousSpeedKmh: Double?,
)
internal data class CurvePassComparison(
    val selected: CurvePassSummary, val previous: CurvePassSummary, val samples: List<CurvePassSample>,
)

/** Deliberately rejects traces that cannot be distinguished at the recorded GPS precision. */
internal object CurvePassMatcher {
    private const val MAX_GPS_ACCURACY = 12f
    private const val MAX_OFFSET_METERS = 8.0
    private const val MAX_MEAN_OFFSET_METERS = 4.0
    private const val MAX_HEADING_DIFFERENCE = 35.0

    data class Trace(val curve: RecordedCurveDetail, val points: List<WeatherCoordinate>,
        val meters: List<Double>, val speeds: List<Double?>)
    private data class Reading(val coordinate: WeatherCoordinate, val speedKmh: Double?)

    fun trace(curve: RecordedCurveDetail, points: List<TrackPoint>): Trace? {
        val section = points.filter { it.journeyId == curve.journeyId && it.recordedAt in curve.start.at..curve.end.at }
            .sortedBy { it.recordedAt }
        val first = section.firstOrNull() ?: return null
        val last = section.last()
        if (section.size != curve.pointCount || section.size !in 4..512 ||
            first.id != curve.start.pointId || last.id != curve.end.pointId ||
            first.recordedAt != curve.start.at || last.recordedAt != curve.end.at ||
            first.latitude != curve.start.latitude || first.longitude != curve.start.longitude ||
            last.latitude != curve.end.latitude || last.longitude != curve.end.longitude) return null
        if (section.any { !DiaryRules.isUsablePoint(it) || it.accuracy > MAX_GPS_ACCURACY } ||
            section.zipWithNext().any { (a, b) -> b.recordedAt - a.recordedAt !in 1..15_000 || !DiaryRules.connects(a, b) }) return null
        val coordinates = section.map { WeatherCoordinate(it.latitude, it.longitude) }
        val lengths = section.zipWithNext { a, b -> DiaryRules.metersBetween(a, b) }
        if (lengths.any { !it.isFinite() || it < 1 }) return null
        val meters = lengths.runningFold(0.0, Double::plus)
        if (abs(meters.last() - curve.recordedDistanceMeters) > .01) return null
        return Trace(curve, coordinates, meters, section.map {
            it.speed?.takeIf { speed -> speed.isFinite() && speed in 0f..100f }?.toDouble()?.times(3.6)
        })
    }

    fun compare(selected: Trace, previous: Trace, selectedLean: LeanJourneyReport? = null,
        previousLean: LeanJourneyReport? = null): CurvePassComparison? {
        val aligned = align(selected, previous) ?: return null
        val steps = ceil(selected.meters.last() / 5).toInt().coerceIn(4, 120)
        val samples = (0..steps).map { step ->
            val distance = selected.meters.last() * step / steps
            val current = selected.read(distance, selected.meters)
            val earlier = previous.read(distance, aligned)
            CurvePassSample(distance, current.coordinate, current.speedKmh, earlier.coordinate, earlier.speedKmh)
        }
        return CurvePassComparison(selected.summary(selectedLean), previous.summary(previousLean), samples)
    }

    fun matches(selected: Trace, previous: Trace): Boolean = align(selected, previous) != null

    private fun align(selected: Trace, previous: Trace): List<Double>? {
        if (selected.curve.direction != previous.curve.direction || previous.curve.end.at >= selected.curve.start.at) return null
        if (distance(selected.points.first(), previous.points.first()) > MAX_OFFSET_METERS ||
            distance(selected.points.last(), previous.points.last()) > MAX_OFFSET_METERS ||
            abs(previous.meters.last() - selected.meters.last()) > maxOf(8.0, selected.meters.last() * .15)) return null

        val reference = selected.points.zipWithNext()
        val projected = previous.points.map { point ->
            val nearby = reference.mapIndexed { index, (a, b) -> project(point, a, b, index) }
                .filter { it.distance <= MAX_OFFSET_METERS }
                .map { hit ->
                    val at = selected.meters[hit.index] +
                        (selected.meters[hit.index + 1] - selected.meters[hit.index]) * hit.fraction
                    at to hit.distance
                }
            val closest = nearby.minByOrNull { it.second } ?: return null
            // Near-identical projections onto distant portions are ambiguous at a crossing or hairpin.
            if (nearby.any { abs(it.first - closest.first) > 12 && it.second - closest.second <= 2 }) return null
            closest.first
        }.toMutableList()
        if (projected.zipWithNext().any { (a, b) -> b <= a } ||
            projected.first() > MAX_OFFSET_METERS || selected.meters.last() - projected.last() > MAX_OFFSET_METERS) return null
        projected[0] = 0.0
        projected[projected.lastIndex] = selected.meters.last()

        // Check inside each bend even when GPS sampling has a different spacing in the two rides.
        // Include both traces' knots and their interval midpoints to reject a different branch.
        val knots = (selected.meters + projected).distinct().sorted()
        var offsetSum = 0.0
        for ((a, b) in knots.zipWithNext()) {
            val current = selected.read((a + b) / 2, selected.meters).coordinate
            val earlier = previous.read((a + b) / 2, projected).coordinate
            val offset = distance(current, earlier)
            if (offset > MAX_OFFSET_METERS) return null
            offsetSum += offset * (b - a)
        }
        if (offsetSum / selected.meters.last() > MAX_MEAN_OFFSET_METERS) return null
        for ((index, interval) in projected.zipWithNext().withIndex()) {
            val (start, end) = interval
            val a = selected.read(start, selected.meters).coordinate
            val b = selected.read(end, selected.meters).coordinate
            val previousHeading = bearing(previous.points[index], previous.points[index + 1])
            if (angularDifference(bearing(a, b), previousHeading) > MAX_HEADING_DIFFERENCE) return null
        }
        return projected
    }

    private fun Trace.summary(report: LeanJourneyReport?): CurvePassSummary =
        CurvePassSummary(curve.end.at - curve.start.at, speeds.filterNotNull().minOrNull(),
            speeds.filterNotNull().maxOrNull(), lean(report))

    private fun Trace.lean(report: LeanJourneyReport?): CurvePassLean? {
        val groups = listOf(LeanMount.BODY to report, LeanMount.HANDLEBAR to report?.handlebar)
        return groups.flatMap { (mount, source) ->
            source?.takeUnless { it.pending || it.algorithmVersions.isEmpty() }?.curves.orEmpty().mapNotNull { result ->
                if (result.timingEstimated) return@mapNotNull null
                val start = result.startUtcMillis ?: return@mapNotNull null
                val end = result.endUtcMillis ?: return@mapNotNull null
                val lat = result.latitude ?: return@mapNotNull null
                val lon = result.longitude ?: return@mapNotNull null
                val overlap = minOf(end, curve.end.at) - maxOf(start, curve.start.at)
                if (start < 0 || end <= start || overlap <= 0 || overlap < (end - start) * .8 ||
                    overlap < (curve.end.at - curve.start.at) * .5 ||
                    !lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@mapNotNull null
                val anchor = WeatherCoordinate(lat, lon)
                if (points.zipWithNext().none { (a, b) -> project(anchor, a, b, 0).distance <= MAX_GPS_ACCURACY })
                    return@mapNotNull null
                val left = result.leftDegrees?.takeIf { it.isFinite() && it in 0.0..90.0 }
                val right = result.rightDegrees?.takeIf { it.isFinite() && it in 0.0..90.0 }
                if (left == null && right == null) null else CurvePassLean(mount, left, right)
            }
        }.singleOrNull()
    }

    private fun Trace.read(distance: Double, positions: List<Double>): Reading {
        val index = positions.binarySearch(distance)
        if (index >= 0) return Reading(points[index], speeds[index])
        val upper = (-index - 1).coerceIn(1, points.lastIndex)
        val lower = upper - 1
        val fraction = ((distance - positions[lower]) / (positions[upper] - positions[lower])).coerceIn(0.0, 1.0)
        val a = points[lower]
        val b = points[upper]
        val lonDelta = (b.longitude - a.longitude + 540) % 360 - 180
        val longitude = (a.longitude + lonDelta * fraction + 540) % 360 - 180
        val speed = speeds[lower]?.let { left -> speeds[upper]?.let { right -> left + (right - left) * fraction } }
        return Reading(WeatherCoordinate(a.latitude + (b.latitude - a.latitude) * fraction, longitude), speed)
    }

    private fun distance(a: WeatherCoordinate, b: WeatherCoordinate) = WeatherEngine.distanceMeters(a, b)
}
