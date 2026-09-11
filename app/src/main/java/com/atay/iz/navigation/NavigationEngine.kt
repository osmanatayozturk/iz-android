package com.atay.iz.navigation

import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.WeatherCoordinate
import com.atay.iz.weather.WeatherEngine
import kotlin.math.*

/** In-memory ordered matching. No network, diary, clocks or platform location dependencies. */
class NavigationEngine(private val route: PlannedRoute) {
    private val vertices = route.vertices
    private val distances = DoubleArray(vertices.size).also { values ->
        for (i in 1 until vertices.size) values[i] = values[i - 1] +
            WeatherEngine.distanceMeters(vertices[i - 1].coordinate, vertices[i].coordinate)
    }
    private var travelled = 0.0
    private var elapsed = 0.0
    private var lastTimestamp: Long? = null
    private var lastMatchedTimestamp: Long? = null
    private var offRouteSince: Long? = null
    private var offRouteCount = 0
    private var arrivalCount = 0
    private val stopDistances = run {
        var minimumIndex = 0
        route.stops.mapIndexed { i, stop ->
            when (i) {
                0 -> 0.0
                route.stops.lastIndex -> distances.last()
                else -> {
                    val stopTime = route.stopElapsedSeconds.getOrNull(i)
                    val timedCandidates = (minimumIndex until vertices.size).filter {
                        stopTime != null && abs(vertices[it].elapsedSeconds - stopTime) <= .001
                    }
                    val candidates = timedCandidates.takeIf { it.isNotEmpty() } ?: (minimumIndex until vertices.size).toList()
                    minimumIndex = candidates.minByOrNull {
                        WeatherEngine.distanceMeters(stop.coordinate, vertices[it].coordinate)
                    } ?: minimumIndex
                    distances[minimumIndex]
                }
            }
        }
    }

    fun remainingStops(): List<RouteStop> = route.stops.drop(1).filterIndexed { index, _ ->
        index == route.stops.size - 2 || stopDistances[index + 1] > travelled + 5.0
    }

    fun update(fix: NavigationFix, now: Long): NavigationProgress? {
        if (fix.recordedAt > now || now - fix.recordedAt > 10_000 ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f ||
            (lastTimestamp != null && fix.recordedAt <= lastTimestamp!!)) return null
        lastTimestamp = fix.recordedAt
        val accuracy = fix.accuracyMeters.toDouble().coerceAtLeast(1.0)
        val threshold = max(40.0, 2 * accuracy)
        val seconds = lastMatchedTimestamp?.let { ((fix.recordedAt - it) / 1000.0).coerceIn(0.0, 120.0) }
        val speed = fix.speedMps?.takeIf { it.isFinite() && it in 0f..100f }?.toDouble() ?: 15.0
        val forwardWindow = if (seconds == null) Double.POSITIVE_INFINITY else
            max(80.0, seconds * max(speed, 5.0) * 2 + accuracy * 2).coerceAtMost(2000.0)
        val lower = (travelled - 50.0).coerceAtLeast(0.0)
        val upper = travelled + forwardWindow
        val candidates = mutableListOf<Projection>()
        for (i in 0 until vertices.lastIndex) {
            if (distances[i + 1] < lower || distances[i] > upper) continue
            val candidate = project(i, fix.coordinate, lower, upper)
            val bearing = fix.bearingDegrees?.takeIf { it.isFinite() && it in 0f..360f }
            if (bearing != null && speed >= 3 && distances[i + 1] - distances[i] > 3) {
                val delta = abs((candidate.bearing - bearing + 540.0) % 360.0 - 180.0)
                if (delta > 100.0) continue
            }
            candidates += candidate
        }
        if (vertices.size == 1) candidates += Projection(0.0, 0.0,
            WeatherEngine.distanceMeters(fix.coordinate, vertices.single().coordinate), 0.0)
        val closest = candidates.minOfOrNull { it.distance } ?: (threshold + 1)
        // Prefer the first indistinguishable section at loops and adjacent parallel roads.
        val chosen = candidates.filter { it.distance <= closest + accuracy }.minByOrNull { it.travelled }
        val onRoute = chosen != null && chosen.distance <= threshold
        if (onRoute) {
            offRouteSince = null
            offRouteCount = 0
            if (chosen!!.travelled >= travelled) {
                travelled = chosen.travelled
                elapsed = max(elapsed, chosen.elapsed).coerceAtMost(route.durationSeconds)
            }
            lastMatchedTimestamp = fix.recordedAt
        } else {
            if (offRouteSince == null) offRouteSince = fix.recordedAt
            offRouteCount++
        }
        val offRoute = !onRoute && offRouteCount >= 3 && fix.recordedAt - (offRouteSince ?: fix.recordedAt) >= 6000
        val nearEnd = onRoute && distances.last() - travelled <= 40.0 &&
            travelled >= (stopDistances.getOrNull(stopDistances.lastIndex - 1) ?: 0.0) &&
            WeatherEngine.distanceMeters(fix.coordinate, route.stops.last().coordinate) <= 30.0
        arrivalCount = if (nearEnd) arrivalCount + 1 else 0
        val maneuverIndex = route.maneuvers.indexOfFirst {
            distances.getOrElse(it.beginShapeIndex) { Double.POSITIVE_INFINITY } >= travelled - 12.0 && it.type !in 1..3
        }
        val maneuverDistance = route.maneuvers.getOrNull(maneuverIndex)?.let {
            (distances.getOrElse(it.beginShapeIndex) { travelled } - travelled).coerceAtLeast(0.0)
        } ?: 0.0
        val remainingFraction = if (distances.last() > 0) (1 - travelled / distances.last()).coerceIn(0.0, 1.0) else 0.0
        return NavigationProgress(elapsed, (route.durationSeconds - elapsed).coerceAtLeast(0.0),
            route.distanceMeters * remainingFraction, chosen?.distance ?: closest, maneuverIndex, maneuverDistance,
            arrived = arrivalCount >= 3, offRoute = offRoute)
    }

