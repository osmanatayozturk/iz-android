package org.iz.navigation.wear

import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class WearNavigationFactoryTest {
    @Test fun trackFollowingCannotPublishOldTurnOrEtaEvenIfLegacyFlagsRemain() {
        val track = org.iz.navigation.gpx.ImportedTrack(name = "GPX", segments = emptyList())
        val follow = org.iz.navigation.gpx.TrackFollowState(track, org.iz.navigation.gpx.TrackFollowSelection(),
            org.iz.navigation.gpx.TrackFollowProgress(0.0, 100.0, null, org.iz.navigation.gpx.TrackFollowStatus.WAITING_FOR_GPS))
        assertNull(WearNavigationFactory.create(state().copy(trackFollow = follow), now))
    }
    private val now = 1_000_000L
    private val route = PlannedRoute("route", listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)),
        RouteStop("Kızılay", WeatherCoordinate(0.01, 0.01))),
        listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.01, 0.01), 100.0)),
        1200.0, 100.0, now - 1000, transport = Transport.CAR,
        maneuvers = listOf(RouteManeuver(10, "Sağa dönün", "Sağa dönün", emptyList(), 0, 1, 0.0, 100.0)))
    private fun state() = NavigationState(sessionId = "navigation-session", guidance = true, recording = false,
        route = route, gpsStale = false, fix = NavigationFix(WeatherCoordinate(0.0, 0.0), now - 1000, 5f),
        progress = NavigationProgress(10.0, 90.0, 1000.0, 0.0, 0, 120.0))

    @Test fun navigationWithoutDiaryRecordingHasTurnAndEta() {
        val value = requireNotNull(WearNavigationFactory.create(state(), now))
        assertTrue(value.guidance)
        assertEquals("navigation-session", value.sessionId)
        assertEquals("Sağa dönün", value.instruction)
        assertEquals(10, value.maneuverType)
        assertEquals(120.0, value.nextManeuverMeters!!, 0.0)
        assertEquals(1000.0, value.remainingMeters!!, 0.0)
        assertEquals(now - 1000 + 90_000, value.arrivalAt)
    }
    @Test fun republishingDoesNotRenewGpsAgeOrMoveEta() {
        val first = requireNotNull(WearNavigationFactory.create(state(), now))
        val later = requireNotNull(WearNavigationFactory.create(state(), now + 5000))
        assertEquals(first.fixAt, later.fixAt)
        assertEquals(first.arrivalAt, later.arrivalAt)
        val expired = requireNotNull(WearNavigationFactory.create(state(), now + 29_000))
        assertTrue(expired.gpsStale)
        assertEquals("", expired.instruction)
        assertNull(expired.arrivalAt)
        assertNull(expired.nextManeuverMeters)
    }
    @Test fun loadingOffRouteAndStaleStateDoNotRetainOldTurn() {
        listOf(state().copy(loading = true), state().copy(gpsStale = true),
            state().copy(progress = state().progress!!.copy(offRoute = true))).forEach {
            val value = requireNotNull(WearNavigationFactory.create(it, now))
            assertEquals("", value.instruction)
            assertNull(value.maneuverType)
            assertNull(value.arrivalAt)
        }
    }
    @Test fun inactiveStateClearsNavigationAndArrivedNeverRetainsTurn() {
        assertNull(WearNavigationFactory.create(NavigationState(), now))
        assertNull(WearNavigationFactory.create(state().copy(guidance = false), now))
        val arrived = requireNotNull(WearNavigationFactory.create(state().copy(arrived = true, guidance = false), now))
        assertTrue(arrived.arrived)
        assertEquals("", arrived.instruction)
        assertNull(arrived.nextManeuverMeters)
    }
    @Test fun futureOrMissingFixAndInvalidProgressAreUnavailable() {
        listOf(state().copy(fix = null), state().copy(fix = state().fix!!.copy(recordedAt = now + 6000)),
            state().copy(progress = state().progress!!.copy(remainingSeconds = Double.NaN))).forEach {
            val value = requireNotNull(WearNavigationFactory.create(it, now))
            assertNull(value.arrivalAt)
            assertEquals("", value.instruction)
        }
    }
    @Test fun simulationIsExplicitAndWireTextsAreBounded() {
        val value = requireNotNull(WearNavigationFactory.create(state().copy(simulation = true,
            route = route.copy(maneuvers = route.maneuvers.map { it.copy(instruction = "ğ".repeat(1000)) })), now))
        assertTrue(value.simulation)
        assertTrue(value.instruction.toByteArray(Charsets.UTF_8).size <= 240)
    }
    @Test fun publishingIncludesGuidanceWithoutRecordingAndLastActiveTransition() {
        assertTrue(WearNavigationFactory.shouldPublish(false, true, false, false, 0))
        assertTrue(WearNavigationFactory.shouldPublish(false, false, false, true, 0))
        assertTrue(WearNavigationFactory.shouldPublish(false, false, true, false, 0))
        assertFalse(WearNavigationFactory.shouldPublish(false, false, false, false, 0))
    }
}
