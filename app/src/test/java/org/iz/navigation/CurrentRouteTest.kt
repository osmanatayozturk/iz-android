package org.iz.navigation

import org.iz.navigation.data.*
import org.iz.navigation.ui.DiaryState
import org.junit.Assert.*
import org.junit.Test

class CurrentRouteTest {
    private fun point(journey: Journey) = TrackPoint(journeyId = journey.id, latitude = 40.0, longitude = 32.0, accuracy = 5f)

    @Test fun homeMapShowsOnlyActiveRouteAndKeepsHistoryAvailable() {
        val old = Journey(transport = Transport.CAR, startedAt = 1, endedAt = 10)
        val active = Journey(transport = Transport.PASSENGER, startedAt = 20)
        val points = listOf(point(old), point(active))
        val state = DiaryState(journeys = listOf(active, old), points = points)
        assertEquals(listOf(points[1]), state.currentRoute(30))
        assertEquals(2, state.points.size)
        assertEquals(listOf(points[0]), state.points.filter { it.journeyId == old.id })
    }

    @Test fun noActiveTripMeansNoRouteOnHomeMap() {
        val old = Journey(startedAt = 1, endedAt = 10)
        assertTrue(DiaryState(journeys = listOf(old), points = listOf(point(old))).currentRoute(30).isEmpty())
    }

    @Test fun pending500MeterRouteIsVisibleUntilFinishedOrExpired() {
        val pending = Journey(startedAt = 10, expiresAt = 40, status = JourneyStatus.TEMPORARY)
        val state = DiaryState(journeys = listOf(pending), points = listOf(point(pending)))
        assertEquals(1, state.currentRoute(30).size)
        assertTrue(state.currentRoute(40).isEmpty())
        assertTrue(state.copy(journeys = listOf(pending.copy(endedAt = 35))).currentRoute(36).isEmpty())
    }
}
