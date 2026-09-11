package com.atay.iz.car

import com.atay.iz.data.TrackPoint
import kotlin.math.*

/** One active identity only; honor recorder gap boundaries instead of joining across lost GPS. */
internal fun activeTrailSegments(journeyId: String?, points: List<TrackPoint>): List<List<TrackPoint>> {
    if (journeyId == null) return emptyList()
    val segments = mutableListOf<MutableList<TrackPoint>>()
    points.asSequence().filter { it.journeyId == journeyId && it.latitude.isFinite() &&
        it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        .sortedBy { it.recordedAt }.forEach { point ->
            if (segments.isEmpty() || point.breakBefore) segments.add(mutableListOf())
            segments.last().add(point)
        }
    return segments
}

internal fun trailDistanceMeters(segments: List<List<TrackPoint>>): Double = segments.sumOf { segment ->
    segment.zipWithNext().sumOf { (a, b) ->
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
