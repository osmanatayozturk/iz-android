package org.iz.navigation.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.iz.navigation.data.*
import org.iz.navigation.gpx.*
import org.iz.navigation.weather.*
import org.iz.navigation.wear.WearNavigationFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TrackFollowSessionTest {
    private class Runtime : NavigationRuntime {
        override val journeys = MutableStateFlow<List<Journey>>(emptyList())
        var running: String? = null
        var session: String? = null
        var high = false
        var suppressed = false
        var preparing = false
        var pending: String? = null
        var actual: Journey? = null
        var read: (suspend () -> Unit)? = null
        var delivered = true
        var starts = 0; var confirms = 0; var finishes = 0; var discards = 0; var plans = 0
        override suspend fun activeJourney(): Journey? { read?.invoke(); return actual ?: journeys.value.firstOrNull { it.endedAt == null && !it.interrupted } }
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
            check(stillCurrent()); starts++
            return attach(Journey(id = "record-$starts", transport = transport, startedAt = 1000)).id
        }
        fun attach(journey: Journey): Journey { running = journey.id; journeys.value = listOf(journey); return journey }
        override suspend fun confirm(id: String, transport: Transport) { confirms++; journeys.value = journeys.value.map { it.copy(status = JourneyStatus.CONFIRMED, transport = transport) } }
        override suspend fun finish(id: String) { finishes++; running = null; journeys.value = emptyList() }
        override suspend fun discardCandidate(id: String) { discards++; running = null; journeys.value = emptyList() }
        override suspend fun interrupt(id: String) { running = null; journeys.value = emptyList() }
        override fun recordingId() = running
        override fun startPending(id: String) = pending == id
        override fun setHighFrequency(journeyId: String?) = Unit
        override fun setPreparingNoRecord(enabled: Boolean) { preparing = enabled }
        override fun setLocationSession(id: String?, transport: Transport?, highFrequency: Boolean, suppressAutomatic: Boolean) {
            session = id; high = highFrequency; suppressed = suppressAutomatic
        }
        override fun locationActive() = delivered && session != null
        override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute { plans++; return route() }
        override suspend fun locate() = NavigationFix(WeatherCoordinate(40.0, 29.0), 1000, 5f)
        override fun render(state: NavigationState) = Unit
        override fun speak(text: String) = Unit
        override fun cancelSpeech() = Unit
    }

    @Test fun noRecordTrackStartsOnlySessionDemandAndStopReleasesIt() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        assertEquals(id, runtime.session)
        assertTrue(runtime.high)
        assertTrue(runtime.suppressed)
        assertNotNull(coordinator.state.value.trackFollow)
        assertFalse(coordinator.state.value.recording)
        coordinator.stopTrackFollow()
        assertNull(runtime.session)
        assertEquals(0, runtime.starts + runtime.confirms + runtime.finishes)
    }

    @Test fun confirmedRecorderAndItsSessionSurviveTrackStop() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val session = coordinator.startFreeDrive(Transport.WALK, recordJourney = false)
        val record = runtime.attach(Journey(id = "existing", transport = Transport.WALK, startedAt = 1))
        runCurrent()
        assertEquals(session, coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK))
        assertEquals(record.id, coordinator.state.value.journey?.id)
        coordinator.stopTrackFollow()
        assertEquals(session, coordinator.state.value.sessionId)
        assertEquals(record.id, runtime.running)
        assertEquals(0, runtime.starts + runtime.confirms + runtime.finishes)
    }

    @Test fun stoppingRecordingKeepsTrackAndGps() = runTest {
        val runtime = Runtime()
        runtime.attach(Journey(id = "record", transport = Transport.WALK, startedAt = 1))
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        coordinator.stopRecording("record")
        runCurrent()
        assertEquals(id, coordinator.state.value.sessionId)
        assertNotNull(coordinator.state.value.trackFollow)
        assertTrue(runtime.high)
        assertTrue(runtime.suppressed)
        assertEquals(1, runtime.finishes)
    }

    @Test fun automaticCandidateIsDiscardedNeverConfirmed() = runTest {
        val runtime = Runtime()
        runtime.attach(Journey(id = "candidate", transport = Transport.UNKNOWN, status = JourneyStatus.TEMPORARY, startedAt = 1))
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        runCurrent()
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        assertNotEquals("candidate", id)
        assertNull(coordinator.state.value.journey)
        assertEquals(1, runtime.discards)
        assertEquals(0, runtime.confirms + runtime.starts + runtime.finishes)
    }

    @Test fun confirmedIncompatibleRecorderIsRefused() = runTest {
        val runtime = Runtime()
        runtime.attach(Journey(id = "record", transport = Transport.CAR, startedAt = 1))
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        assertTrue(runCatching { coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK, true) }.isFailure)
        assertEquals("record", runtime.running)
        assertEquals(0, runtime.finishes)
    }

    @Test fun activeRouteRequiresExplicitReplacementAndClearsAllTurnState() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val id = coordinator.startGuidance(route(), recordJourney = false)
        assertTrue(runCatching { coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK) }.isFailure)
        assertEquals(id, coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK, true))
        val state = coordinator.state.value
        assertNull(state.route); assertNull(state.progress)
        assertFalse(state.guidance || state.arrived || state.loading)
        assertNull(WearNavigationFactory.create(state, 1000))
        coordinator.stopGuidance(); runCurrent()
        assertNotNull(coordinator.state.value.trackFollow)
    }

    @Test fun replacingTrackRequiresApprovalAndKeepsSessionIdentity() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        assertTrue(runCatching { coordinator.startTrackFollow(track("replacement"), TrackFollowSelection(), Transport.WALK) }.isFailure)
        assertEquals("track", coordinator.state.value.trackFollow?.track?.id)
        assertEquals(id, coordinator.startTrackFollow(track("replacement"), TrackFollowSelection(), Transport.WALK, true))
        assertEquals("replacement", coordinator.state.value.trackFollow?.track?.id)
    }

    @Test fun sharingAndFreeDriveRemainAfterTrackStops() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        coordinator.setSharingLocation(true)
        coordinator.stopTrackFollow()
        assertEquals(id, runtime.session)
        coordinator.setSharingLocation(false)
        assertNull(runtime.session)
        val free = coordinator.startFreeDrive(Transport.WALK, false)
        coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        coordinator.stopTrackFollow()
        assertEquals(free, runtime.session)
    }

    @Test fun pendingManualStartCannotBeDiscardedOrBorrowed() = runTest {
        val runtime = Runtime()
        runtime.journeys.value = listOf(Journey(id = "pending", transport = Transport.WALK, startedAt = 1))
        runtime.pending = "pending"
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        assertTrue(runCatching { coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK) }.isFailure)
        assertEquals("pending", runtime.journeys.value.single().id)
        assertNull(runtime.session)
    }

    @Test fun oldRoadCommandsCannotReplaceActiveTrack() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        assertTrue(runCatching { coordinator.startGuidance(route(), false) }.isFailure)
        assertTrue(runCatching { coordinator.activateRoute(route()) }.isFailure)
        assertNotNull(coordinator.state.value.trackFollow)
        assertEquals(0, runtime.starts)
    }

    @Test fun stopDuringPreparationCannotResurrectGpsDemand() = runTest {
        val runtime = Runtime()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        runCurrent()
        runtime.read = { entered.complete(Unit); release.await() }
        val start = async { runCatching { coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK) } }
        while (!entered.isCompleted && !start.isCompleted) yield()
        assertTrue("Track startup must reach the runtime preparation before committing", entered.isCompleted)
        val stop = async { coordinator.stopTrackFollow() }
        runCurrent(); release.complete(Unit)
        assertTrue(start.await().isFailure)
        stop.await()
        assertNull(coordinator.state.value.trackFollow)
        assertNull(runtime.session)
    }

    @Test fun validFixUpdatesLineProgressAndOffTrackNeverRequestsRouting() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        coordinator.onAcceptedLocation(id, NavigationFix(WeatherCoordinate(40.5, 29.5), 1000, 5f))
        while (coordinator.state.value.fix == null) yield()
        assertEquals(TrackFollowStatus.OFF_TRACK, coordinator.state.value.trackFollow?.progress?.status)
        assertNull(coordinator.state.value.progress)
        assertNull(coordinator.state.value.route)
        assertEquals(0, runtime.plans)
        assertNull(WearNavigationFactory.create(coordinator.state.value, 1000))
    }

    @Test fun oldSessionFixAndFinishedRecorderSnapshotCannotChangeReplacement() = runTest {
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        val old = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        coordinator.stopTrackFollow()
        val current = coordinator.startTrackFollow(track("next"), TrackFollowSelection(), Transport.WALK)
        coordinator.onAcceptedLocation(old, NavigationFix(WeatherCoordinate(40.0, 29.0), 1000, 5f))
        runtime.journeys.value = listOf(Journey(id = "old-recorder", transport = Transport.CAR, startedAt = 1))
        runCurrent()
        assertEquals(current, coordinator.state.value.sessionId)
        assertEquals("next", coordinator.state.value.trackFollow?.track?.id)
        assertNull(coordinator.state.value.fix)
    }

    @Test fun staleRoomSnapshotCannotClearConfirmedTrackButRealModeChangeDoes() = runTest {
        val runtime = Runtime()
        val record = runtime.attach(Journey(id = "record", transport = Transport.WALK, startedAt = 1))
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        runCurrent()
        runtime.actual = record
        runtime.journeys.value = listOf(record.copy(status = JourneyStatus.TEMPORARY, transport = Transport.UNKNOWN))
        runCurrent()
        assertNotNull(coordinator.state.value.trackFollow)
        runtime.actual = null
        runtime.journeys.value = listOf(record.copy(transport = Transport.CAR))
        runCurrent()
        assertNull(coordinator.state.value.trackFollow)
        assertTrue(coordinator.state.value.recording)
    }

    @Test fun recorderDisappearanceClearsTrackAndNoRecordDeliveryFailureMarksStale() = runTest {
        val runtime = Runtime()
        runtime.attach(Journey(id = "record", transport = Transport.WALK, startedAt = 1))
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope)
        coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        runCurrent(); runtime.finish("record"); runCurrent()
        assertNull(coordinator.state.value.trackFollow)
        val id = coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        coordinator.onAcceptedLocation(id, NavigationFix(WeatherCoordinate(40.0, 29.0), 1000, 5f))
        while (coordinator.state.value.fix == null) yield()
        runtime.delivered = false
        coordinator.onTrackingStopped(null)
        runCurrent()
        assertTrue(coordinator.state.value.gpsStale)
        assertFalse(coordinator.state.value.locationActive)
        assertNotNull(coordinator.state.value.trackFollow)
    }

    @Test fun interruptedMarkerExposesWarningWithoutRestartingGpsOrRecording() = runTest {
        val store = object : TrackFollowSessionStore(File("unused")) { override fun read() = true }
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope, store)
        // Yield a real IO turn as marker hydration intentionally never blocks the UI thread.
        repeat(50) { runCurrent(); withContext(Dispatchers.IO) { Thread.sleep(2) } }
        assertTrue(coordinator.state.value.interruptedTrackFollow)
        assertNull(coordinator.state.value.trackFollow)
        assertNull(runtime.session)
        assertEquals(0, runtime.starts + runtime.confirms)
        coordinator.dismissInterruptedTrackFollow()
        assertFalse(coordinator.state.value.interruptedTrackFollow)
    }

    @Test fun lateMarkerWriteCannotResurrectStoppedTrack() = runTest {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cleared = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = object : TrackFollowSessionStore(File("unused")) {
            override fun read() = active.get()
            override fun write() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); active.set(true) }
            override fun clear() { active.set(false); cleared.countDown() }
        }
        val coordinator = JourneyNavigationCoordinator(Runtime(), null, { 1000L }, backgroundScope, store)
        coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            coordinator.stopTrackFollow()
            release.countDown()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { cleared.await(2, TimeUnit.SECONDS) })
            assertFalse(active.get())
        } finally { release.countDown() }
    }

    @Test fun failedMarkerWriteReportsProblemWithoutCreatingRecording() = runTest {
        val store = object : TrackFollowSessionStore(File("unused")) {
            override fun write(): Unit = throw java.io.IOException("disk full")
        }
        val runtime = Runtime()
        val coordinator = JourneyNavigationCoordinator(runtime, null, { 1000L }, backgroundScope, store)
        coordinator.startTrackFollow(track(), TrackFollowSelection(), Transport.WALK)
        repeat(50) { runCurrent(); withContext(Dispatchers.IO) { Thread.sleep(2) } }
        assertNotNull(coordinator.state.value.message)
        assertNotNull(coordinator.state.value.trackFollow)
        assertEquals(0, runtime.starts + runtime.confirms)
    }

    companion object {
        private fun track(id: String = "track") = ImportedTrack(id, "Test", listOf(TrackSegment("A", listOf(WeatherCoordinate(40.0, 29.0), WeatherCoordinate(40.01, 29.0)))), 1)
        private fun route() = PlannedRoute("road", listOf(RouteStop("A", WeatherCoordinate(40.0, 29.0)), RouteStop("B", WeatherCoordinate(40.01, 29.0))),
            listOf(RouteVertex(WeatherCoordinate(40.0, 29.0), 0.0), RouteVertex(WeatherCoordinate(40.01, 29.0), 100.0)),
            1110.0, 100.0, 1000, transport = Transport.WALK,
            maneuvers = listOf(RouteManeuver(1, "Kuzeye", "Kuzeye", emptyList(), 0, 1, 0.0, 100.0)))
    }
}
