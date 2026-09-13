package org.iz.navigation.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.yield
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPreferenceGateTest {
    private class Runtime : NavigationRuntime {
        override val journeys = MutableStateFlow<List<Journey>>(emptyList())
        var starts = 0
        var planned: PlannedRoute? = null
        var requestedPreferences: RoutePreferences? = null
        var requestedSpeed: Double? = null
        private var running: String? = null
        override suspend fun activeJourney() = journeys.value.firstOrNull { it.endedAt == null }
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
            check(stillCurrent()); starts++
            val journey = Journey(id = "trip-$starts", transport = transport, startedAt = 1000)
            journeys.value = listOf(journey); running = journey.id
            return journey.id
        }
        override suspend fun confirm(id: String, transport: Transport) = Unit
        override suspend fun finish(id: String) { journeys.value = emptyList(); running = null }
        override suspend fun interrupt(id: String) = finish(id)
        override fun recordingId() = running
        override fun setHighFrequency(journeyId: String?) = Unit
        override suspend fun plan(stops: List<RouteStop>, transport: Transport) = requireNotNull(planned)
        override suspend fun plan(stops: List<RouteStop>, transport: Transport, preferences: RoutePreferences,
            travelSpeedKmh: Double?): PlannedRoute {
            requestedPreferences = preferences
            requestedSpeed = travelSpeedKmh
            return requireNotNull(planned)
        }
        override fun trafficRefreshEnabled(route: PlannedRoute) = true
        override suspend fun locate() = NavigationFix(WeatherCoordinate(40.0, 29.0), 1000, 4f)
        override fun render(state: NavigationState) = Unit
        override fun speak(text: String) = Unit
        override fun cancelSpeech() = Unit
    }
    private fun route(mode: Transport, proof: Boolean? = null, avoid: Boolean = true, id: String = "route") =
        PlannedRoute(id, listOf(RouteStop("A", WeatherCoordinate(40.0, 29.0)), RouteStop("B", WeatherCoordinate(40.01, 29.0))),
            listOf(RouteVertex(WeatherCoordinate(40.0, 29.0), 0.0), RouteVertex(WeatherCoordinate(40.01, 29.0), 100.0)),
            1110.0, 100.0, 1000, listOf(0.0, 100.0), mode,
            maneuvers = listOf(RouteManeuver(1, "Kuzeye ilerle", "Kuzeye ilerle", emptyList(), 0, 1, 0.0, 100.0)),
            preferences = RoutePreferences(avoidHighways = avoid), hasHighway = proof)

    @Test fun unprovenOrHighwayActiveModeCannotCreateRecordingThroughAnyEntryPoint() = runTest {
        for (mode in listOf(Transport.WALK, Transport.RUN, Transport.BICYCLE)) {
            for (proof in listOf(null, true)) {
                val runtime = Runtime()
                val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
                runCurrent()
                assertTrue(runCatching { owner.startGuidance(route(mode, proof)) }.exceptionOrNull() is RouteServiceException)
                assertTrue(runCatching { owner.activateRoute(route(mode, proof)) }.exceptionOrNull() is RouteServiceException)
                assertEquals(0, runtime.starts)
                assertNull(owner.state.value.sessionId)
            }
        }
    }

    @Test fun missingProofCannotReplaceTheAlreadyWorkingWalkingRoute() = runTest {
        val runtime = Runtime()
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        owner.startGuidance(route(Transport.WALK, false, id = "safe"), recordJourney = false)
        runCurrent()
        val before = owner.state.value
        assertTrue(runCatching { owner.startGuidance(route(Transport.WALK, id = "unknown"), recordJourney = false) }
            .exceptionOrNull() is RouteServiceException)
        assertEquals(before.sessionId, owner.state.value.sessionId)
        assertEquals("safe", owner.state.value.route?.id)
        assertEquals(0, runtime.starts)
    }

    @Test fun sharedPreviewRejectsUnknownHighwayProofBeforeAnySessionStarts() = runTest {
        val runtime = Runtime().also { it.planned = route(Transport.BICYCLE) }
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        assertTrue(runCatching { owner.previewRoute(runtime.planned!!.stops, Transport.BICYCLE) }
            .exceptionOrNull() is RouteServiceException)
        assertFalse(owner.state.value.loading)
        assertNull(owner.state.value.route)
        assertEquals(0, runtime.starts)
    }

    @Test fun explicitlyDisabledAvoidanceDoesNotRequireHighwayProof() = runTest {
        val runtime = Runtime()
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        owner.startGuidance(route(Transport.WALK, avoid = false), recordJourney = false)
        assertTrue(owner.state.value.guidance)
        assertFalse(owner.state.value.recording)
    }

    @Test fun staleTrackConfirmationCannotReplaceTheNewTrackOrStartRecording() = runTest {
        val runtime = Runtime()
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val track = ImportedTrack(id = "track-a", name = "Sentetik", segments = listOf(
            TrackSegment("Bölüm", listOf(WeatherCoordinate(40.0, 29.0), WeatherCoordinate(40.01, 29.0)))))
        owner.startTrackFollow(track, TrackFollowSelection(), Transport.WALK)
        val confirmedKey = owner.state.value.trackReplacementKey()
        owner.startTrackFollow(track.copy(id = "track-b"), TrackFollowSelection(), Transport.WALK, replaceExisting = true)
        val before = owner.state.value
        assertTrue(runCatching {
            owner.startGuidance(route(Transport.WALK, false), replaceTrackFollow = true, expectedTrackFollowKey = confirmedKey)
        }.isFailure)
        assertEquals(before.sessionId, owner.state.value.sessionId)
        assertEquals("track-b", owner.state.value.trackFollow?.track?.id)
        assertEquals(0, runtime.starts)
    }

    @Test fun trafficRefreshKeepsTheSelectedModesPreferencesAndPersonalSpeed() = runTest {
        val runtime = Runtime()
        val now = { 1000L + testScheduler.currentTime }
        val owner = JourneyNavigationCoordinator(runtime, null, now, backgroundScope)
        val initial = route(Transport.BICYCLE, false, id = "selected").copy(
            preferences = RoutePreferences(avoidHighways = true, avoidFerries = true), travelSpeedKmh = 18.0)
        runtime.planned = initial.copy(id = "refreshed")
        owner.startGuidance(initial)
        runCurrent()
        advanceTimeBy(119_000)
        owner.onAcceptedLocation("trip-1", NavigationFix(initial.stops.first().coordinate, now(), 5f))
        while (owner.state.value.fix == null) yield()
        runCurrent()
        advanceTimeBy(1001)
        while (owner.state.value.loading) yield()
        runCurrent()
        assertEquals(RoutePreferences(avoidHighways = true, avoidFerries = true), runtime.requestedPreferences)
        assertEquals(18.0, runtime.requestedSpeed!!, 0.0)
        assertEquals("refreshed", owner.state.value.route?.id)
        assertEquals(1, runtime.starts)
    }
}
