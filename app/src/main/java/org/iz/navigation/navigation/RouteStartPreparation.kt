package org.iz.navigation.navigation

import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine

/** Shared boundary for attaching a fresh fix to a user-selected itinerary. */
fun prepareRouteStartStops(planned: List<RouteStop>, current: WeatherCoordinate): List<RouteStop> {
    require(planned.size in 2..5) { "Başlangıç ve varış noktalarını seç." }
    val origin = RouteStop("Mevcut konum", current)
    return if (WeatherEngine.distanceMeters(planned.first().coordinate, current) <= 100.0)
        listOf(origin) + planned.drop(1)
    else listOf(origin) + planned
}
