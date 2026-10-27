package org.iz.navigation.ui

import org.iz.navigation.data.*
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class MotorcyclePhonePolicyTest {
    @Test fun drivingCameraExcludesPlanningTemporaryAndOtherModes() {
        val moto = Journey(transport = Transport.MOTORCYCLE)
        val recording = NavigationState(journey = moto, recording = true, sessionTransport = Transport.MOTORCYCLE)
        assertTrue(phoneDrivingCameraEnabled(recording, false))
        assertFalse(phoneDrivingCameraEnabled(recording, true))
        assertFalse(phoneDrivingCameraEnabled(recording.copy(journey = moto.copy(status = JourneyStatus.TEMPORARY)), false))
        assertFalse(phoneDrivingCameraEnabled(recording.copy(sessionTransport = Transport.BICYCLE, journey = moto.copy(transport = Transport.BICYCLE)), false))
        assertFalse(phoneDrivingCameraEnabled(recording.copy(recording = false), false))
        assertTrue(phoneDrivingCameraEnabled(NavigationState(guidance = true, sessionTransport = Transport.CAR), false))
        assertFalse(phoneDrivingCameraEnabled(recording.copy(simulation = true), false))
    }
    private fun guidanceState(): NavigationState {
        val a = org.iz.navigation.weather.WeatherCoordinate(41.0, 29.0)
        val b = org.iz.navigation.weather.WeatherCoordinate(41.001, 29.001)
        val route = org.iz.navigation.weather.PlannedRoute("camera", listOf(
            org.iz.navigation.weather.RouteStop("A", a), org.iz.navigation.weather.RouteStop("B", b)),
            listOf(org.iz.navigation.weather.RouteVertex(a, 0.0),
                org.iz.navigation.weather.RouteVertex(org.iz.navigation.weather.WeatherCoordinate(41.0005, 29.0005), 20.0),
                org.iz.navigation.weather.RouteVertex(b, 30.0)),
            200.0, 30.0, 1L, maneuvers = listOf(org.iz.navigation.weather.RouteManeuver(
                1, "Turn", "Turn", emptyList(), 1, 2, 20.0, 30.0)))
        return NavigationState(guidance = true, sessionTransport = Transport.MOTORCYCLE, route = route,
            progress = org.iz.navigation.navigation.NavigationProgress(12.0, 18.0, 150.0, 0.0, 0, 150.0),
            fix = org.iz.navigation.navigation.NavigationFix(a, 1L, 3f, 1f, 0f), gpsStale = false)
    }
    @Test fun cameraHasNoManeuverHintWithoutRealNextManeuver() {
        val state = guidanceState()
        for (index in listOf(-1, 1)) {
            val input = phoneDriveCameraInput(state.copy(progress = state.progress!!.copy(
                maneuverIndex = index, nextManeuverDistanceMeters = 0.0)), false, 1000L)
            assertNull(input.maneuverMeters)
            assertNull(input.maneuverSeconds)
        }
    }
    @Test fun cameraSuppressesHintsForRecordingOnlyOffRouteAndArrival() {
        val state = guidanceState()
        val variants = listOf(state.copy(guidance = false, recording = true, journey = Journey(transport = Transport.MOTORCYCLE)),
            state.copy(progress = state.progress!!.copy(offRoute = true)), state.copy(arrived = true),
            state.copy(progress = state.progress!!.copy(arrived = true)))
        for (variant in variants) {
            val input = phoneDriveCameraInput(variant, false, 1000L)
            assertNull(input.maneuverMeters)
            assertNull(input.maneuverSeconds)
        }
    }
    @Test fun cameraManeuverTimeUsesRouteTimelineRatherThanDistanceOverSpeed() {
        val input = phoneDriveCameraInput(guidanceState(), false, 1000L)
        assertEquals(150.0, input.maneuverMeters!!, 0.0)
        assertEquals(8.0, input.maneuverSeconds!!, 0.0)
    }
    @Test fun shortcutRejectsDeletedOrInvalidDestinationRatherThanZeroCoordinates() {
        assertNull(shortcutRouteStop(null))
        assertNull(shortcutRouteStop(Place(name = "Ev")))
        assertNull(shortcutRouteStop(Place(name = "", latitude = 41.0, longitude = 29.0)))
        assertNull(shortcutRouteStop(Place(name = "A".repeat(301), latitude = 41.0, longitude = 29.0)))
        assertNull(shortcutRouteStop(Place(name = "Ev", latitude = Double.NaN, longitude = 29.0)))
        assertNull(shortcutRouteStop(Place(name = "Ev", latitude = 91.0, longitude = 29.0)))
        val stop = shortcutRouteStop(Place(name = "Ev", latitude = 41.0, longitude = 29.0))!!
        assertEquals(41.0, stop.coordinate.latitude, 0.0)
        assertEquals("Ev", stop.label)
    }
}