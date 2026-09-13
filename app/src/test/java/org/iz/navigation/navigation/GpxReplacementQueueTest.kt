package org.iz.navigation.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpxReplacementQueueTest {
    private class Runtime(recording: Boolean = true) : NavigationRuntime {
        val existing = Journey(id = "record", transport = Transport.WALK, startedAt = 1000)
        override val journeys = MutableStateFlow(if (recording) listOf(existing) else emptyList())
        var release: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        var prepares = 0
        var starts = 0
        var finishes = 0
        override suspend fun activeJourney() = journeys.value.firstOrNull()
        override fun recordingId() = journeys.value.firstOrNull()?.id
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String { starts++; error("Unexpected recording start") }
        override suspend fun confirm(id: String, transport: Transport) = Unit
        override suspend fun finish(id: String) { finishes++ }
        override suspend fun interrupt(id: String) { finishes++ }
        override fun setHighFrequency(journeyId: String?) = Unit
        override suspend fun prepareTrackFollowSession(transport: Transport, stillCurrent: () -> Boolean,
            onDiscard: (Journey) -> Unit, commit: (Journey?) -> Unit) {
            prepares++
            entered?.complete(Unit)
            release?.await()
            check(stillCurrent())
            commit(activeJourney())
        }
        override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute = error("No planning")
        override suspend fun locate() = NavigationFix(WeatherCoordinate(0.0, 0.0), 1000, 5f)
        override fun render(state: NavigationState) = Unit
        override fun speak(text: String) = Unit
        override fun cancelSpeech() = Unit
    }

    private fun track(id: String) = ImportedTrack(id = id, name = id, segments = listOf(
        TrackSegment("Part", listOf(WeatherCoordinate(0.0, 0.0), WeatherCoordinate(0.0, .01)))))
    private fun route(id: String) = PlannedRoute(id,
        listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .01))),
        listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.0, .01), 100.0)),
        1110.0, 100.0, 1000L, transport = Transport.WALK, hasHighway = false,
        maneuvers = listOf(RouteManeuver(1, "Continue", "Continue", emptyList(), 0, 1, 0.0, 100.0)))

    @Test fun queuedConfirmationCannotReplaceTheSessionThatCommittedAheadOfIt() = runTest {
        val runtime = Runtime()
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        owner.startTrackFollow(track("a"), TrackFollowSelection(), Transport.WALK)
        val approved = owner.state.value.navigationReplacementKey()
        runtime.release = CompletableDeferred()
        runtime.entered = CompletableDeferred()
        val ahead = async { owner.startTrackFollow(track("b"), TrackFollowSelection(), Transport.WALK, true) }
        runtime.entered!!.await()
        val stale = async { runCatching {
            owner.startTrackFollow(track("c"), TrackFollowSelection(), Transport.WALK, true, approved)
        } }
        runCurrent()
        assertFalse(stale.isCompleted)
        runtime.release!!.complete(Unit)
        ahead.await()
        assertTrue(stale.await().isFailure)
        assertEquals("b", owner.state.value.trackFollow?.track?.id)
        assertEquals(2, runtime.prepares)
        assertEquals("record", owner.state.value.journey?.id)
        assertEquals(0, runtime.starts + runtime.finishes)
    }

    @Test fun onlineRouteIdentityIsPartOfTheApprovedGpxReplacement() = runTest {
        val runtime = Runtime(false)
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        owner.startGuidance(route("online-a"), recordJourney = false)
        val approved = owner.state.value.navigationReplacementKey()
        owner.startGuidance(route("online-b"), recordJourney = false)
        assertTrue(runCatching {
            owner.startTrackFollow(track("c"), TrackFollowSelection(), Transport.WALK, true, approved)
        }.isFailure)
        assertEquals("online-b", owner.state.value.route?.id)
        assertNull(owner.state.value.trackFollow)
        assertEquals(0, runtime.prepares + runtime.starts + runtime.finishes)
    }

    @Test fun confirmedSameKeyReplacesOnlyTheSelectedTrackAndPreservesRecording() = runTest {
        val runtime = Runtime()
        val owner = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val session = owner.startTrackFollow(track("a"), TrackFollowSelection(), Transport.WALK)
        owner.startTrackFollow(track("b"), TrackFollowSelection(), Transport.WALK, true,
            owner.state.value.navigationReplacementKey())
        assertEquals(session, owner.state.value.sessionId)
        assertEquals("b", owner.state.value.trackFollow?.track?.id)
        assertEquals("record", owner.state.value.journey?.id)
        assertEquals(0, runtime.starts + runtime.finishes)
    }
}
