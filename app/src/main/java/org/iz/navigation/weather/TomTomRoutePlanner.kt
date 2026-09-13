package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONArray

/**
 * Routing v1: geometry, guidance and live ETA all come from this one response. No disk cache.
 * https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route
 * https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/guidance-instructions
 * Motorcycle remains explicitly beta under the v1 common-routing-parameters contract.
 */
class TomTomRoutePlanner internal constructor(
    private val apiKey: String,
    endpoint: String,
    client: OkHttpClient,
    private val clock: () -> Long,
    private val gate: TrafficRequestGate,
    private val ensureAuthorized: () -> Unit = {},
) : RoutePlanner {
    private val endpoint = endpoint.toHttpUrl().also {
        require(it.toString() == ENDPOINT || it.host in setOf("localhost", "127.0.0.1", "::1"))
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = singleAttemptHttpClient(client.newBuilder().apply {
        // The personal key is part of the required request URL. Never retain it in HTTP caches,
        // application interceptors, event loggers or redirect destinations.
        cache(null)
        interceptors().clear()
        networkInterceptors().clear()
        eventListener(okhttp3.EventListener.NONE)
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(20, TimeUnit.SECONDS)
        callTimeout(30, TimeUnit.SECONDS)
    }.build())

    constructor(apiKey: String, ensureAuthorized: () -> Unit = {}) : this(apiKey, ENDPOINT, defaultWeatherHttpClient(), System::currentTimeMillis, sharedTrafficRequestGate, ensureAuthorized)

    override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
        travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute =
        request(stops, departureAt, transport, travelSpeedKmh, preferences, false).routes.first()

    override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
        travelSpeedKmh: Double?, preferences: RoutePreferences): RouteAlternatives =
        request(stops, departureAt, transport, travelSpeedKmh, preferences, true)

    private suspend fun request(stops: List<RouteStop>, departureAt: Long, transport: Transport,
        travelSpeedKmh: Double?, preferences: RoutePreferences, alternatives: Boolean): RouteAlternatives = withContext(Dispatchers.IO) {
        require(stops.size in 2..6)
        routeTravelSpeedKmh(transport, travelSpeedKmh)
        val mode = when (transport) {
            Transport.CAR, Transport.PASSENGER -> "car"
            Transport.MOTORCYCLE -> "motorcycle"
            else -> throw IllegalArgumentException("Bu ulaşım türü için Valhalla kullanılır.")
        }
        require(apiKey.isNotBlank() && apiKey.length <= 256)
        try {
            gate.request(clock, ensureAuthorized) {
                val requestDeparture = if (departureAt <= clock()) clock() else departureAt
                val leaveNow = departureAt <= clock()
                val locations = stops.joinToString(":") { "${it.coordinate.latitude},${it.coordinate.longitude}" }
                val url = endpoint.newBuilder().addPathSegment(locations).addPathSegment("json")
                    .addQueryParameter("key", apiKey)
                    .addQueryParameter("traffic", "true")
                    .addQueryParameter("sectionType", "speedLimit")
                    .addQueryParameter("travelMode", mode)
                    .addQueryParameter("routeType", "fastest")
                    .addQueryParameter("routeRepresentation", "polyline")
                    .addQueryParameter("extendedRouteRepresentation", "travelTime")
                    .addQueryParameter("computeTravelTimeFor", "all")
                    .addQueryParameter("computeBestOrder", "false")
                    .addQueryParameter("maxAlternatives", if (alternatives) "2" else "0")
                    .addQueryParameter("instructionsType", "text")
                    .addQueryParameter("language", "tr-TR")
                    .addQueryParameter("departAt", if (leaveNow) "now" else Instant.ofEpochMilli(departureAt).toString())
                    .apply {
                        if (preferences.avoidHighways) addQueryParameter("avoid", "motorways")
                        if (preferences.avoidTolls) addQueryParameter("avoid", "tollRoads")
                        if (preferences.avoidFerries) addQueryParameter("avoid", "ferries")
                    }.build()
                val request = weatherRequest(url.toString()).header("Cache-Control", "no-store").build()
                // A key may have been removed while this request waited for the shared gate.
                // Keep this last authorization check adjacent to the cancellable HTTP enqueue.
                ensureAuthorized()
                val response = executeBounded(client, request, MAX_RESPONSE_BYTES)
                if (response.code == 429 || response.code == 503) {
                    gate.coolDown(clock(), response.retryAfter)
                    throw RouteServiceException("TomTom kullanım sınırı veya geçici bekleme; trafiksiz rota kullanılacak.")
                }
                if (!response.successful) throw RouteServiceException("TomTom trafik servisi kullanılamıyor; trafiksiz rota kullanılacak.")
                coroutineContext.ensureActive()
                if (!alternatives) RouteAlternatives(listOf(parseTomTomRoute(response.body, stops, transport, clock(), requestDeparture).copy(preferences = preferences)))
                else {
                    val values = JSONObject(response.body).getJSONArray("routes")
                    require(values.length() in 1..3)
                    val routes = (0 until values.length()).mapNotNull { index -> runCatching {
                        val single = JSONObject().put("routes", JSONArray().put(values.getJSONObject(index)))
                        parseTomTomRoute(single.toString(), stops, transport, clock(), requestDeparture).copy(preferences = preferences)
                    }.getOrNull() }
                    uniqueAlternatives(routes, if (routes.size < values.length()) "Bazı alternatif yollar doğrulanamadı." else null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: RouteServiceException) {
            throw safe
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            // Do not retain a cause: an HTTP exception may contain the full URL and API key.
            throw RouteServiceException("TomTom trafik yanıtı alınamadı veya geçersiz; trafiksiz rota kullanılacak.")
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.tomtom.com/routing/1/calculateRoute/"
        const val MAX_RESPONSE_BYTES = 2_000_000
    }
}

internal val sharedTrafficRequestGate = TrafficRequestGate()

/** Process-wide pacing and Retry-After; no coordinates, keys or routes are persisted. */
internal class TrafficRequestGate {
    private val mutex = Mutex()
    private var nextRequestAt = 0L
    private var cooldownUntil = 0L

    suspend fun <T> request(clock: () -> Long, ensureAuthorized: () -> Unit = {}, action: suspend () -> T): T = mutex.withLock {
        ensureAuthorized()
        if (clock() < cooldownUntil) throw RouteServiceException("TomTom beklememizi istiyor; trafiksiz rota kullanılacak.")
        val wait = (nextRequestAt - clock()).coerceIn(0L, 1_000L)
        if (wait > 0L) delay(wait)
        coroutineContext.ensureActive()
        ensureAuthorized()
        nextRequestAt = maxOf(clock(), nextRequestAt) + 1_000L
        action()
    }

    fun coolDown(now: Long, value: String?) {
        val numeric = value?.trim()?.toLongOrNull()
        val duration = if (numeric != null) numeric.coerceIn(30L, 86_400L) * 1_000L else retryAfterMillis(value, now)
        cooldownUntil = maxOf(cooldownUntil, now + duration)
    }
}

private data class TrafficShapePoint(val offset: Double, val coordinate: WeatherCoordinate)
private data class TrafficTimeAnchor(val offset: Double, val seconds: Double)
private data class TrafficInstruction(val json: JSONObject, val offset: Double, val routeOffset: Double, val seconds: Double, val coordinate: WeatherCoordinate)
private data class TimedTrafficPoint(val point: TrafficShapePoint, val seconds: Double)

private fun parseTomTomRoute(json: String, stops: List<RouteStop>, transport: Transport, now: Long, requestDeparture: Long): PlannedRoute {
    val routes = JSONObject(json).getJSONArray("routes")
    require(routes.length() in 1..6)
    val route = routes.getJSONObject(0)
    val summary = route.getJSONObject("summary")
    val distance = summary.nonNegative("lengthInMeters")
    val duration = summary.nonNegative("travelTimeInSeconds")
    val delay = summary.nonNegative("trafficDelayInSeconds")
    val noTraffic = if (summary.has("noTrafficTravelTimeInSeconds")) summary.nonNegative("noTrafficTravelTimeInSeconds") else null
    val legs = route.getJSONArray("legs")
    require(legs.length() == stops.size - 1)
    val geometry = mutableListOf<TrafficShapePoint>()
    val stopTimes = mutableListOf(0.0)
    val anchors = mutableListOf(TrafficTimeAnchor(0.0, 0.0))
    var legOffset = 0.0
    var timeOffset = 0.0
    for (legIndex in 0 until legs.length()) {
        val leg = legs.getJSONObject(legIndex)
        val legSummary = leg.getJSONObject("summary")
        val length = legSummary.nonNegative("lengthInMeters")
        val seconds = legSummary.nonNegative("travelTimeInSeconds")
        val points = leg.getJSONArray("points")
        require(points.length() in 2..100_000 && geometry.size + points.length() <= 100_000)
        val coordinates = (0 until points.length()).map { points.getJSONObject(it).coordinate() }
        require(geometry.isEmpty() || WeatherEngine.distanceMeters(geometry.last().coordinate, coordinates.first()) <= 2.0)
        val shapeDistances = DoubleArray(coordinates.size)
        for (i in 1 until coordinates.size) shapeDistances[i] = shapeDistances[i - 1] + WeatherEngine.distanceMeters(coordinates[i - 1], coordinates[i])
        require(length == 0.0 || shapeDistances.last() > 0.0)
        coordinates.forEachIndexed { index, coordinate ->
            val ratio = if (shapeDistances.last() > 0.0) shapeDistances[index] / shapeDistances.last() else index.toDouble() / coordinates.lastIndex
            val point = TrafficShapePoint(legOffset + length * ratio, coordinate)
            if (index != 0 || geometry.lastOrNull() != point) geometry += point
        }
        legOffset += length
        timeOffset += seconds
        stopTimes += timeOffset
        anchors += TrafficTimeAnchor(legOffset, timeOffset)
    }
    require(abs(distance - legOffset) <= legs.length().toDouble())
    require(abs(duration - timeOffset) <= legs.length().toDouble())
    // Summaries are integral seconds/metres; use the route-level final value for the endpoint.
    geometry[geometry.lastIndex] = geometry.last().copy(offset = distance)
    anchors[anchors.lastIndex] = TrafficTimeAnchor(distance, duration)
    stopTimes[stopTimes.lastIndex] = duration
    // extendedRouteRepresentation=travelTime supplies sparse, route-wide point timing.
    // Interpolate omitted points only between these anchors (using the same leg geometry),
    // preserving concentrated congestion even when there are no intervening turn instructions.
    val progress = route.getJSONArray("progress")
    require(progress.length() in 2..100_000)
    var previousProgressIndex = -1
    var previousProgressSeconds = 0.0
    for (index in 0 until progress.length()) {
        val point = progress.getJSONObject(index)
        val shapeIndex = point.getInt("pointIndex")
        val seconds = point.nonNegative("travelTimeInSeconds")
        require(shapeIndex in geometry.indices && shapeIndex > previousProgressIndex)
        require(seconds >= previousProgressSeconds && seconds <= duration)
        if (index == 0) require(shapeIndex == 0 && seconds == 0.0)
        if (index == progress.length() - 1) require(shapeIndex == geometry.lastIndex && seconds == duration)
        anchors += TrafficTimeAnchor(geometry[shapeIndex].offset, seconds)
        previousProgressIndex = shapeIndex
        previousProgressSeconds = seconds
    }
    val instructionsJson = route.getJSONObject("guidance").getJSONArray("instructions")
    require(instructionsJson.length() in 1..10_000)
    val instructions = (0 until instructionsJson.length()).map { i ->
        val value = instructionsJson.getJSONObject(i)
        val routeOffset = value.nonNegative("routeOffsetInMeters")
        val seconds = value.nonNegative("travelTimeInSeconds")
        require(routeOffset <= distance && seconds <= duration)
        val shapeIndex = value.getInt("pointIndex")
        require(shapeIndex in geometry.indices)
        val point = value.getJSONObject("point").coordinate()
        val before = geometry[shapeIndex]
        val offset = if (before.coordinate == point) before.offset else {
            require(shapeIndex < geometry.lastIndex)
            val after = geometry[shapeIndex + 1]
            val toPoint = WeatherEngine.distanceMeters(before.coordinate, point)
            val fromPoint = WeatherEngine.distanceMeters(point, after.coordinate)
            val segment = WeatherEngine.distanceMeters(before.coordinate, after.coordinate)
            require(toPoint + fromPoint <= segment + maxOf(2.0, segment * 0.01))
            val ratio = if (toPoint + fromPoint > 0.0) toPoint / (toPoint + fromPoint) else 0.0
            before.offset + (after.offset - before.offset) * ratio
        }
        TrafficInstruction(value, offset, routeOffset, seconds, point)
    }
    require(instructions.zipWithNext().all { (a, b) -> a.offset <= b.offset && a.routeOffset <= b.routeOffset && a.seconds <= b.seconds })
    anchors += instructions.map { TrafficTimeAnchor(it.offset, it.seconds) }
    val orderedAnchors = anchors.distinct().sortedWith(compareBy<TrafficTimeAnchor> { it.offset }.thenBy { it.seconds })
    require(orderedAnchors.zipWithNext().all { (a, b) -> a.seconds <= b.seconds })

    // The documented pointIndex locates a maneuver on its original segment. The reported road
    // distance can differ from a simplified polyline's length: never let that reorder geometry.
    // Interpolate only between timing anchors from this response, including timed stationary points.
    val shape = (geometry + instructions.map { TrafficShapePoint(it.offset, it.coordinate) })
        .distinct().sortedBy { it.offset }
    require(shape.size <= 110_000)
    val anchorsAtOffset = orderedAnchors.groupBy { it.offset }
    var anchorIndex = 0
    val timedShape = mutableListOf<TimedTrafficPoint>()
    shape.groupBy { it.offset }.forEach { (offset, points) ->
        while (anchorIndex < orderedAnchors.lastIndex && orderedAnchors[anchorIndex + 1].offset <= offset) anchorIndex++
        val from = orderedAnchors[anchorIndex]
        val to = orderedAnchors.getOrElse(anchorIndex + 1) { from }
        val ratio = if (to.offset > from.offset) ((offset - from.offset) / (to.offset - from.offset)).coerceIn(0.0, 1.0) else 0.0
        val times = anchorsAtOffset[offset]?.map { it.seconds } ?: listOf(from.seconds + ratio * (to.seconds - from.seconds))
        require(timedShape.size.toLong() + points.size.toLong() * times.size <= 120_000)
        times.forEach { seconds -> points.forEach { point -> timedShape += TimedTrafficPoint(point, seconds) } }
    }
    val vertices = timedShape.map { RouteVertex(it.point.coordinate, it.seconds) }
    val vertexIndices = timedShape.withIndex().associate { it.value to it.index }
    val instructionIndices = instructions.map { instruction ->
        requireNotNull(vertexIndices[TimedTrafficPoint(TrafficShapePoint(instruction.offset, instruction.coordinate), instruction.seconds)])
    }
    val maneuvers = instructions.mapIndexed { index, instruction ->
        val value = instruction.json
        val message = value.optString("message").take(2_000)
        RouteManeuver(
            type = tomTomManeuverType(value.optString("maneuver")),
            instruction = message,
            verbalInstruction = message,
            streetNames = value.optString("street").take(300).takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList(),
            beginShapeIndex = instructionIndices[index],
            endShapeIndex = instructionIndices.getOrElse(index + 1) { vertices.lastIndex },
            beginElapsedSeconds = instruction.seconds,
            endElapsedSeconds = instructions.getOrNull(index + 1)?.seconds ?: duration,
            roundaboutExit = value.optInt("roundaboutExitNumber").takeIf { it in 1..100 },
        )
    }
    // Section indices refer to the provider's original joined shape, before inserted guidance points.
    // Discard malformed optional metadata without losing otherwise valid navigation geometry.
    val sections = route.optJSONArray("sections")
    val speedLimits = (0 until minOf(sections?.length() ?: 0, 10_000)).mapNotNull { index ->
        runCatching {
            val section = sections!!.getJSONObject(index)
            if (section.optString("sectionType") != "SPEED_LIMIT") return@runCatching null
            val start = section.getInt("startPointIndex")
            val end = section.getInt("endPointIndex")
            val speed = section.getDouble("maxSpeedLimitInKmh")
            if (section.getDouble("startPointIndex") != start.toDouble() || section.getDouble("endPointIndex") != end.toDouble()) return@runCatching null
            if (start !in geometry.indices || end !in geometry.indices || start >= end || !speed.isFinite() || speed <= 0.0) return@runCatching null
            val mappedStart = timedShape.indexOfFirst { it.point == geometry[start] }
            val mappedEnd = timedShape.indexOfFirst { it.point == geometry[end] }
            if (mappedStart < 0 || mappedEnd <= mappedStart) null else RouteSpeedLimitSection(mappedStart, mappedEnd, speed)
        }.getOrNull()
    }
    val effectiveDeparture = runCatching { Instant.parse(summary.getString("departureTime")).toEpochMilli() }
        .getOrNull()?.takeIf { it >= 0L } ?: requestDeparture
    return PlannedRoute(UUID.randomUUID().toString(), stops, vertices, distance, duration, now, stopTimes, transport,
        maneuvers = maneuvers, provider = RouteProvider.TOMTOM,
        traffic = RouteTrafficInfo(now, delay, noTraffic, experimental = transport == Transport.MOTORCYCLE),
        speedLimits = speedLimits, effectiveDepartureAt = effectiveDeparture)
}

private fun JSONObject.coordinate() = WeatherCoordinate(getDouble("latitude"), getDouble("longitude"))
private fun JSONObject.nonNegative(name: String) = getDouble(name).also { require(it.isFinite() && it >= 0.0) }

/** Map documented TomTom v1 instruction codes onto the app's shared maneuver vocabulary. */
private fun tomTomManeuverType(code: String): Int = when (code) {
    "DEPART" -> 1
    "ARRIVE" -> 4
    "ARRIVE_RIGHT" -> 5
    "ARRIVE_LEFT" -> 6
    "STRAIGHT", "FOLLOW" -> 8
    "BEAR_RIGHT" -> 9
    "TURN_RIGHT" -> 10
    "SHARP_RIGHT" -> 11
    "MAKE_UTURN", "TRY_MAKE_UTURN" -> 13
    "SHARP_LEFT" -> 14
    "TURN_LEFT" -> 15
    "BEAR_LEFT" -> 16
    "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" -> 17
    "MOTORWAY_EXIT_RIGHT" -> 20
    "MOTORWAY_EXIT_LEFT" -> 21
    "KEEP_RIGHT" -> 23
    "KEEP_LEFT" -> 24
    "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> 26
    "TAKE_FERRY" -> 28
    else -> 0
}
