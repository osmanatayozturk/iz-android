package org.iz.navigation.car

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.RoadSpeedLimit
import org.iz.navigation.speed.RoadSpeedSource
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class CarMapPresentationTest {
    private val now = 100_000L
    private val fix = NavigationFix(WeatherCoordinate(41.0, 29.0), now, 5f, 10f)
    private val speed = RoadSpeedState(36.0, RoadSpeedLimit(50.0, RoadSpeedSource.OSM, false,
        now, fix.coordinate, Transport.CAR, "road"), true)

    @Test fun onlyVisibleIdleMapRequestsItsOwnLocation() {
        assertTrue(carMapLocationVisible(true, true, true, NavigationState()))
        assertFalse(carMapLocationVisible(false, true, true, NavigationState()))
        assertFalse(carMapLocationVisible(true, false, true, NavigationState()))
        assertFalse(carMapLocationVisible(true, true, false, NavigationState()))
        for (state in listOf(NavigationState(sessionId = "session"), NavigationState(recording = true),
            NavigationState(simulation = true), NavigationState(guidance = true))) {
            assertFalse(carMapLocationVisible(true, true, true, state))
        }
    }

    @Test fun idleMapUsesOnlyTheVisibleLocationAndClearsSpeedWhenItExpires() {
        val prior = NavigationState(fix = fix, gpsStale = false, roadSpeed = speed)
        val shown = carMapDisplayState(prior, fix, speed, now)
        assertEquals(fix, shown.fix)
        assertFalse(shown.gpsStale)
        assertEquals(36.0, shown.roadSpeed.ownKmh!!, 0.0)
        assertEquals(50.0, shown.roadSpeed.limit!!.valueKmh!!, 0.0)
        val oldSpeed = carMapDisplayState(prior, fix, speed, now + 5_001)
        assertEquals(fix, oldSpeed.fix)
        assertNull(oldSpeed.roadSpeed.ownKmh)
        assertNull(oldSpeed.roadSpeed.limit)
        for (value in listOf(carMapDisplayState(prior, null, speed, now),
            carMapDisplayState(prior, fix, speed, now + 10_001))) {
            assertNull(value.fix)
            assertTrue(value.gpsStale)
            assertNull(value.roadSpeed.ownKmh)
            assertNull(value.roadSpeed.limit)
        }
    }

    @Test fun recordingOrNavigationStateCannotBeOverwrittenByIdleMapFix() {
        val active = NavigationState(sessionId = "trip", recording = true, fix = fix,
            gpsStale = false, roadSpeed = speed)
        assertSame(active, carMapDisplayState(active, null, RoadSpeedState(), now))
        val simulation = active.copy(recording = false, simulation = true)
        assertSame(simulation, carMapDisplayState(simulation, null, speed, now))
    }

    @Test fun aNewFixNeverCarriesThePreviousRoadLimitWhileResolutionIsPending() {
        val next = fix.copy(coordinate = WeatherCoordinate(41.0001, 29.0), recordedAt = now + 1_000)
        val shown = carMapDisplayState(NavigationState(), next, speed, now + 1_000)
        assertEquals(next, shown.fix)
        assertEquals(36.0, shown.roadSpeed.ownKmh!!, 0.0)
        assertNull(shown.roadSpeed.limit)
        val timestampOnly = carMapDisplayState(NavigationState(), fix.copy(recordedAt = now + 1), speed, now + 1)
        assertNull(timestampOnly.roadSpeed.limit)
        val motorcycle = carMapDisplayState(NavigationState(), fix, speed, now, Transport.MOTORCYCLE)
        assertNull("A car-specific limit cannot be shown for another selected vehicle", motorcycle.roadSpeed.limit)
    }

    @Test fun recordingActionDistinguishesTemporaryFromConfirmedAndIdle() {
        assertEquals(CarRecordingAction.START, carRecordingAction(NavigationState()))
        assertEquals(CarRecordingAction.FINISH, carRecordingAction(NavigationState(recording = true,
            journey = Journey(transport = Transport.CAR))))
        assertEquals(CarRecordingAction.SAVE, carRecordingAction(NavigationState(recording = true,
            journey = Journey(transport = Transport.CAR, status = JourneyStatus.TEMPORARY))))
    }

    @Test fun menuRecordingRequestCannotRetargetAnotherSessionOrActiveGuidance() {
        val state = NavigationState(sessionId = "trip", routeRevision = 3,
            journey = Journey(id = "journey", transport = Transport.CAR), recording = true)
        val shown = carRecordingRequest(state, Transport.CAR)
        assertEquals(shown, carRecordingRequest(state.copy(fix = fix), Transport.CAR))
        assertNotEquals(shown, carRecordingRequest(state.copy(sessionId = "new"), Transport.CAR))
        assertNotEquals(shown, carRecordingRequest(state.copy(routeRevision = 4), Transport.CAR))
        assertNotEquals(shown, carRecordingRequest(state.copy(recording = false), Transport.CAR))
        assertNull(carRecordingRequest(state.copy(guidance = true), Transport.CAR))
    }
}
