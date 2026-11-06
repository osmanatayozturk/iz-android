package org.iz.navigation.ui

import org.iz.navigation.data.TrackPoint
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.driveCameraTurn
import org.iz.navigation.navigation.RouteEditPreview
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
    val editPreviewId: String? = null,
    val editPreviewLabel: String? = null,
)

internal fun navigationMapPresentation(ui: PhoneDirectionsState, nav: NavigationState,
    points: List<TrackPoint>, planning: Boolean, editPreview: RouteEditPreview? = null): NavigationMapPresentation {
    val active = nav.sessionId != null || nav.guidance || nav.recording || nav.locationActive
    val matchedEdit = editPreview?.takeIf {
        planning && it.id.isNotBlank() && it.owner.matches(nav) && nav.guidance &&
            !nav.paused && !nav.arrived && !nav.simulation
    }
    val route = matchedEdit?.route ?: if (planning) ui.preview else nav.route.takeIf { nav.guidance }
    val stops = if (planning) route?.stops ?: ui.stops else route?.stops.orEmpty()
    return NavigationMapPresentation(active, route, stops,
        emptyList(),
        nav.fix?.coordinate.takeIf { active } ?: ui.origin?.takeIf { ui.originCurrent }?.coordinate,
        when {
            matchedEdit != null -> "edit:${matchedEdit.owner.sessionId}:${matchedEdit.owner.routeId}:" +
                "${matchedEdit.owner.routeRevision}:${matchedEdit.id}:${matchedEdit.route.id}"
            planning -> ui.preview?.id ?: "planning:" + stops.joinToString { "${it.coordinate.latitude},${it.coordinate.longitude}" }
            else -> "session:${nav.sessionId}"
        },
        editPreviewId = matchedEdit?.id,
        editPreviewLabel = matchedEdit?.let { "Yeni rota önizlemesi · Onay bekleniyor" })
}

/** An online edit preview never borrows an active prepared-map owner, even if route IDs coincide. */
internal fun preparedNavigationMapApplies(presentation: NavigationMapPresentation, nav: NavigationState): Boolean =
    nav.guidance && !nav.simulation && nav.preparedRouteFingerprint?.isNotBlank() == true &&
        presentation.editPreviewId == null && nav.route != null && presentation.route == nav.route


internal fun phoneDrivingCameraEnabled(nav: NavigationState, planning: Boolean): Boolean {
    val mode = nav.sessionTransport ?: nav.journey?.transport
    return !planning && !nav.simulation && mode in setOf(org.iz.navigation.data.Transport.CAR, org.iz.navigation.data.Transport.MOTORCYCLE) &&
        (nav.guidance || nav.recording && nav.journey?.status == org.iz.navigation.data.JourneyStatus.CONFIRMED)
}

internal fun phoneDriveCameraInput(nav: NavigationState, planning: Boolean, nowMillis: Long): org.iz.navigation.navigation.DriveCameraInput {
    val fix = nav.fix
    val speed = fix?.speedMps?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
    val turn = nav.driveCameraTurn()
    return org.iz.navigation.navigation.DriveCameraInput(nowMillis, fix?.coordinate, speed?.times(3.6),
        fix?.bearingDegrees?.toDouble()?.takeIf { it.isFinite() }, !nav.gpsStale && fix != null,
        phoneDrivingCameraEnabled(nav, planning), turn?.meters, turn?.seconds)
}