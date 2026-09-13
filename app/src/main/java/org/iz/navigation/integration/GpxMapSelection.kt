package org.iz.navigation.integration

import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackFollowSelection
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import kotlin.math.abs
import kotlin.math.cos

internal data class GpxStartCandidate(val selection: TrackFollowSelection, val coordinate: WeatherCoordinate,
    val alongMeters: Double, val distanceMeters: Double)

internal fun gpxStartCandidates(track: ImportedTrack, selection: TrackFollowSelection,
    point: WeatherCoordinate): List<GpxStartCandidate> {
    val segment = track.segments.getOrNull(selection.segmentIndex) ?: return emptyList()
    val points = if (selection.reversed) segment.points.asReversed() else segment.points
    val candidates = ArrayList<GpxStartCandidate>()
    var along = 0.0
    for (index in 0 until points.lastIndex) {
        val a = points[index]; val b = points[index + 1]
        val length = WeatherEngine.distanceMeters(a, b)
        if (length <= .001) continue
        val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(.000001)
        fun lonDelta(longitude: Double) = ((longitude - a.longitude + 540) % 360) - 180
        val dx = lonDelta(b.longitude) * scale
        val dy = b.latitude - a.latitude
        val denominator = dx * dx + dy * dy
        val fraction = ((lonDelta(point.longitude) * scale * dx + (point.latitude - a.latitude) * dy) / denominator)
            .coerceIn(0.0, 1.0)
        val projected = WeatherCoordinate(a.latitude + dy * fraction,
            ((a.longitude + lonDelta(b.longitude) * fraction + 540) % 360) - 180)
        candidates += GpxStartCandidate(selection.copy(startPointIndex = index, startFraction = fraction),
            projected, along + length * fraction, WeatherEngine.distanceMeters(point, projected))
        along += length
    }
    val closest = candidates.minOfOrNull { it.distanceMeters } ?: return emptyList()
    // Adjacent edges describe one place; distant passes through that place require a choice.
    val nearby = candidates.filter { it.distanceMeters <= closest + 15.0 && along - it.alongMeters > .01 }
        .sortedWith(compareBy<GpxStartCandidate> { it.distanceMeters }.thenBy { it.alongMeters })
    val distinct = mutableListOf<GpxStartCandidate>()
    for (candidate in nearby) {
        if (distinct.none { abs(it.alongMeters - candidate.alongMeters) <= 50.0 }) distinct += candidate
    }
    return distinct.sortedBy { it.alongMeters }
}

/** GeoJSON lines crossing the date line need separate pieces at +/-180 degrees. */
internal fun gpxDisplayLines(points: List<WeatherCoordinate>): List<List<WeatherCoordinate>> {
    if (points.size < 2) return emptyList()
    val lines = mutableListOf<List<WeatherCoordinate>>()
    var current = mutableListOf(points.first())
    for (index in 1 until points.size) {
        val a = points[index - 1]; val b = points[index]
        val rawDelta = b.longitude - a.longitude
        if (abs(rawDelta) > 180.0) {
            val delta = ((rawDelta + 540) % 360) - 180
            if (abs(delta) <= .000000001) {
                current += WeatherCoordinate(b.latitude, a.longitude)
                if (current.any { it != current.first() }) lines += current
                current = mutableListOf(b)
                continue
            } else {
                val boundary = if (delta > 0) 180.0 else -180.0
                val fraction = ((boundary - a.longitude) / delta).coerceIn(0.0, 1.0)
                val latitude = a.latitude + (b.latitude - a.latitude) * fraction
                current += WeatherCoordinate(latitude, boundary)
                if (current.any { it != current.first() }) lines += current
                current = mutableListOf(WeatherCoordinate(latitude, -boundary))
            }
        }
        current += b
    }
    if (current.size >= 2 && current.any { it != current.first() }) lines += current
    return lines
}
