package org.iz.navigation.car

import androidx.car.app.navigation.model.Maneuver
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class CarNavigationMetadataTest {
    @Test fun unknownAndAmbiguousRoundaboutsNeverInventDirection() {
        listOf(0, 26, 27, 999).forEach { assertEquals(Maneuver.TYPE_UNKNOWN, carManeuverType(it)) }
    }
    @Test fun valhallaDirectionsPreserveLeftAndRight() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, carManeuverType(10))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, carManeuverType(15))
        assertEquals(Maneuver.TYPE_U_TURN_LEFT, carManeuverType(13))
    }
    @Test fun missingRouteCannotPublishDestinationOrTurn() {
        val trip = carTrip(NavigationState())
        assertTrue(trip.isLoading)
        assertTrue(trip.steps.isEmpty())
        assertTrue(trip.destinations.isEmpty())
    }
    @Test fun invalidOrTerminalPhoneStateCannotLeakCachedTripNumbers() {
        val point = org.iz.navigation.weather.WeatherCoordinate(41.0, 29.0)
        val route = org.iz.navigation.weather.PlannedRoute("trip", listOf(
            org.iz.navigation.weather.RouteStop("Başlangıç", point), org.iz.navigation.weather.RouteStop("Hedef", point)),
            listOf(org.iz.navigation.weather.RouteVertex(point, 0.0)), 100.0, 60.0, 1000,
            maneuvers = listOf(org.iz.navigation.weather.RouteManeuver(10, "Sağa", "Sağa", emptyList(), 0, 0, 0.0, 60.0)))
        val current = NavigationState(route = route, guidance = true, gpsStale = false,
            progress = org.iz.navigation.navigation.NavigationProgress(0.0, 60.0, 100.0, 0.0, 0, 20.0))
        val progress = checkNotNull(current.progress)
        for (state in listOf(current.copy(arrived = true), current.copy(guidance = false), current.copy(paused = true),
            current.copy(progress = progress.copy(remainingMeters = Double.NaN)),
            current.copy(progress = progress.copy(remainingSeconds = Double.POSITIVE_INFINITY)))) {
            val trip = carTrip(state)
            assertTrue(trip.isLoading)
            assertTrue(trip.steps.isEmpty())
            assertTrue(trip.destinations.isEmpty())
        }
    }
}
