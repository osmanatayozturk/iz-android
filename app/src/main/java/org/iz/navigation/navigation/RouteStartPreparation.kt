package org.iz.navigation.navigation

import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RoutePlanner
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.requireUsablePreferences
import org.iz.navigation.weather.revalidateSelection

/** Revalidate an explicitly selected geometry before starting from a fresh location. */
suspend fun prepareRouteStart(planner: RoutePlanner, planned: PlannedRoute, current: WeatherCoordinate,
    departureAt: Long, originUsesCurrentLocation: Boolean = false,
    preferences: RoutePreferences = planned.preferences, travelSpeedKmh: Double? = planned.travelSpeedKmh): PlannedRoute {
    val userStops = planned.stops.toMutableList().also {
        if (originUsesCurrentLocation) it[0] = RouteStop("Mevcut konum", current)
    }
    val stops = prepareRouteStartStops(userStops, current)
    return if (planned.selectionLocked) planner.revalidateSelection(planned, stops, departureAt,
        planned.transport, travelSpeedKmh, preferences)
    else planner.plan(stops, departureAt, planned.transport, travelSpeedKmh, preferences).requireUsablePreferences()
}

/** Shared boundary for attaching a fresh fix to a user-selected itinerary. */
fun prepareRouteStartStops(planned: List<RouteStop>, current: WeatherCoordinate): List<RouteStop> {
    require(planned.size in 2..5) { "Başlangıç ve varış noktalarını seç." }
    val origin = RouteStop("Mevcut konum", current)
    return if (WeatherEngine.distanceMeters(planned.first().coordinate, current) <= 100.0)
        listOf(origin) + planned.drop(1)
    else listOf(origin) + planned
}
