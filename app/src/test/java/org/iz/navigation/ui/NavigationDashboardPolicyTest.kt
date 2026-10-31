package org.iz.navigation.ui

import org.junit.Assert.*
import org.junit.Test

class NavigationDashboardPolicyTest {
    @Test fun matchingOwnerIsCurrent() {
        assertTrue(dashboardOwnerCurrent(DashboardOwner("entry-7", "session-4"), DashboardOwner("entry-7", "session-4")))
    }
    @Test fun replacedEntryIsNotCurrent() {
        assertFalse(dashboardOwnerCurrent(DashboardOwner("entry-7", "session-4"), DashboardOwner("entry-8", "session-4")))
    }
    @Test fun replacedSessionIsNotCurrent() {
        assertFalse(dashboardOwnerCurrent(DashboardOwner("entry-7", "session-4"), DashboardOwner("entry-7", "session-5")))
    }
    @Test fun liveSessionOwnsWeather() {
        assertEquals("session-4", dashboardWeatherOwner("session-4", "journey-2", recording = true, simulation = false))
        assertEquals("session-4", dashboardWeatherOwner("session-4", "journey-2", recording = false, simulation = false))
    }
    @Test fun recordingJourneyFallbackOwnsWeather() {
        assertEquals("journey-2", dashboardWeatherOwner(null, "journey-2", recording = true, simulation = false))
    }
    @Test fun simulationOrInactiveHasNoWeatherOwner() {
        assertNull(dashboardWeatherOwner("session-4", "journey-2", recording = true, simulation = true))
        assertNull(dashboardWeatherOwner(null, "journey-2", recording = false, simulation = false))
        assertNull(dashboardWeatherOwner(null, null, recording = true, simulation = false))
    }
    @Test fun currentGuidanceKeepsSuppliedRemaining() {
        assertEquals(DashboardRemaining(1234.0, 90.0), dashboardRemaining(true, true, false, 1234.0, 90.0))
    }
    @Test fun recordOnlyPreviewAndStaleHaveNoRemaining() {
        assertNull(dashboardRemaining(false, true, false, 1234.0, 90.0))
        assertNull(dashboardRemaining(true, false, false, 1234.0, 90.0))
        assertNull(dashboardRemaining(true, true, true, 1234.0, 90.0))
    }
    @Test fun missingOrNonFiniteProgressHasNoRemaining() {
        val invalid: List<Pair<Double?, Double?>> = listOf(
            null to 90.0, 1234.0 to null,
            Double.NaN to 90.0, 1234.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 90.0, 1234.0 to Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY to 90.0, -1.0 to 90.0, 1234.0 to -1.0,
        )
        invalid.forEach { (meters, seconds) ->
            assertNull("Invalid progress $meters/$seconds must stay unavailable", dashboardRemaining(true, true, false, meters, seconds))
        }
    }
}
