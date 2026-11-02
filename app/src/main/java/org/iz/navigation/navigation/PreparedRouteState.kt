package org.iz.navigation.navigation

import org.iz.navigation.offline.OfflinePack
import org.iz.navigation.weather.PlannedRoute

data class PreparedCorridorSelection(val fromMeters: Double, val toMeters: Double, val halfWidthMeters: Double)
data class PreparedRouteState(val route: PlannedRoute? = null, val fingerprint: String? = null,
    val pack: OfflinePack? = null, val selection: PreparedCorridorSelection? = null,
    val generation: Long = 0, val busy: Boolean = false, val inUse: Boolean = false, val error: String? = null)
