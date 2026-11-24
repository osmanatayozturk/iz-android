package org.iz.navigation.wear

import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle

internal sealed interface WearGarageSelection {
    data class Ready(val vehicleId: String?) : WearGarageSelection
    data object NeedsPhone : WearGarageSelection
    data object Invalid : WearGarageSelection
}

internal fun wearGarageSelection(mode: Transport, vehicles: List<GarageVehicle>, selected: String?): WearGarageSelection {
    if (mode !in setOf(Transport.CAR, Transport.MOTORCYCLE)) return WearGarageSelection.Ready(null)
    val matching = vehicles.filter { it.transport == mode }
    if (selected != null) return if (matching.any { it.id == selected }) WearGarageSelection.Ready(selected) else WearGarageSelection.Invalid
    return if (matching.size > 1) WearGarageSelection.NeedsPhone else WearGarageSelection.Ready(matching.singleOrNull()?.id)
}
