package org.iz.navigation.wear

import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.garage.GarageRepository
import org.iz.navigation.navigation.NavigationState

internal sealed interface WearGarageSelection {
    data class Ready(val vehicleId: String?, val explicit: Boolean = false) : WearGarageSelection
    data object NeedsPhone : WearGarageSelection
    data object Invalid : WearGarageSelection
}

internal fun wearGarageSelection(mode: Transport, vehicles: List<GarageVehicle>, selected: String?): WearGarageSelection {
    if (mode !in setOf(Transport.CAR, Transport.MOTORCYCLE)) return WearGarageSelection.Ready(null)
    val matching = vehicles.filter { it.transport == mode }
    if (selected != null) return if (matching.any { it.id == selected }) WearGarageSelection.Ready(selected, explicit = true) else WearGarageSelection.Invalid
    return if (matching.size > 1) WearGarageSelection.NeedsPhone else WearGarageSelection.Ready(matching.singleOrNull()?.id)
}

/** Re-read an inherited choice after service acknowledgement; never use a stale session's vehicle. */
internal fun wearStartChoiceForBinding(requestedVehicleId: String?, sourceSessionId: String?, mode: Transport,
    journeyId: String, state: NavigationState, currentChoice: GarageRepository.Binding?): String? {
    if (state.simulation) return null
    if (requestedVehicleId != null) return requestedVehicleId
    return currentChoice?.takeIf {
        sourceSessionId != null && it.sessionId == sourceSessionId && it.explicitChoice &&
            state.sessionId == sourceSessionId && state.sessionTransport == mode &&
            (state.journey == null || state.journey.id == journeyId)
    }?.vehicleId
}
