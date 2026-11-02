package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.navigation.JourneyNavigationCoordinator
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.RouteEditOwner
import org.iz.navigation.navigation.RouteEditState
import org.iz.navigation.navigation.RouteOperationsController
import org.iz.navigation.navigation.RouteStopLookupKind
import org.iz.navigation.navigation.RouteStopLookupState
import org.iz.navigation.weather.RideWeatherLiveState
import org.iz.navigation.weather.RideWeatherStatus
import org.iz.navigation.weather.RideWeatherTiming
import org.iz.navigation.weather.RouteEditAction
import java.util.Locale

/** Neither fixes nor progress timestamps belong to the presentation identity. */
internal fun routeOperationsToken(entryId: Long, nav: NavigationState, edits: RouteEditState,
    lookup: RouteStopLookupState): RouteOperationsUiToken = RouteOperationsUiToken(
    ownerId = "$entryId:${lookup.takeIf { it.owner?.matches(nav) == true }?.requestId.orEmpty()}",
    sessionId = nav.sessionId, routeId = nav.route?.id, routeRevision = nav.routeRevision,
    generation = edits.generation,
)

internal fun routeOperationsData(entryId: Long, nav: NavigationState, edits: RouteEditState,
    lookup: RouteStopLookupState, online: Boolean?, commandBusy: Boolean, remainingStops: List<Int>,
    weather: RideWeatherLiveState? = null, now: Long = System.currentTimeMillis()): RouteOperationsPanelData {
    val token = routeOperationsToken(entryId, nav, edits, lookup)
    val owner = RouteEditOwner.from(nav)
    val route = nav.route
    val active = owner != null && route != null && nav.guidance && !nav.arrived && !nav.simulation
    val freshFix = !nav.gpsStale && nav.fix?.let { JourneyNavigationCoordinator.fresh(it, now) } == true
    val progressing = active && !nav.paused && !nav.loading && freshFix &&
        nav.progress?.let { !it.offRoute && it.elapsedSeconds.isFinite() } == true
    val currentLookup = lookup.takeIf { active && !nav.paused && it.owner == owner }
    val lookupFresh = currentLookup?.fetchedAt?.let { now - it in 0..600_000L } == true
    val preview = edits.preview?.takeIf { active && !nav.paused && it.owner == owner }?.let {
        RouteOperationUiPreview(token, it.id, it.route, when (it.action) {
            is RouteEditAction.AvoidManeuver -> RoutePreviewUiReason.AVOID
            is RouteEditAction.SkipStop -> RoutePreviewUiReason.SKIP
            is RouteEditAction.AddStop -> RoutePreviewUiReason.POI
        })
    }
    val maneuvers = if (progressing && route != null) route.maneuvers.mapIndexedNotNull { index, maneuver ->
        if (maneuver.endElapsedSeconds > requireNotNull(nav.progress).elapsedSeconds &&
            maneuver.beginShapeIndex in route.vertices.indices && maneuver.endShapeIndex in route.vertices.indices &&
            maneuver.beginShapeIndex < maneuver.endShapeIndex)
            RouteAvoidUiTarget(index.toString(), maneuver.instruction.ifBlank { "Rota bölümü ${index + 1}" })
        else null
    } else emptyList()
    val stops = if (progressing && route != null) remainingStops.distinct().filter { it in 1 until route.stops.lastIndex }
        .map { RouteStopUiTarget(it.toString(), route.stops[it]) } else emptyList()
    val poi = currentLookup?.let { current ->
        val requestId = current.requestId ?: return@let null
        val kind = when (current.kind) {
            RouteStopLookupKind.FUEL -> RoutePoiUiKind.FUEL
            RouteStopLookupKind.REST -> RoutePoiUiKind.REST
            null -> return@let null
        }
        val results = current.results.mapNotNull { result ->
            val id = RouteOperationsController.identity(result) ?: return@mapNotNull null
            RoutePoiUiResult(id, result.place.name, result.place.displayName,
                result.offsetMeters.takeIf { it.isFinite() && it >= 0 }?.let {
                    RoutePoiUiDistance(it, RoutePoiUiDistanceKind.FROM_ROUTE)
                })
        }.distinctBy { it.id }
        RoutePoiUiData(token, requestId, kind, when {
            current.loading -> RoutePoiUiState.LOADING
            current.error != null -> RoutePoiUiState.ERROR
            !lookupFresh -> if (results.isEmpty()) RoutePoiUiState.ERROR else RoutePoiUiState.CACHED
            results.isEmpty() -> RoutePoiUiState.EMPTY
            else -> RoutePoiUiState.RESULTS
        }, results, current.fetchedAt, current.error)
    }
    val pendingId = preview?.requestId ?: edits.requestId ?: poi?.requestId
    val producerBusy = edits.loading || currentLookup?.loading == true
    val unrelatedBusy = commandBusy && !producerBusy
    val actions = buildSet {
        if (active && !unrelatedBusy) {
            if (!nav.paused) add(RouteOperationUiAction.PAUSE)
            else if (freshFix) add(RouteOperationUiAction.RESUME)
            if (pendingId != null) add(RouteOperationUiAction.CANCEL)
        }
        if (progressing && !commandBusy && !producerBusy) {
            if (preview != null) add(RouteOperationUiAction.CONFIRM)
            else if (online == true) {
                if (maneuvers.isNotEmpty()) add(RouteOperationUiAction.AVOID)
                if (stops.isNotEmpty()) add(RouteOperationUiAction.SKIP)
                add(RouteOperationUiAction.LOOKUP_FUEL)
                add(RouteOperationUiAction.LOOKUP_REST)
                if (lookupFresh && poi?.results?.isNotEmpty() == true) add(RouteOperationUiAction.SELECT_POI)
            }
        }
    }
    val ownedWeather = weather?.takeIf {
        owner != null && it.journeyId == owner.sessionId && it.route?.id == owner.routeId &&
            it.routeRevision == owner.routeRevision && it.paused == nav.paused
    }
    val arrivalCurrent = ownedWeather != null && !nav.paused && freshFix && !ownedWeather.gpsStale &&
        ownedWeather.timingUpdatedAt?.let { now - it in 0..10_000L } == true
    val weatherFact = if (ownedWeather != null && route != null) ownedWeather.assessment?.let { assessment ->
        fun number(value: Double?, unit: String): String = value?.takeIf { it.isFinite() }?.let {
            String.format(Locale.forLanguageTag("tr-TR"), "%.0f", it) + " " + unit
        } ?: "Bilinmiyor"
        RouteWeatherUiFact(route.id, ownedWeather.routeRevision,
            listOf("Yağış olasılığı en çok: ${number(assessment.maxPrecipitationProbabilityPercent, "%")}.",
                "Rüzgâr en çok: ${number(assessment.maxWindKmh, "km/sa")}.",
                if (assessment.complete) "Rota tahmini mevcut." else "Rota tahmini kısmen eksik.").joinToString(" "),
            current = !nav.paused && freshFix && !ownedWeather.gpsStale && !ownedWeather.refreshFailed &&
                ownedWeather.status == RideWeatherStatus.READY && assessment.complete &&
                RideWeatherTiming.forecastFresh(assessment.fetchedAt, now),
            fetchedAt = assessment.fetchedAt)
    } else null
    return RouteOperationsPanelData(
        token, route, nav.paused, when (online) {
            true -> RouteOnlineUiState.AVAILABLE
            false -> RouteOnlineUiState.OFFLINE
            null -> RouteOnlineUiState.UNKNOWN
        }, actions, maneuvers, stops, preview, pendingId,
        busy = commandBusy || producerBusy || nav.loading,
        error = edits.error,
        arrivalAt = ownedWeather?.arrivalAt,
        arrivalCurrent = arrivalCurrent,
        timingUpdating = !nav.paused && !arrivalCurrent,
        weather = weatherFact,
        poi = poi,
    )
}

