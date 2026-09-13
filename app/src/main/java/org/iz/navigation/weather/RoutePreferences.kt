package org.iz.navigation.weather

import org.iz.navigation.data.Transport

/** A user's explicit choices. Defaults are applied only when no choice has been saved. */
data class RoutePreferences(
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
) {
    companion object {
        fun defaults(transport: Transport) = RoutePreferences(
            avoidHighways = transport in setOf(Transport.WALK, Transport.RUN, Transport.BICYCLE),
        )
    }
}

