package org.iz.navigation.navigation

import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
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

    fun remainingStopIndices(): List<Int> = (1..route.stops.lastIndex).filter { index ->
        index == route.stops.lastIndex || stopDistances[index] > travelled + 5.0
    }

    fun remainingStops(): List<RouteStop> = remainingStopIndices().map { route.stops[it] }

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
class NavigationCuePolicy(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var routeId: String? = null
    private val spoken = mutableSetOf<Pair<Int, Boolean>>()
    private val pending = mutableMapOf<Pair<Int, Boolean>, NavigationCueRequest>()
    private data class Budget(val firstAt: Long, var lastAt: Long, var attempts: Int)
    private val budgets = mutableMapOf<Pair<Int, Boolean>, Budget>()
    private data class Candidate(val key: Pair<Int, Boolean>, val text: String)
    fun reset() { routeId = null; spoken.clear(); pending.clear(); budgets.clear() }
    private fun useRoute(route: PlannedRoute) {
        if (routeId != route.id) { reset(); routeId = route.id }
    }

    /** Legacy immediate-consumption API; tracked production guidance uses offer + receipts. */
    fun cue(route: PlannedRoute, progress: NavigationProgress, fix: NavigationFix): String? {
        useRoute(route)
        val candidate = candidate(route, progress, fix) ?: return null
        if (blocked(candidate.key)) return null
        consume(candidate.key)
        return candidate.text
    }

    fun offer(route: PlannedRoute, progress: NavigationProgress, fix: NavigationFix,
        stillCurrent: (NavigationCueRequest) -> Boolean = { true }): NavigationCueRequest? {
        useRoute(route)
        val candidate = candidate(route, progress, fix) ?: return null
        val key = candidate.key
        if (blocked(key)) return null
        val now = clock()
        val budget = budgets[key]
        if (budget != null && (now < budget.lastAt || now - budget.firstAt >= 15_000L ||
                budget.attempts >= 3 || now - budget.lastAt < 1_000L)) return null
        val admitted = budget ?: Budget(now, now, 0).also { budgets[key] = it }
        admitted.lastAt = now
        admitted.attempts++
        lateinit var request: NavigationCueRequest
        request = NavigationCueRequest(candidate.text, admitted.firstAt + 15_000L, stillCurrent) { result ->
            if (pending[key] === request) {
                pending.remove(key)
                if (result != CueDelivery.REJECTED) consume(key)
            }
        }
        pending[key] = request
        return request
    }

    /** Recompute phase from the current fix; never compare localized cue text or reset history. */
    fun isEligible(request: NavigationCueRequest, route: PlannedRoute, progress: NavigationProgress,
        fix: NavigationFix): Boolean {
        if (routeId != route.id || clock() >= request.expiresAtMillis) return false
        val key = candidate(route, progress, fix)?.key ?: return false
        return pending[key] === request
    }
    private fun blocked(key: Pair<Int, Boolean>) = key in spoken || key in pending ||
        (!key.second && (key.first to true) in pending)
    private fun consume(key: Pair<Int, Boolean>) {
        spoken.add(key)
        if (key.second) spoken.add(key.first to false)
    }
    private fun candidate(route: PlannedRoute, progress: NavigationProgress, fix: NavigationFix): Candidate? {
        if (progress.offRoute || progress.arrived || !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f ||
            progress.distanceFromRouteMeters > max(40.0, 2.0 * fix.accuracyMeters)) return null
        val maneuver = route.maneuvers.getOrNull(progress.maneuverIndex) ?: return null
        val instruction = when (maneuver.type) {
            0 -> if (route.provider == org.iz.navigation.weather.RouteProvider.TOMTOM)
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
        return Candidate(key, if (now) "Şimdi $instruction." else "${(round(distance / 50) * 50).toInt().coerceAtLeast(50)} metre sonra $instruction.")
    }
}