    private data class Projection(val travelled: Double, val elapsed: Double, val distance: Double, val bearing: Double)

    private fun project(index: Int, point: WeatherCoordinate, lower: Double, upper: Double): Projection {
        val a = vertices[index].coordinate
        val b = vertices[index + 1].coordinate
        val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(.000001)
        fun lonDelta(lon: Double) = ((lon - a.longitude + 540) % 360) - 180
        val dx = lonDelta(b.longitude) * scale
        val dy = b.latitude - a.latitude
        val length = distances[index + 1] - distances[index]
        val minimum = if (length > 0) ((lower - distances[index]) / length).coerceIn(0.0, 1.0) else 0.0
        val maximum = if (length > 0) ((upper - distances[index]) / length).coerceIn(minimum, 1.0) else 1.0
        val denominator = dx * dx + dy * dy
        val fraction = (if (denominator > 0) (lonDelta(point.longitude) * scale * dx + (point.latitude - a.latitude) * dy) / denominator else 0.0)
            .coerceIn(minimum, maximum)
        val projected = WeatherCoordinate(a.latitude + dy * fraction, ((a.longitude + lonDelta(b.longitude) * fraction + 540) % 360) - 180)
        return Projection(distances[index] + length * fraction,
            vertices[index].elapsedSeconds + (vertices[index + 1].elapsedSeconds - vertices[index].elapsedSeconds) * fraction,
            WeatherEngine.distanceMeters(point, projected), (Math.toDegrees(atan2(dx, dy)) + 360) % 360)
    }
}

/** Two bounded cues per maneuver; TomTom generic instructions retain provider text. */
class NavigationCuePolicy {
    private var routeId: String? = null
    private val spoken = mutableSetOf<Pair<Int, Boolean>>()
    fun reset() { routeId = null; spoken.clear() }

    fun cue(route: PlannedRoute, progress: NavigationProgress, fix: NavigationFix): String? {
        if (routeId != route.id) { reset(); routeId = route.id }
        if (progress.offRoute || progress.arrived || !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f ||
            progress.distanceFromRouteMeters > max(40.0, 2.0 * fix.accuracyMeters)) return null
        val maneuver = route.maneuvers.getOrNull(progress.maneuverIndex) ?: return null
        val instruction = when (maneuver.type) {
            0 -> if (route.provider == com.atay.iz.weather.RouteProvider.TOMTOM)
                maneuver.verbalInstruction.replace(Regex("[\\p{Cntrl}\\s]+"), " ").take(240).trim().trimEnd('.', '!', '?')
                    .takeIf { it.isNotBlank() } ?: return null
                else return null
            4, 5, 6 -> "Hedefinize ulaşıyorsunuz"
            7, 8 -> "Düz devam edin"
            9 -> "Hafif sağa dönün"
            10 -> "sağa dönün"
            11 -> "Keskin sağa dönün"
            12, 13 -> "U dönüşü yapın"
            14 -> "Keskin sola dönün"
            15 -> "Sola dönün"
            16 -> "Hafif sola dönün"
            17 -> "Bağlantı yolunda düz devam edin"
            18, 20 -> "Sağdaki bağlantı yoluna girin"
            19, 21 -> "Soldaki bağlantı yoluna girin"
            22 -> "Düz devam edin"
            23 -> "Sağda kalın"
            24 -> "Solda kalın"
            25 -> "Trafiğe katılın"
            26 -> maneuver.roundaboutExit?.let { "Dönel kavşakta $it. çıkışa girin" } ?: "Dönel kavşağa girin"
            27 -> "Dönel kavşaktan çıkın"
            28 -> "Feribota binin"
            29 -> "Feribottan çıkın"
            37 -> "Sağdan trafiğe katılın"
            38 -> "Soldan trafiğe katılın"
            else -> return null
        }
        val speed = fix.speedMps?.takeIf { it.isFinite() && it >= 0 }?.toDouble() ?: 0.0
        val distance = progress.nextManeuverDistanceMeters
        if (!distance.isFinite() || distance < 0 || distance > max(180.0, speed * 15).coerceAtMost(800.0)) return null
        val now = distance <= max(20.0, speed * 2).coerceAtMost(60.0)
        val key = progress.maneuverIndex to now
        if (!spoken.add(key)) return null
        if (now) spoken.add(progress.maneuverIndex to false)
        return if (now) "Şimdi $instruction." else "${(round(distance / 50) * 50).toInt().coerceAtLeast(50)} metre sonra $instruction."
    }
}
