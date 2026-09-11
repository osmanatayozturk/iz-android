package com.atay.iz.navigation

import com.atay.iz.data.Journey
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.WeatherCoordinate

data class NavigationFix(
    val coordinate: WeatherCoordinate,
    val recordedAt: Long,
    val accuracyMeters: Float,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
)

data class NavigationProgress(
    val elapsedSeconds: Double,
    val remainingSeconds: Double,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double,
    val maneuverIndex: Int,
    val nextManeuverDistanceMeters: Double,
    val arrived: Boolean = false,
    val offRoute: Boolean = false,
)

data class NavigationState(
    val journey: Journey? = null,
    val recording: Boolean = false,
    val route: PlannedRoute? = null,
    val guidance: Boolean = false,
    val arrived: Boolean = false,
    val progress: NavigationProgress? = null,
    val fix: NavigationFix? = null,
    val gpsStale: Boolean = true,
    val loading: Boolean = false,
    val message: String? = null,
    val muted: Boolean = false,
    val routeRevision: Long = 0,
    val simulation: Boolean = false,
    val sessionId: String? = null,
    val sessionTransport: com.atay.iz.data.Transport? = null,
    val sharingLocation: Boolean = false,
    val locationActive: Boolean = false,
)
