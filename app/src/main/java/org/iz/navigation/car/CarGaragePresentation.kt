package org.iz.navigation.car

import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.garage.GarageVehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal suspend fun resolveCarStartVehicle(vehicles: Flow<List<GarageVehicle>>, transport: Transport,
    stillCurrent: () -> Boolean, start: suspend (String?) -> Unit, choose: () -> Unit) {
    val matching = vehicles.first().filter { it.transport == transport }
    check(stillCurrent()) { "Yolculuk değişti. Haritaya dönüp yeniden seçin." }
    if (matching.size <= 1) start(matching.singleOrNull()?.id) else choose()
}

/** Implicit persistent matching belongs to creation, never a screen's delayed completion. */
internal fun carShouldBindStartVehicle(state: NavigationState, explicit: Boolean,
    hasUnrecordedSelection: Boolean): Boolean = state.sessionId != null && !state.simulation &&
    (explicit || state.journey == null && !state.recording && !state.recordingPending && !hasUnrecordedSelection)

/** Identity for a pending manual vehicle choice; GPS/progress updates do not replace its owner. */
internal data class CarVehicleStartSnapshot(val sessionId: String?, val journeyId: String?,
    val journeyStatus: JourneyStatus?, val sessionTransport: Transport?, val routeRevision: Long, val guidance: Boolean,
    val recording: Boolean, val simulation: Boolean, val mode: Transport)

internal fun carVehicleStartSnapshot(state: NavigationState, mode: Transport) = CarVehicleStartSnapshot(
    state.sessionId, state.journey?.id, state.journey?.status, state.sessionTransport, state.routeRevision,
    state.guidance, state.recording, state.simulation, mode)

internal fun carFuelActionAllowed(shownId: String, currentId: String?, expiresAtElapsedMs: Long,
    nowElapsedMs: Long, visible: Boolean, busy: Boolean): Boolean =
    visible && !busy && shownId == currentId && nowElapsedMs < expiresAtElapsedMs
