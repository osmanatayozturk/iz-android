package com.atay.iz.car

import androidx.car.app.navigation.model.Maneuver
import com.atay.iz.navigation.NavigationState
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
}
