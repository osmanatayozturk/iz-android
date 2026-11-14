package org.iz.navigation.weather

import org.iz.navigation.data.Transport

/** Routing permission only. TomTom auxiliary speed-limit settings/gates remain independent. */
internal object RoutingPolicy {
    fun usesTomTomRouting(transport: Transport): Boolean = transport == Transport.CAR || transport == Transport.PASSENGER
    fun usesHeuristicOrder(transport: Transport, size: Int): Boolean = transport == Transport.MOTORCYCLE || size >= 6
    fun supportsStopOrder(transport: Transport, size: Int): Boolean = size in 4..RouteStopLimits.MAX_PLANNED_STOPS &&
        if (usesHeuristicOrder(transport, size)) transport != Transport.UNKNOWN else usesTomTomRouting(transport)
}

internal const val MOTORCYCLE_ROUTE_CONTEXT = "Valhalla · Trafik hariç"

internal fun PlannedRoute.motorcycleTrafficFree(): PlannedRoute = copy(provider = RouteProvider.VALHALLA,
    traffic = null, trafficUnavailableReason = MOTORCYCLE_ROUTE_CONTEXT)
