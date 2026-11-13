package org.iz.navigation.ui

import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.RouteTripTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompactDrivingHudDataTest {
    private val known = NavigationState(guidance = true, gpsStale = false,
        progress = NavigationProgress(0.0, 360.0, 3000.0, 0.0, 0, 200.0),
        tripTiming = RouteTripTiming(360.0, 120.0, 480.0, 480000, 0))

    private fun data(state: NavigationState) = compactHudData(state, "18°", null, null)

    @Test fun knownSummaryKeepsExistingValuesAndFullDescription() {
        val hud = data(known)
        assertEquals("200 m · 3,0 km / 8 dk · 2 dk mola dahil", hud.summaryText)
        assertEquals("Manevra 200 m · Kalan 3.0 km · 8 dk · 2 dk mola dahil", hud.summaryDescription)
        assertEquals("Manevra 200 m", hud.next)
        assertEquals("Kalan 3.0 km · 8 dk · 2 dk mola dahil", hud.remaining)
    }

    @Test fun initialAndStaleSummaryKeepUnknownDistancesAndTimeWithoutInventingZero() {
        for (state in listOf(NavigationState(tripTiming = known.tripTiming), known.copy(gpsStale = true))) {
            val hud = data(state)
            assertEquals("— · — / — · 2 dk mola dahil", hud.summaryText)
            assertEquals("Manevra — · Kalan — · — · 2 dk mola dahil", hud.summaryDescription)
            assertFalse(hud.summaryText.contains('0'))
        }
    }

    @Test fun missingAndInvalidProgressStayUnknown() {
        val invalid = NavigationProgress(0.0, Double.NaN, Double.NaN, 0.0, 0, -1.0)
        for (progress in listOf(null, invalid)) {
            val hud = data(known.copy(progress = progress, tripTiming = null))
            assertEquals("— · — / —", hud.summaryText)
            assertEquals("Manevra — · Kalan — · —", hud.summaryDescription)
        }
    }

    @Test fun longNumbersPreserveDistanceAndMinuteRounding() {
        val hud = data(known.copy(
            progress = NavigationProgress(0.0, 12345.0, 999900.0, 0.0, 0, 12345.0),
            tripTiming = RouteTripTiming(12224.0, 121.0, 12345.0, 12345000, 0)))
        assertEquals("12,3 km · 999,9 km / 206 dk · 3 dk mola dahil", hud.summaryText)
        assertEquals("Manevra 12.3 km · Kalan 999.9 km · 206 dk · 3 dk mola dahil", hud.summaryDescription)
    }
}
