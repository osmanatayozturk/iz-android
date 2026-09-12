package org.iz.navigation.speed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.*
import kotlin.math.abs

/** The coordinator's existing fix/tick calls observe. Only refresh performs bounded IO. */
internal class RoadSpeedMonitor(
    private val roads: suspend (WeatherCoordinate) -> RoadRegion,
    private val credentials: () -> TrafficCredentials,
    private val reverse: TomTomReverseSpeedClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val refreshLock = Mutex()
    private val matcher = LocalRoadMatcher()
    private val routeMatcher = LocalRoadMatcher()
    private var region: RoadRegion? = null
    private var latest = NavigationState()
    private var session: String? = null
    private var generation = 0L
    private var match: RoadMatch? = null
    private var routeId: String? = null
    private var routeRoad: OsmRoad? = null
    private var revision: String? = null
    private var fallbackEnabled = false
    private var posted: RoadSpeedLimit? = null
    private var nextOsmAttempt = 0L

    fun invalidateCredentials() = synchronized(lock) {
        generation++; revision = null; fallbackEnabled = false; posted = null
        matcher.reset(); routeMatcher.reset(); match = null
    }

    fun observe(state: NavigationState, now: Long): RoadSpeedState = synchronized(lock) {
        latest = state
        val identity = state.sessionId?.let { "$it:${state.sessionTransport}" }
        if (identity != session) {
            session = identity; generation++; posted = null; match = null
            matcher.reset(); routeMatcher.reset(); nextOsmAttempt = 0
        }
        val visible = identity != null && speedMode(state.sessionTransport)
        if (!visible) return@synchronized RoadSpeedState()
        val fix = state.fix
        if (state.gpsStale || fix == null || !speedFixFresh(fix, now)) {
            matcher.reset(); routeMatcher.reset(); match = null; posted = null
            return@synchronized RoadSpeedState(visible = true)
        }
        val own = ownSpeedKmh(fix, now)
        if (state.simulation) return@synchronized RoadSpeedState(ownKmh = own, visible = true)
        val network = region?.takeIf { it.contains(fix.coordinate, now) }
        match = if (network == null) { matcher.reset(); null } else matcher.match(fix, network.roads)
        posted = posted?.takeIf { !state.guidance && it.roadIdentity == match?.identity && it.transport == state.sessionTransport &&
            now - it.observedAt in 0..10_000 && distance(it.coordinate, fix.coordinate) <= 100 }
        val osm = match?.let { osmSpeedValue(it.road.tags, state.sessionTransport!!, it.forward) }
        val routeLimit = if (fallbackEnabled) routeLimit(state, now) else null
        val limit = when {
            osm == OsmSpeedValue.Unknown -> null
            osm is OsmSpeedValue.Known -> {
                if (routeLimit != null && (osm.kmh == null || abs(osm.kmh - routeLimit.valueKmh!!) > 1)) null
                else RoadSpeedLimit(osm.kmh, RoadSpeedSource.OSM, osm.genericPosted, fix.recordedAt,
                    fix.coordinate, state.sessionTransport!!, match!!.identity)
            }
            osm == OsmSpeedValue.Missing -> routeLimit ?: posted.takeIf { fallbackEnabled }
            network == null || network.roads.isEmpty() -> routeLimit
            else -> null // Known nearby geometry is ambiguous; a provider cannot override it.
        }
        RoadSpeedState(own, limit, true)
    }

    private fun routeLimit(state: NavigationState, now: Long): RoadSpeedLimit? {
        val route = state.route
        if (route?.id != routeId) {
            routeId = route?.id; routeMatcher.reset()
            routeRoad = route?.let { OsmRoad(-1, it.vertices.map { point -> point.coordinate }, emptyList(), mapOf("oneway" to "yes")) }
        }
        val fix = state.fix ?: return null
        if (route == null || !state.guidance || state.progress?.offRoute == true || route.provider != RouteProvider.TOMTOM ||
            route.transport != state.sessionTransport || route.effectiveDepartureAt == null ||
            abs(route.effectiveDepartureAt - route.createdAt) > 120_000 || route.effectiveDepartureAt > now + 5_000 ||
            now - route.createdAt !in 0..300_000) { routeMatcher.reset(); return null }
        val matched = routeMatcher.match(fix, listOfNotNull(routeRoad)) ?: return null
        val sections = route.speedLimits.filter { matched.segment >= it.beginShapeIndex && matched.segment < it.endShapeIndex }
        if (sections.map { it.maxKmh }.distinct().size != 1) return null
        val section = sections.first()
        if (!section.maxKmh.isFinite() || section.maxKmh <= 0 || section.maxKmh > 400) return null
        // Do not carry an adjacent section's value across a speed-limit boundary within GPS uncertainty.
        val boundaryDistance = listOf(section.beginShapeIndex, section.endShapeIndex).mapNotNull { route.vertices.getOrNull(it) }
            .minOfOrNull { distance(it.coordinate, fix.coordinate) } ?: return null
        if (boundaryDistance < maxOf(8.0, fix.accuracyMeters.toDouble())) return null
        return RoadSpeedLimit(section.maxKmh, RoadSpeedSource.TOMTOM_ROUTE, false, fix.recordedAt,
            fix.coordinate, state.sessionTransport!!, "route:${route.id}:${section.beginShapeIndex}")
    }

    suspend fun refresh() = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val settings = credentials()
            val work = synchronized(lock) {
                if (latest.sessionId == null || !speedMode(latest.sessionTransport) || latest.simulation ||
                    latest.gpsStale || latest.fix?.let { speedFixFresh(it, clock()) } != true) return@withContext
                if (revision != settings.revision) {
                    posted = null; revision = settings.revision; generation++
                    matcher.reset(); routeMatcher.reset(); match = null
                }
                fallbackEnabled = settings.freeAccountVerified && settings.speedFallbackEnabled && !settings.apiKey.isNullOrBlank()
                Triple(generation, latest.fix!!, region?.contains(latest.fix!!.coordinate, clock()) != true)
            }
            if (work.third && clock() >= nextOsmAttempt) {
                nextOsmAttempt = clock() + 30_000
                try {
                    val loaded = roads(work.second.coordinate)
                    synchronized(lock) { if (generation == work.first && session != null) region = loaded }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* OSM remains unknown; no endpoint rotation or request loop. */ }
            }
            val query = synchronized(lock) {
                if (generation != work.first || latest.sessionId == null || latest.guidance || !fallbackEnabled) return@withContext
                val current = observe(latest, clock())
                val road = match ?: return@withContext
                if (current.limit != null || osmSpeedValue(road.road.tags, latest.sessionTransport!!, road.forward) != OsmSpeedValue.Missing) return@withContext
                Triple(latest.fix!!, road, latest.sessionTransport!!)
            }
            fun stillCurrent(): Boolean = synchronized(lock) {
                generation == work.first && latest.sessionId != null && !latest.guidance && latest.sessionTransport == query.third &&
                    match?.identity == query.second.identity && !latest.gpsStale &&
                    latest.fix?.let { speedFixFresh(it, clock()) && distance(it.coordinate, query.first.coordinate) <= 100 } == true
            }
            val value = reverse.lookup(query.first, query.second, settings.revision, ::stillCurrent) ?: return@withContext
            val revisionStillCurrent = credentials().revision == settings.revision
            synchronized(lock) {
                if (stillCurrent() && clock() - query.first.recordedAt in 0..10_000 && revisionStillCurrent) {
                    posted = RoadSpeedLimit(value.kmh, RoadSpeedSource.TOMTOM_POSTED, true,
                        query.first.recordedAt, query.first.coordinate, query.third, query.second.identity)
                }
            }
        }
    }
}
