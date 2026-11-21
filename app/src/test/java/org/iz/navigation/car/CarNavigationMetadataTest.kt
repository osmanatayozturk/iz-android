package org.iz.navigation.car

import androidx.car.app.navigation.model.Maneuver
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class CarNavigationMetadataTest {
    @Test fun directionGlyphsPreserveShapeAndSideAndDeferSpecializedManeuversToHost() {
        assertEquals(CarTurnGlyph(CarTurnShape.STRAIGHT), carTurnGlyph(8))
        for ((right, left, shape) in listOf(Triple(9, 16, CarTurnShape.SLIGHT),
            Triple(10, 15, CarTurnShape.TURN), Triple(11, 14, CarTurnShape.SHARP),
            Triple(12, 13, CarTurnShape.U_TURN), Triple(23, 24, CarTurnShape.FORK),
            Triple(20, 21, CarTurnShape.FORK))) {
            assertEquals(CarTurnGlyph(shape), carTurnGlyph(right))
            assertEquals(CarTurnGlyph(shape, left = true), carTurnGlyph(left))
        }
        for (type in listOf(4, 18, 25, 26, 27, 28, 37, 999)) assertNull(carTurnGlyph(type))
    }
    @Test fun turnIncludesNamedRoadOnceAndFallsBackToSpokenInstruction() {
        val turn = org.iz.navigation.weather.RouteManeuver(10, "Sağa dönün", "Sağa dönün",
            listOf(" ", " Test Caddesi "), 0, 1, 0.0, 12.0)
        assertEquals("Sağa dönün · Test Caddesi", turn.turnInstruction())
        val complete = "Test Caddesi yönünde sağa dönün"
        assertEquals(complete, turn.copy(instruction = complete).turnInstruction())
        assertEquals("Sağa dönün", turn.copy(instruction = "", streetNames = emptyList()).turnInstruction())
        assertEquals("Rotayı takip edin", turn.copy(instruction = "", verbalInstruction = "",
            streetNames = emptyList()).turnInstruction())
    }
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
