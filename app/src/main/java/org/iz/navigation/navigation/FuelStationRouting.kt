package org.iz.navigation.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteModificationRequest
import org.iz.navigation.weather.RouteSchedule
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import org.iz.navigation.weather.requireAvoidedAreas
import org.iz.navigation.weather.requireUsablePreferences
import org.iz.navigation.weather.routeTravelSpeedKmh

internal data class FuelStationPlan(
    val title: String,
    val distanceToStationMeters: Double,
    val additionalSeconds: Double?,
    internal val owner: FuelStationOwner,
    internal val route: PlannedRoute,
)

internal data class FuelStationOwner(
    val epoch: Long,
    val sessionId: String?,
    val journeyId: String?,
    val recording: Boolean,
    val routeId: String?,
    val routeRevision: Long,
    val transport: Transport,
    val fix: NavigationFix,
    val remainingStops: List<Int>,
) {
    fun matches(state: NavigationState, currentEpoch: Long, currentStops: List<Int>): Boolean =
        epoch == currentEpoch && state.sessionId == sessionId && state.journey?.id == journeyId &&
            state.recording == recording && state.route?.id == routeId && state.routeRevision == routeRevision &&
            (state.sessionTransport == null || state.sessionTransport == transport) &&
            state.guidance == (routeId != null) && !state.paused && !state.arrived && !state.simulation &&
            !state.loading && !state.recordingPending && state.trackFollow == null && currentStops == remainingStops
}

/** Requests stay bounded within the same provider path used for an ordinary route edit. */
internal object FuelStationRouting {
    private const val MAX_ROUTE_ATTEMPTS = 8
    private const val STATION_SNAP_METERS = 50.0
    internal const val MAX_ORIGIN_DRIFT_METERS = 650.0

    suspend fun choose(
        owner: FuelStationOwner,
        currentRoute: PlannedRoute?,
        currentProgress: NavigationProgress?,
        stations: List<SelectedOsmPlace>,
        request: (RouteStop) -> RouteModificationRequest?,
        plan: suspend (RouteModificationRequest) -> PlannedRoute,
        stillCurrent: suspend () -> Boolean,
    ): FuelStationPlan? {
        val context = currentCoroutineContext()
        val candidates = stations.distinctBy { it.osmRef }.filter { it.osmRef != null }.take(MAX_ROUTE_ATTEMPTS)
        var best: FuelStationPlan? = null
        for (station in candidates) {
            context.ensureActive()
            if (!stillCurrent()) return null
            val stop = RouteStop(station.name, WeatherCoordinate(station.latitude, station.longitude))
            val input = request(stop) ?: continue
            val result = try { plan(input) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { continue }
            context.ensureActive()
            if (!stillCurrent()) return null
            if (result.stops != input.stops || result.transport != input.transport ||
                result.preferences != input.preferences || result.avoidAreas != input.avoidAreas ||
                result.travelSpeedKmh != routeTravelSpeedKmh(input.transport, input.travelSpeedKmh) ||
                result.maneuvers.isEmpty() || result.vertices.size < 2 || result.durationSeconds <= 0 ||
                result.distanceMeters <= 0 || (currentRoute != null && result.provider != currentRoute.provider)) continue
            val stationAt = if (result.stops.size == 2) result.durationSeconds
                else result.stopElapsedSeconds.getOrNull(1)?.takeIf { it > 0 && it < result.durationSeconds } ?: continue
            val stationSnap = result.vertices.asSequence().filter { kotlin.math.abs(it.elapsedSeconds - stationAt) <= .001 }
                .minOfOrNull { WeatherEngine.distanceMeters(it.coordinate, stop.coordinate) } ?: continue
            if (stationSnap > STATION_SNAP_METERS) continue
            try {
                result.requireUsablePreferences()
                requireAvoidedAreas(result, input.avoidAreas, context::ensureActive)
                RouteSchedule.planned(result, input.departureAt)
            } catch (_: IllegalArgumentException) { continue }
            val meters = NavigationEngine(result).distanceToStopMeters(1)?.takeIf { it.isFinite() && it > 5 } ?: continue
            val additional = currentProgress?.let { (result.durationSeconds - it.remainingSeconds).coerceAtLeast(0.0) }
            val candidate = FuelStationPlan(stop.label, meters, additional, owner, result)
            val previous = best
            val earlier = if (currentRoute == null) previous == null || meters < previous.distanceToStationMeters
                else previous == null || result.durationSeconds < previous.route.durationSeconds
            if (earlier) best = candidate
        }
        return best
    }
}
