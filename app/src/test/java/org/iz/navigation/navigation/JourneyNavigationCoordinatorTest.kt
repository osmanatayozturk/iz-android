package org.iz.navigation.navigation

import org.iz.navigation.data.*
import org.iz.navigation.weather.*
import org.iz.navigation.gpx.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyNavigationCoordinatorTest {
    private class Runtime : NavigationRuntime {
        override val journeys = MutableStateFlow<List<Journey>>(emptyList())
        var started = 0; var finished = 0; var confirmed = 0
        var running: String? = null
        var demand: String? = null
        var activeCalls = 0
        var onActive: (suspend (Int) -> Unit)? = null
        var onConfirmed: (suspend () -> Unit)? = null
        var onInterrupted: (suspend () -> Unit)? = null
        var authoritativeJourney: Journey? = null
        var preparing = false
        var renderFailure = false
        var speechFailure: RuntimeException? = null
        var planCalls = 0
        var onPlan: (suspend (List<RouteStop>, Transport) -> PlannedRoute)? = null
        override suspend fun activeJourney(): Journey? {
            activeCalls++; onActive?.invoke(activeCalls)
            return authoritativeJourney ?: journeys.value.firstOrNull { it.endedAt == null }
        }
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
            check(stillCurrent())
            started++
            val trip = Journey(id = "trip-$started", transport = transport, startedAt = 1000)
            journeys.value = listOf(trip); running = trip.id
            return trip.id
        }
        override suspend fun confirm(id: String, transport: Transport) {
            confirmed++; journeys.value = journeys.value.map { it.copy(status = JourneyStatus.CONFIRMED, transport = transport) }
            onConfirmed?.invoke()
        }
        override suspend fun finish(id: String) { finished++; journeys.value = journeys.value.map { it.copy(endedAt = 2000) }; running = null }
        override suspend fun interrupt(id: String) { journeys.value = journeys.value.map { it.copy(interrupted = true, endedAt = 2000) }; running = null; onInterrupted?.invoke() }
        override fun setPreparingNoRecord(enabled: Boolean) { preparing = enabled }
        override fun recordingId() = running
        override fun setHighFrequency(journeyId: String?) { demand = journeyId }
        override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute {
            planCalls++
            return requireNotNull(onPlan) { "Not used" }(stops, transport)
        }
        override suspend fun locate() = NavigationFix(WeatherCoordinate(40.0, 29.0), 1000, 5f)
        override fun render(state: NavigationState) { if (renderFailure) throw IllegalStateException("Disconnected display") }
        override fun speak(text: String) = Unit
        override fun cancelSpeech() { speechFailure?.let { throw it } }
    }
    private fun route() = PlannedRoute("r", listOf(RouteStop("A", WeatherCoordinate(40.0, 29.0)), RouteStop("B", WeatherCoordinate(40.01, 29.0))),
        listOf(RouteVertex(WeatherCoordinate(40.0, 29.0), 0.0), RouteVertex(WeatherCoordinate(40.01, 29.0), 100.0)),
        1110.0, 100.0, 1000, listOf(0.0,100.0), Transport.CAR,
        maneuvers = listOf(RouteManeuver(1,"Kuzeye ilerleyin","Kuzeye ilerleyin", emptyList(),0,1,0.0,100.0)))

    @Test fun displayFailureCannotAbortRecordingOrLaterSessionCommands() = runTest {
        val runtime = Runtime().also { it.renderFailure = true }
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val id = controller.startGuidance(route())
        assertTrue(controller.state.value.recording)
        assertTrue(controller.state.value.guidance)
        controller.finishSession(id)
        assertNull(controller.state.value.sessionId)
        assertEquals(1, runtime.finished)
    }

    @Test fun guidanceWithoutRecordingNeverCreatesDiaryAndAcceptsSessionFixes() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val id = controller.startGuidance(route(), recordJourney = false)
        runCurrent()
        assertEquals(0, runtime.started)
        assertNull(controller.state.value.journey)
        assertEquals(id, controller.state.value.sessionId)
        assertTrue(controller.state.value.guidance)
        assertFalse(controller.state.value.recording)
        controller.onAcceptedLocation(id, NavigationFix(route().stops.first().coordinate, 1000, 5f))
        while (controller.state.value.fix == null) yield()
        assertFalse(controller.state.value.gpsStale)
    }

    @Test fun addingAndStoppingRecordingKeepsSessionRouteAndSharing() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route(), recordJourney = false)
        controller.setSharingLocation(true)
        assertEquals(session, controller.startFreeDrive(Transport.CAR))
        val journey = controller.state.value.journey!!.id
        assertNotEquals(session, journey)
        controller.stopRecording(journey)
        runCurrent()
        assertEquals(session, controller.state.value.sessionId)
        assertTrue(controller.state.value.guidance)
        assertTrue(controller.state.value.sharingLocation)
        assertNull(controller.state.value.journey)
        assertFalse(controller.state.value.recording)
        controller.finishSession(session)
        runCurrent()
        assertNull(controller.state.value.sessionId)
        assertFalse(controller.state.value.sharingLocation)
        assertFalse(controller.state.value.guidance)
    }

    @Test fun guidanceCanStopWhileGroupKeepsSessionThenLastDemandClosesIt() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route(), recordJourney = false)
        controller.setSharingLocation(true)
        controller.stopGuidance(); runCurrent()
        assertEquals(session, controller.state.value.sessionId)
        assertNull(controller.state.value.route)
        assertTrue(controller.state.value.sharingLocation)
        controller.setSharingLocation(false)
        assertNull(controller.state.value.sessionId)
    }

    @Test fun guardedStopAwaitsGuidanceRemovalAndPreservesRecordingAndSharing() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route())
        controller.setSharingLocation(true)
        val before = controller.state.value
        assertTrue(controller.stopGuidance(session, before.routeRevision))
        val stopped = controller.state.value
        assertFalse(stopped.guidance)
        assertNull(stopped.route)
        assertEquals(session, stopped.sessionId)
        assertEquals(before.journey, stopped.journey)
        assertTrue(stopped.recording)
        assertTrue(stopped.sharingLocation)
        assertEquals(before.journey!!.id, runtime.running)
        assertEquals(0, runtime.finished)
        assertNull(runtime.demand)
    }

    @Test fun guardedStopReleasesSessionWhenGuidanceWasItsLastDemand() = runTest {
        val controller = JourneyNavigationCoordinator(Runtime(), null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route(), recordJourney = false)
        assertTrue(controller.stopGuidance(session, controller.state.value.routeRevision))
        assertNull(controller.state.value.sessionId)
        assertFalse(controller.state.value.guidance)
        assertFalse(controller.state.value.recording)
    }

    @Test fun guardedStopKeepsGroupOnlySessionUntilSharingEnds() = runTest {
        val controller = JourneyNavigationCoordinator(Runtime(), null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route(), recordJourney = false)
        controller.setSharingLocation(true)
        assertTrue(controller.stopGuidance(session, controller.state.value.routeRevision))
        assertNull(controller.state.value.route)
        assertEquals(session, controller.state.value.sessionId)
        assertTrue(controller.state.value.sharingLocation)
        assertFalse(controller.state.value.recording)
        controller.setSharingLocation(false, session)
        assertNull(controller.state.value.sessionId)
    }

    @Test fun guardedStopRejectsAnotherSessionWithoutChangingCurrentGuidance() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startGuidance(route())
        val before = controller.state.value
        assertFalse(controller.stopGuidance("another-session", before.routeRevision))
        assertSame(before, controller.state.value)
        assertEquals(before.journey!!.id, runtime.demand)
        assertEquals(0, runtime.finished)
    }

    @Test fun guardedStopRejectsOldRevisionAfterRouteReplacementInSameSession() = runTest {
        val controller = JourneyNavigationCoordinator(Runtime(), null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route())
        val oldRevision = controller.state.value.routeRevision
        assertEquals(session, controller.startGuidance(route().copy(id = "replacement")))
        val before = controller.state.value
        assertNotEquals(oldRevision, before.routeRevision)
        assertFalse(controller.stopGuidance(session, oldRevision))
        assertSame(before, controller.state.value)
        assertEquals("replacement", controller.state.value.route!!.id)
    }

    @Test fun guardedStopChecksIdentityAfterQueuedRouteReplacementCompletes() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route())
        runCurrent()
        val oldRevision = controller.state.value.routeRevision
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runtime.onActive = { entered.complete(Unit); release.await() }
        val replacement = async { runCatching { controller.startGuidance(route().copy(id = "new-route")) } }
        entered.await()
        val stop = async { controller.stopGuidance(session, oldRevision) }
        runCurrent()
        val queued = !stop.isCompleted
        release.complete(Unit)
        assertEquals(session, replacement.await().getOrThrow())
        assertFalse(stop.await())
        assertTrue("Stop must await the command holding the route lock", queued)
        assertTrue(controller.state.value.guidance)
        assertEquals("new-route", controller.state.value.route!!.id)
        assertNotEquals(oldRevision, controller.state.value.routeRevision)
    }

    @Test fun guardedStopRejectsIdleCoordinatorWithoutMutation() = runTest {
        val controller = JourneyNavigationCoordinator(Runtime(), null, {1000L}, backgroundScope)
        val before = controller.state.value
        assertFalse(controller.stopGuidance("closed-session", before.routeRevision))
        assertSame(before, controller.state.value)
    }

    @Test fun guardedStopRejectsActiveRouteWithoutGuidance() = runTest {
        val controller = JourneyNavigationCoordinator(Runtime(), null, {1000L}, backgroundScope)
        val session = controller.activateRoute(route())
        val before = controller.state.value
        assertFalse(before.guidance)
        assertFalse(controller.stopGuidance(session, before.routeRevision))
        assertSame(before, controller.state.value)
        assertNotNull(controller.state.value.route)
        assertTrue(controller.state.value.recording)
    }

    @Test fun guardedStopCannotEndGpxFollowing() = runTest {
        val controller = JourneyNavigationCoordinator(Runtime(), null, {1000L}, backgroundScope)
        val track = ImportedTrack("synthetic", "Synthetic track", listOf(TrackSegment("A", listOf(
            WeatherCoordinate(40.0, 29.0), WeatherCoordinate(40.01, 29.0)))), 1)
        val session = controller.startTrackFollow(track, TrackFollowSelection(), Transport.CAR)
        val before = controller.state.value
        assertFalse(controller.stopGuidance(session, before.routeRevision))
        assertSame(before, controller.state.value)
        assertEquals(track.id, controller.state.value.trackFollow!!.track.id)
    }

    @Test fun guardedStopQueuedDuringGpxStartCannotClearTheNewFollowSession() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route())
        runCurrent()
        val oldRevision = controller.state.value.routeRevision
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runtime.onActive = { entered.complete(Unit); release.await() }
        val track = ImportedTrack("synthetic", "Synthetic track", listOf(TrackSegment("A", listOf(
            WeatherCoordinate(40.0, 29.0), WeatherCoordinate(40.01, 29.0)))), 1)
        val activation = async { controller.startTrackFollow(track, TrackFollowSelection(), Transport.CAR, replaceExisting = true) }
        entered.await()
        val stop = async { controller.stopGuidance(session, oldRevision) }
        runCurrent()
        val queued = !stop.isCompleted
        release.complete(Unit)
        assertEquals(session, activation.await())
        assertFalse(stop.await())
        assertTrue(queued)
        assertEquals(track.id, controller.state.value.trackFollow!!.track.id)
        assertTrue(controller.state.value.recording)
        assertEquals(0, runtime.finished)
    }

    @Test fun guardedStopPropagatesRuntimeFailureToItsAwaitingCaller() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route())
        val failure = IllegalStateException("Synthetic speech stop failure")
        runtime.speechFailure = failure
        val result = runCatching { controller.stopGuidance(session, controller.state.value.routeRevision) }
        runtime.speechFailure = null
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun sharingRequiresLiveSessionAndSimulationCannotShare() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        assertTrue(runCatching { controller.setSharingLocation(true) }.isFailure)
        controller.enableSimulation(); controller.startGuidance(route())
        assertTrue(runCatching { controller.setSharingLocation(true) }.isFailure)
        assertFalse(controller.state.value.sharingLocation)
    }

    @Test fun oldSessionFinishAndFixCannotChangeReplacement() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val old = controller.startGuidance(route(), recordJourney = false)
        controller.finishSession(old)
        val current = controller.startGuidance(route(), recordJourney = false)
        controller.finishSession(old)
        controller.onAcceptedLocation(old, NavigationFix(route().stops.first().coordinate, 1000, 5f))
        runCurrent()
        assertEquals(current, controller.state.value.sessionId)
        assertTrue(controller.state.value.guidance)
        assertNull(controller.state.value.fix)
    }

    @Test fun recordingOffRejectsExistingConfirmedRecordWithoutEndingIt() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startFreeDrive(Transport.CAR)
        assertTrue(runCatching { controller.startGuidance(route(), recordJourney = false) }.isFailure)
        assertTrue(controller.state.value.recording)
        assertEquals(0, runtime.finished)
    }

    @Test fun noRecordStartupSuppressesDetectionAcrossAsyncReadsAndReleasesGuardOnFailure() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        runtime.onActive = {
            assertTrue(runtime.preparing)
            yield()
        }
        controller.startFreeDrive(Transport.CAR, recordJourney = false)
        assertFalse(runtime.preparing)
        runtime.onActive = null
        controller.finishSession(controller.state.value.sessionId!!)
        controller.startFreeDrive(Transport.CAR)
        assertTrue(runCatching { controller.startGuidance(route(), recordJourney = false) }.isFailure)
        assertFalse(runtime.preparing)
    }

    @Test fun delayedShareReleaseCannotDisableReplacementSession() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val old = controller.startGuidance(route(), recordJourney = false)
        controller.setSharingLocation(true)
        controller.finishSession(old)
        val current = controller.startGuidance(route(), recordJourney = false)
        controller.setSharingLocation(true)
        controller.setSharingLocation(false, expectedSessionId = old)
        assertEquals(current, controller.state.value.sessionId)
        assertTrue(controller.state.value.sharingLocation)
    }

    @Test fun explicitExternalRecordingAttachesToCompatibleNoRecordSession() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val session = controller.startGuidance(route(), recordJourney = false)
        runCurrent()
        val diary = runtime.start(Transport.CAR)
        runCurrent()
        assertEquals(session, controller.state.value.sessionId)
        assertEquals(diary, controller.state.value.journey?.id)
        assertTrue(controller.state.value.recording)
        assertTrue(controller.state.value.guidance)
        controller.setSharingLocation(true)
        runtime.finish(diary)
        runCurrent()
        assertNull(controller.state.value.sessionId)
        assertFalse(controller.state.value.sharingLocation)
    }

    @Test fun observedOrphanCanStartDifferentTransport() = runTest {
        val runtime = Runtime()
        runtime.journeys.value = listOf(Journey(id = "orphan", transport = Transport.CAR, startedAt = 1))
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        runCurrent()
        val id = controller.startFreeDrive(Transport.WALK)
        assertEquals("trip-1", id)
        assertEquals(Transport.WALK, controller.state.value.sessionTransport)
        assertTrue(controller.state.value.recording)
    }

    @Test fun observedOrphanRecoverySurvivesRoomEmissionDuringInterruption() = runTest {
        val runtime = Runtime()
        runtime.journeys.value = listOf(Journey(id = "orphan", transport = Transport.CAR, startedAt = 1))
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        runCurrent()
        runtime.onInterrupted = { yield() }
        val id = controller.startFreeDrive(Transport.CAR)
        assertEquals("trip-1", id)
        assertEquals(id, controller.state.value.journey?.id)
        assertTrue(controller.state.value.recording)
    }

    @Test fun orphanedConfirmedRecordIsInterruptedBeforeExplicitStart() = runTest {
        val runtime = Runtime()
        runtime.journeys.value = listOf(Journey(id = "orphan", transport = Transport.CAR, startedAt = 1))
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val id = controller.startFreeDrive(Transport.CAR)
        assertNotEquals("orphan", id)
        assertEquals(1, runtime.started)
        assertTrue(controller.state.value.recording)
        assertEquals(id, runtime.running)
    }

    @Test fun concurrentFreeStartsReuseOneRecording() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        val ids = awaitAll(async { controller.startFreeDrive(Transport.CAR) }, async { controller.startFreeDrive(Transport.CAR) })
        assertEquals(listOf("trip-1", "trip-1"), ids); assertEquals(1, runtime.started)
        assertTrue(controller.state.value.recording); assertNull(controller.state.value.route)
    }
    @Test fun stopGuidanceDoesNotFinishTripAndDropsGpsDemand() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startGuidance(route()); runCurrent()
        assertEquals("trip-1", runtime.demand)
        controller.stopGuidance(); runCurrent()
        assertTrue(controller.state.value.recording); assertFalse(controller.state.value.guidance)
        assertEquals(0, runtime.finished); assertNull(runtime.demand)
    }
    @Test fun incompatibleTripIsNotReplaced() = runTest {
        val runtime = Runtime()
        runtime.start(Transport.MOTORCYCLE)
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        assertTrue(runCatching { controller.startGuidance(route()) }.isFailure)
        assertEquals(1, runtime.started); assertEquals(0, runtime.finished)
    }
    @Test fun expectedJourneyIdPreventsFinishingNewTrip() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startFreeDrive(Transport.CAR)
        controller.finishJourney("old-trip")
        assertEquals(0, runtime.finished); assertEquals("trip-1", runtime.running)
    }
    @Test fun simulationDoesNotCreateRealTripOrGpsDemand() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.enableSimulation()
        controller.startGuidance(route()); runCurrent()
        assertTrue(controller.state.value.simulation)
        assertEquals(0, runtime.started); assertNull(runtime.demand)
        controller.stopSimulation(); runCurrent()
        assertFalse(controller.state.value.simulation); assertEquals(0, runtime.finished)
    }
    @Test fun externalFinishClearsRouteAndSpeechState() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startGuidance(route()); runCurrent()
        runtime.finish("trip-1"); runCurrent()
        assertNull(controller.state.value.route); assertFalse(controller.state.value.guidance)
        assertNull(runtime.demand)
    }
    @Test fun explicitStartConfirmsExistingAutomaticCandidate() = runTest {
        val runtime = Runtime(); runtime.start(Transport.CAR)
        runtime.journeys.value = runtime.journeys.value.map { it.copy(status = JourneyStatus.TEMPORARY) }
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startFreeDrive(Transport.CAR)
        assertEquals(1, runtime.started); assertEquals(1, runtime.confirmed)
        assertEquals(JourneyStatus.CONFIRMED, controller.state.value.journey?.status)
    }
    @Test fun stopDuringFinalJourneyReadCannotResurrectGuidance() = runTest {
        val runtime = Runtime(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        runtime.onActive = { if (it == 2) { entered.complete(Unit); release.await() } }
        val activation = async { runCatching { controller.startGuidance(route()) } }
        entered.await(); controller.stopGuidance(); runCurrent(); release.complete(Unit)
        assertTrue(activation.await().isFailure); assertFalse(controller.state.value.guidance)
    }
    @Test fun restoreDuringInitialReadCannotCreateNewRecording() = runTest {
        val runtime = Runtime(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        runtime.onActive = { if (it == 1) { entered.complete(Unit); release.await() } }
        val activation = async { runCatching { controller.startGuidance(route()) } }
        entered.await(); controller.invalidateAfterDiaryReplacement(); runCurrent(); release.complete(Unit)
        assertTrue(activation.await().isFailure); assertEquals(0, runtime.started)
    }
    @Test fun weatherActivationCannotLeakSimulationIntoRealDiary() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.enableSimulation(); controller.startGuidance(route())
        assertTrue(runCatching { controller.activateRoute(route()) }.isFailure)
        assertEquals(0, runtime.started); assertTrue(controller.state.value.simulation)
    }
    @Test fun confirmingUnknownCandidateCanStartGuidanceWhileObserverRuns() = runTest {
        val runtime = Runtime(); runtime.start(Transport.UNKNOWN)
        runtime.journeys.value = runtime.journeys.value.map { it.copy(status = JourneyStatus.TEMPORARY) }
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        runCurrent()
        runtime.onConfirmed = { yield() }
        controller.startGuidance(route())
        runCurrent()
        assertTrue(controller.state.value.guidance)
        assertEquals(Transport.CAR, controller.state.value.journey?.transport)
        assertEquals(1, runtime.started); assertEquals(1, runtime.confirmed)
    }
    @Test fun staleCandidateSnapshotCannotClearNewlyConfirmedGuidance() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startGuidance(route()); runCurrent()
        runtime.authoritativeJourney = controller.state.value.journey
        runtime.journeys.value = runtime.journeys.value.map { it.copy(status = JourneyStatus.TEMPORARY, transport = Transport.UNKNOWN) }
        runCurrent()
        assertTrue(controller.state.value.guidance)
        assertEquals(JourneyStatus.CONFIRMED, controller.state.value.journey?.status)
    }
    @Test fun actualTransportChangeClearsIncompatibleGuidance() = runTest {
        val runtime = Runtime()
        val controller = JourneyNavigationCoordinator(runtime, null, {1000L}, backgroundScope)
        controller.startGuidance(route()); runCurrent()
        runtime.journeys.value = runtime.journeys.value.map { it.copy(transport = Transport.WALK) }
        runCurrent()
        assertFalse(controller.state.value.guidance)
        assertNull(controller.state.value.route)
        assertTrue(controller.state.value.recording)
    }

    @Test fun activeTrafficRefreshesAfterTwoMinutesWithoutStartingAnotherJourney() = runTest {
        val runtime = Runtime()
        val now = { 1000L + testScheduler.currentTime }
        val controller = JourneyNavigationCoordinator(runtime, null, now, backgroundScope)
        val trafficRoute = route().copy(provider = RouteProvider.TOMTOM,
            traffic = RouteTrafficInfo(1000, 12.0, 88.0))
        runtime.onPlan = { _, _ -> trafficRoute.copy(id = "updated", traffic = trafficRoute.traffic!!.copy(fetchedAt = now())) }
        controller.startGuidance(trafficRoute)
        runCurrent()
        advanceTimeBy(119_000)
        controller.onAcceptedLocation("trip-1", NavigationFix(trafficRoute.stops.first().coordinate, now(), 5f))
        while (controller.state.value.fix == null) yield()
        runCurrent()
        assertEquals(0, runtime.planCalls)
        advanceTimeBy(1001)
        while (controller.state.value.loading) yield()
        runCurrent()
        assertEquals(1, runtime.planCalls)
        assertEquals(1, runtime.started)
        assertTrue(controller.state.value.guidance)
        assertEquals("trip-1", controller.state.value.journey?.id)
        controller.stopGuidance(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, runtime.planCalls)
    }
}
