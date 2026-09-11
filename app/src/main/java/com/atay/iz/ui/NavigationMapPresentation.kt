package com.atay.iz.ui

import com.atay.iz.data.TrackPoint
import com.atay.iz.navigation.NavigationState
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.WeatherCoordinate

internal data class NavigationMapPresentation(
    val sessionActive: Boolean,
    val route: PlannedRoute?,
    val stops: List<RouteStop>,
    val trail: List<TrackPoint>,
    val coordinate: WeatherCoordinate?,
    val cameraIdentity: String,
)

internal fun navigationMapPresentation(ui: PhoneDirectionsState, nav: NavigationState,
    points: List<TrackPoint>, planning: Boolean): NavigationMapPresentation {
    val active = nav.sessionId != null || nav.guidance || nav.recording || nav.locationActive
    val route = if (planning) ui.preview else nav.route.takeIf { nav.guidance }
    val stops = if (planning) route?.stops ?: ui.stops else route?.stops.orEmpty()
    return NavigationMapPresentation(active, route, stops,
        points.filter { nav.recording && it.journeyId == nav.journey?.id },
        nav.fix?.coordinate.takeIf { active } ?: ui.origin?.takeIf { ui.originCurrent }?.coordinate,
        if (planning) ui.preview?.id ?: "planning:" + stops.joinToString { "${it.coordinate.latitude},${it.coordinate.longitude}" }
        else "session:${nav.sessionId}")
}