@Composable
internal fun RouteOperationsControls(vm: PhoneNavigationViewModel, entryId: Long, nav: NavigationState,
    commandBusy: Boolean, weather: RideWeatherLiveState) {
    val edits by vm.navigation.edits.collectAsStateWithLifecycle()
    val lookup by vm.routeOperations.lookup.collectAsStateWithLifecycle()
    val online by vm.routeOperations.online.collectAsStateWithLifecycle()
    val owner = RouteEditOwner.from(nav) ?: return
    if (!nav.guidance || nav.simulation) return
    val data = routeOperationsData(entryId, nav, edits, lookup, online, commandBusy,
        vm.navigation.remainingStopIndices(owner), weather)
    RouteOperationsPanel(data, RouteOperationsPanelCallbacks(
        onAvoid = { token, id -> vm.routeOperation(entryId, token, RouteOperationUiAction.AVOID, targetId = id) },
        onSkip = { token, id -> vm.routeOperation(entryId, token, RouteOperationUiAction.SKIP, targetId = id) },
        onLookup = { token, kind -> vm.routeOperation(entryId, token,
            if (kind == RoutePoiUiKind.FUEL) RouteOperationUiAction.LOOKUP_FUEL else RouteOperationUiAction.LOOKUP_REST) },
        onSelectPoi = { token, requestId, id -> vm.routeOperation(entryId, token, RouteOperationUiAction.SELECT_POI,
            targetId = id, requestId = requestId) },
        onConfirm = { token, requestId, routeId -> vm.routeOperation(entryId, token, RouteOperationUiAction.CONFIRM,
            requestId = requestId, previewRouteId = routeId) },
        onCancel = { token, requestId -> vm.routeOperation(entryId, token, RouteOperationUiAction.CANCEL, requestId = requestId) },
        onPause = { token -> vm.routeOperation(entryId, token, RouteOperationUiAction.PAUSE) },
        onResume = { token -> vm.routeOperation(entryId, token, RouteOperationUiAction.RESUME) },
    ))
}
