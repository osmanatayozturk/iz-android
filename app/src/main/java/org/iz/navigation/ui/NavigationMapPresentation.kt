package org.iz.navigation.ui

import org.iz.navigation.data.TrackPoint
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate

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
        emptyList(),
        nav.fix?.coordinate.takeIf { active } ?: ui.origin?.takeIf { ui.originCurrent }?.coordinate,
        if (planning) ui.preview?.id ?: "planning:" + stops.joinToString { "${it.coordinate.latitude},${it.coordinate.longitude}" }
        else "session:${nav.sessionId}")
}

internal fun phoneDrivingCameraEnabled(nav: NavigationState, planning: Boolean): Boolean {
    val mode = nav.sessionTransport ?: nav.journey?.transport
    return !planning && !nav.simulation && mode in setOf(org.iz.navigation.data.Transport.CAR, org.iz.navigation.data.Transport.MOTORCYCLE) &&
        (nav.guidance || nav.recording && nav.journey?.status == org.iz.navigation.data.JourneyStatus.CONFIRMED)
}

internal fun phoneDriveCameraInput(nav: NavigationState, planning: Boolean, nowMillis: Long): org.iz.navigation.navigation.DriveCameraInput {
    val fix = nav.fix
    val speed = fix?.speedMps?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
    val progress = nav.progress
    val maneuver = if (nav.guidance && !nav.arrived && progress != null && !progress.arrived && !progress.offRoute)
        nav.route?.maneuvers?.getOrNull(progress.maneuverIndex) else null
    val meters = maneuver?.let { progress?.nextManeuverDistanceMeters }?.takeIf { it.isFinite() && it >= 0 }
    val seconds = maneuver?.let { it.beginElapsedSeconds - (progress?.elapsedSeconds ?: Double.NaN) }
        ?.takeIf { it.isFinite() && it >= 0 }
    return org.iz.navigation.navigation.DriveCameraInput(nowMillis, fix?.coordinate, speed?.times(3.6),
        fix?.bearingDegrees?.toDouble()?.takeIf { it.isFinite() }, !nav.gpsStale && fix != null,
        phoneDrivingCameraEnabled(nav, planning), meters, seconds)
}