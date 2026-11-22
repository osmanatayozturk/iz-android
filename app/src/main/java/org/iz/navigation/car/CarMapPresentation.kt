package org.iz.navigation.car

import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.JourneyNavigationCoordinator
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.speed.ownSpeedKmh
import org.iz.navigation.speed.speedFixFresh

internal fun carUsesNavigationFix(state: NavigationState): Boolean =
    state.sessionId != null || state.recording || state.guidance || state.simulation

internal fun carMapLocationVisible(home: Boolean, session: Boolean, surface: Boolean, state: NavigationState): Boolean =
    home && session && surface && !carUsesNavigationFix(state)

/** Display-only projection. It never opens a navigation session or writes a journey. */
internal fun carMapDisplayState(state: NavigationState, fix: NavigationFix?, speed: RoadSpeedState, now: Long,
    transport: Transport = Transport.CAR): NavigationState {
    if (carUsesNavigationFix(state)) return state
    val fresh = fix?.takeIf { JourneyNavigationCoordinator.fresh(it, now) }
    return state.copy(fix = fresh, gpsStale = fresh == null,
        roadSpeed = if (fresh == null || !speedFixFresh(fresh, now)) RoadSpeedState(visible = true)
            else speed.copy(ownKmh = ownSpeedKmh(fresh, now), visible = true,
                limit = speed.limit?.takeIf { it.observedAt == fresh.recordedAt &&
                    it.coordinate == fresh.coordinate && it.transport == transport }))
}

internal enum class CarRecordingAction(val title: String) {
    START("Kaydı başlat"), SAVE("Sürüşü kaydet"), FINISH("Kaydı bitir")
}

internal fun carRecordingAction(state: NavigationState): CarRecordingAction = when {
    state.journey?.status == JourneyStatus.TEMPORARY -> CarRecordingAction.SAVE
    state.recording -> CarRecordingAction.FINISH
    else -> CarRecordingAction.START
}

internal data class CarRecordingRequest(val action: CarRecordingAction, val sessionId: String?,
    val journeyId: String?, val routeRevision: Long, val mode: Transport, val simulation: Boolean)

internal fun carRecordingRequest(state: NavigationState, mode: Transport): CarRecordingRequest? =
    if (state.guidance) null else CarRecordingRequest(carRecordingAction(state), state.sessionId,
        state.journey?.id, state.routeRevision, mode, state.simulation)

internal data class CarRecordingStatus(val starting: Boolean = false, val error: String? = null)
