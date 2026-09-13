package org.iz.navigation.weather

import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.data.Transport

class RoutePreferencesDefaultsTest {
    @Test fun activeTravelAvoidsMotorwaysByDefault() {
        listOf(Transport.WALK, Transport.RUN, Transport.BICYCLE).forEach {
            assertTrue(RoutePreferences.defaults(it).avoidHighways)
            assertFalse(RoutePreferences.defaults(it).avoidTolls)
            assertFalse(RoutePreferences.defaults(it).avoidFerries)
        }
    }
    @Test fun motorizedTravelPreservesExistingDefaults() {
        listOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE).forEach {
            assertEquals(RoutePreferences(), RoutePreferences.defaults(it))
        }
    }
    @Test fun explicitChoiceCanBeStoredWithoutReapplyingDefaults() {
        val selected = RoutePreferences.defaults(Transport.WALK).copy(avoidHighways = false)
        assertFalse(selected.avoidHighways)
    }
}

