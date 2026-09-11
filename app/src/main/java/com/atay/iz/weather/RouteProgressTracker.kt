package com.atay.iz.weather

import kotlin.math.*

internal data class RouteProgress(
    val elapsedSeconds: Double,
    val remainingSeconds: Double,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double,
)

/** Projects onto the untravelled polyline, preserving order at loops and repeated crossings. */
internal class RouteProgressTracker(private val route: PlannedRoute) {
    private val vertices = route.vertices
    private val distances = DoubleArray(vertices.size).also { result ->
        for (i in 1 until vertices.size) result[i] = result[i - 1] +
            WeatherEngine.distanceMeters(vertices[i - 1].coordinate, vertices[i].coordinate)
    }
    private var segment = 0
    private var fraction = 0.0

    fun project(point: WeatherCoordinate, accuracyMeters: Float = 10f): RouteProgress {
        if (vertices.size < 2) return RouteProgress(0.0, route.durationSeconds, route.distanceMeters, Double.POSITIVE_INFINITY)
        data class Projection(val index: Int, val fraction: Double, val distance: Double)
        val candidates = ArrayList<Projection>(vertices.size - segment)
        for (i in segment until vertices.lastIndex) {
            val a = vertices[i].coordinate
            val b = vertices[i + 1].coordinate
            val latitudeScale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(0.000001)
            fun longitudeDelta(lon: Double) = ((lon - a.longitude + 540.0) % 360.0) - 180.0
            val dx = longitudeDelta(b.longitude) * latitudeScale
            val dy = b.latitude - a.latitude
            val px = longitudeDelta(point.longitude) * latitudeScale
            val py = point.latitude - a.latitude
            val denominator = dx * dx + dy * dy
            val lower = if (i == segment) fraction else 0.0
            val ratio = (if (denominator > 0) (px * dx + py * dy) / denominator else 0.0).coerceIn(lower, 1.0)
            val interpolatedLon = ((a.longitude + longitudeDelta(b.longitude) * ratio + 540.0) % 360.0) - 180.0
            val projected = WeatherCoordinate(a.latitude + dy * ratio, interpolatedLon)
            val distance = WeatherEngine.distanceMeters(point, projected)
            candidates += Projection(i, ratio, distance)
        }
        val bestDistance = candidates.minOf { it.distance }
        // GPS cannot distinguish nearby parallel branches within its uncertainty.
        // Preserve the earliest plausible forward position instead of skipping a loop/via.
        val uncertainty = if (accuracyMeters.isFinite()) accuracyMeters.coerceIn(0f,50f).toDouble().coerceAtLeast(0.5) else 0.5
        val chosen = candidates.first { it.distance <= bestDistance + uncertainty }
        // A far-away fix is evidence for rerouting, never evidence of progress.
        if (chosen.distance <= 250.0) { segment = chosen.index; fraction = chosen.fraction }
        val start = vertices[segment].elapsedSeconds
        val end = vertices[segment + 1].elapsedSeconds
        val elapsed = (start + (end - start) * fraction).coerceIn(0.0, route.durationSeconds)
        val travelled = distances[segment] + (distances[segment + 1] - distances[segment]) * fraction
        val remainingFraction = if (distances.last() > 0) 1.0 - travelled / distances.last() else 0.0
        return RouteProgress(elapsed, (route.durationSeconds - elapsed).coerceAtLeast(0.0),
            (route.distanceMeters * remainingFraction).coerceAtLeast(0.0), bestDistance)
    }

    fun remainingStops(elapsedSeconds: Double): List<RouteStop> {
        val times = route.stopElapsedSeconds.takeIf { it.size == route.stops.size && it.zipWithNext().all { p -> p.first <= p.second } }
            ?: fallbackStopTimes()
        return route.stops.drop(1).filterIndexed { index, _ ->
            index == route.stops.size - 2 || times[index + 1] > elapsedSeconds + 1.0
        }
    }

    private fun fallbackStopTimes(): List<Double> {
        var minimumIndex = 0
        return route.stops.mapIndexed { index, stop ->
            when (index) {
                0 -> 0.0
                route.stops.lastIndex -> route.durationSeconds
                else -> {
                    val closest = (minimumIndex until vertices.size).minByOrNull {
                        WeatherEngine.distanceMeters(stop.coordinate, vertices[it].coordinate)
                    } ?: minimumIndex
                    minimumIndex = closest
                    vertices[closest].elapsedSeconds
                }
            }
        }
    }
}
