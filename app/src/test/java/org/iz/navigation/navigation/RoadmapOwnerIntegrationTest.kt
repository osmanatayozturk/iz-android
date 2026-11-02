package org.iz.navigation.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteProvider
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoadmapOwnerIntegrationTest {
    @Test fun pauseKeepsRecorderSessionAndGpsDemandWhileRawFixChanges() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route())
        runCurrent()
        f.push(session, longitude = 0.003)
        assertEquals(30.0, f.owner.state.value.progress!!.elapsedSeconds, 0.01)
        assertEquals(1_000L, f.owner.state.value.progressUpdatedAt)
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))

        advanceTimeBy(60_000)
        f.push(session, longitude = 0.009)

        val paused = f.owner.state.value
        assertTrue(paused.paused)
        assertTrue(paused.guidance)
        assertTrue(paused.recording)
        assertTrue(paused.locationActive)
        assertEquals("owner-1", paused.journey!!.id)
        assertEquals(session, paused.sessionId)
        assertEquals("owner-1", f.runtime.recordingId())
        assertEquals("owner-1", f.runtime.highFrequencyJourney)
        assertEquals(session, f.runtime.locationDemand.id)
        assertTrue(f.runtime.locationDemand.highFrequency)
        assertFalse(f.runtime.locationDemand.suppressAutomatic)
        assertEquals(0.009, paused.fix!!.coordinate.longitude, 0.0)
        assertEquals(30.0, paused.progress!!.elapsedSeconds, 0.01)
        assertNull(paused.progressUpdatedAt)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.interrupted)
        assertEquals(0, f.runtime.planCalls)
        assertTrue(f.runtime.cues.isEmpty())
        assertTrue(f.runtime.directSpeech.isEmpty())
    }

    @Test fun pausedTrafficRouteDoesNotPlanOrIssueCuesAfterRefreshBecomesDue() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route("traffic", RouteProvider.TOMTOM))
        runCurrent()
        f.push(session, longitude = 0.003)
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))
        advanceTimeBy(121_000)
        f.push(session, longitude = 0.009)
        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(f.owner.state.value.paused)
        assertFalse("The latest fix is still fresh, so GPS staleness cannot explain suppression", f.owner.state.value.gpsStale)
        assertEquals(30.0, f.owner.state.value.progress!!.elapsedSeconds, 0.01)
        assertEquals("traffic", f.owner.state.value.route!!.id)
        assertEquals(0, f.runtime.planCalls)
        assertTrue(f.runtime.cues.isEmpty())
        assertTrue(f.runtime.directSpeech.isEmpty())
        assertEquals(session, f.runtime.locationDemand.id)
    }

    @Test fun pausingNoRecordGuidanceKeepsGpsDemandWithoutCreatingADiaryOwner() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route(), recordJourney = false)
        runCurrent()
        f.push(session, longitude = 0.003)
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))
        advanceTimeBy(1_000)
        f.push(session, longitude = 0.004)

        val state = f.owner.state.value
        assertTrue(state.paused)
        assertTrue(state.guidance)
        assertFalse(state.recording)
        assertNull(state.journey)
        assertEquals(session, state.sessionId)
        assertEquals(session, f.runtime.locationDemand.id)
        assertTrue(f.runtime.locationDemand.highFrequency)
        assertTrue(f.runtime.locationDemand.suppressAutomatic)
        assertNull(f.runtime.highFrequencyJourney)
        assertEquals(0, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(30.0, state.progress!!.elapsedSeconds, 0.01)
    }

    @Test fun prePauseAndForeignConfirmationCannotResumeTheCurrentPausedOwner() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route())
        runCurrent()
        f.push(session, longitude = 0.003)
        val beforePause = snapshot(f.owner)
        assertTrue(f.owner.setPaused(beforePause, true))
        val paused = snapshot(f.owner)

        assertFalse(f.owner.setPaused(beforePause, false))
        assertFalse(f.owner.setPaused(paused.copy(sessionId = "foreign-session"), false))
        assertTrue(f.owner.state.value.paused)
        assertEquals(session, f.owner.state.value.sessionId)
        assertEquals(0, f.runtime.finished)
        assertTrue("The actual current identity can resume using the same fresh fix", f.owner.setPaused(paused, false))
        assertFalse(f.owner.state.value.paused)
    }

    @Test fun pausedSnapshotFromOldRouteCannotResumeANewPausedRoute() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route("old-route"))
        runCurrent()
        f.push(session, longitude = 0.003)
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))
        val oldPaused = snapshot(f.owner)
        f.owner.startGuidance(route("new-route"))
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))

        assertFalse(f.owner.setPaused(oldPaused, false))
        assertTrue(f.owner.state.value.paused)
        assertEquals("new-route", f.owner.state.value.route!!.id)
        assertEquals(session, f.owner.state.value.sessionId)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.planCalls)
    }

    @Test fun resumeRejectsStaleGpsThenUsesFreshPausedFixWithoutMovingTheRecorder() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route())
        runCurrent()
        f.push(session, longitude = 0.003)
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))
        advanceTimeBy(11_000)
        runCurrent()
        val failed = runCatching { f.owner.setPaused(snapshot(f.owner), false) }
        assertTrue("An old fix cannot authorize resume", failed.isFailure)
        assertTrue(f.owner.state.value.paused)
        assertEquals(30.0, f.owner.state.value.progress!!.elapsedSeconds, 0.01)

        advanceTimeBy(50_000)
        f.push(session, longitude = 0.009)
        assertEquals("Progress remains frozen until explicit resume", 30.0, f.owner.state.value.progress!!.elapsedSeconds, 0.01)
        assertTrue(f.owner.setPaused(snapshot(f.owner), false))
        assertFalse(f.owner.state.value.paused)
        assertFalse(f.owner.state.value.gpsStale)
        assertEquals(90.0, f.owner.state.value.progress!!.elapsedSeconds, 0.01)
        assertEquals(62_000L, f.owner.state.value.progressUpdatedAt)
        assertEquals("owner-1", f.runtime.recordingId())
        assertEquals(session, f.owner.state.value.sessionId)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.planCalls)
    }

    @Test fun preparedRecordedStartUsesTheExactSavedGeometryWithoutAPlanner() = runTest {
        val f = Fixture(this)
        val session = f.owner.startPreparedGuidance(route("saved"), FINGERPRINT, recordJourney = true)
        runCurrent()
        val state = f.owner.state.value
        assertEquals("owner-1", session)
        assertEquals(session, state.sessionId)
        assertEquals("owner-1", state.journey!!.id)
        assertTrue(state.recording)
        assertTrue(state.guidance)
        assertFalse(state.paused)
        assertEquals(FINGERPRINT, state.preparedRouteFingerprint)
        val selectedRoute = requireNotNull(state.route)
        assertEquals("saved", selectedRoute.id)
        assertEquals(2, selectedRoute.vertices.size)
        assertEquals(WeatherCoordinate(0.0, 0.0), selectedRoute.vertices.first().coordinate)
        assertEquals(WeatherCoordinate(0.0, 0.01), selectedRoute.vertices.last().coordinate)
        assertEquals(30.0, state.progress!!.elapsedSeconds, 0.01)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.planCalls)
        assertEquals(listOf(GuidanceVoiceMode.PREPARED_LOCAL), f.runtime.preparedModes)
    }

    @Test fun preparedNoRecordStartKeepsAnExplicitGpsSessionWithoutCreatingDiaryRows() = runTest {
        val f = Fixture(this)
        val session = f.owner.startPreparedGuidance(route("saved"), FINGERPRINT, recordJourney = false)
        runCurrent()
        val state = f.owner.state.value
        assertEquals(session, state.sessionId)
        assertTrue(state.guidance)
        assertFalse(state.recording)
        assertNull(state.journey)
        assertEquals(FINGERPRINT, state.preparedRouteFingerprint)
        assertEquals("saved", state.route!!.id)
        assertEquals(30.0, state.progress!!.elapsedSeconds, 0.01)
        assertEquals(session, f.runtime.locationDemand.id)
        assertTrue(f.runtime.locationDemand.highFrequency)
        assertTrue(f.runtime.locationDemand.suppressAutomatic)
        assertEquals(0, f.runtime.started)
        assertEquals(0, f.runtime.planCalls)
        assertEquals(listOf(GuidanceVoiceMode.PREPARED_LOCAL), f.runtime.preparedModes)
    }

    @Test fun outsidePreparedRouteCannotInventAConnectorOrStartARecorder() = runTest {
        val f = Fixture(this)
        f.runtime.located = NavigationFix(WeatherCoordinate(0.001, 0.003), f.now(), 5f, 10f, 90f)
        val outcome = runCatching { f.owner.startPreparedGuidance(route("saved"), FINGERPRINT) }
        assertTrue(outcome.isFailure)
        assertEquals(0, f.runtime.planCalls)
        assertEquals(0, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertNull(f.runtime.recordingId())
        assertNull(f.owner.state.value.sessionId)
        assertNull(f.owner.state.value.route)
        assertFalse(f.owner.state.value.guidance)
        assertNull(f.owner.state.value.preparedRouteFingerprint)
        assertTrue(f.runtime.preparedModes.isEmpty())
    }

    @Test fun alreadyInvalidPreparedSelectionCannotAdoptANewRecorderOrSession() = runTest {
        val f = Fixture(this)
        val outcome = runCatching {
            f.owner.startPreparedGuidance(route("deleted-saved"), FINGERPRINT, recordJourney = true,
                stillPrepared = { false })
        }
        assertTrue(outcome.isFailure)
        assertEquals("A stale selection cannot start raw recording before failing", 0, f.runtime.started)
        assertNull(f.runtime.recordingId())
        assertNull(f.owner.state.value.journey)
        assertNull(f.owner.state.value.sessionId)
        assertFalse(f.owner.state.value.recording)
        assertFalse(f.owner.state.value.guidance)
        assertEquals(0, f.runtime.planCalls)
    }

    @Test fun ownerEntryRevokedWhileLocatingCannotAdoptANewRecordingSession() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var currentEntry = "original-entry"
        f.runtime.onLocate = { entered.complete(Unit); release.await() }
        val activation = async {
            runCatching {
                f.owner.startPreparedGuidance(route("saved"), FINGERPRINT, recordJourney = true,
                    stillCurrent = { currentEntry == "original-entry" })
            }
        }
        entered.await()
        currentEntry = "replacement-entry"
        release.complete(Unit)
        assertTrue(activation.await().isFailure)
        assertEquals(0, f.runtime.started)
        assertEquals(0, f.runtime.planCalls)
        assertNull(f.runtime.recordingId())
        assertNull(f.owner.state.value.sessionId)
        assertFalse(f.owner.state.value.guidance)
    }

    @Test fun preparedAssetInvalidatedDuringActivationCannotReplaceTheExistingOwner() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route("existing"))
        runCurrent()
        f.push(session, longitude = 0.003)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var preparedStillExists = true
        f.runtime.onActive = { entered.complete(Unit); release.await() }
        val activation = async {
            runCatching {
                f.owner.startPreparedGuidance(route("saved"), FINGERPRINT,
                    stillPrepared = { preparedStillExists })
            }
        }
        entered.await()
        preparedStillExists = false
        release.complete(Unit)
        assertTrue(activation.await().isFailure)
        assertEquals("existing", f.owner.state.value.route!!.id)
        assertEquals(session, f.owner.state.value.sessionId)
        assertTrue(f.owner.state.value.recording)
        assertTrue(f.owner.state.value.guidance)
        assertNull(f.owner.state.value.preparedRouteFingerprint)
        assertEquals("owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.planCalls)
    }

    @Test fun pausingInvalidatesAnActuallyOfferedDelayedCueBeforeItFinishes() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route("old-route"))
        runCurrent()
        f.push(session, longitude = 0.009)
        f.awaitCues(1)
        assertEquals(1, f.runtime.cues.size)
        val delayed = f.runtime.cues.single()
        assertTrue("This must exercise a real eligible owner request", delayed.isCurrent())
        assertTrue(f.owner.setPaused(snapshot(f.owner), true))
        assertFalse("Pause must revoke the old request before its delayed completion", delayed.isCurrent())

        delayed.finish(CueDelivery.COMPLETED)
        runCurrent()

        assertTrue(f.owner.state.value.paused)
        assertTrue(f.owner.state.value.recording)
        assertEquals(session, f.owner.state.value.sessionId)
        assertEquals("owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.cues.size)
        assertEquals(0, f.runtime.planCalls)
    }

    @Test fun lateOldRouteCompletionCannotConsumeTheNewRoutesPendingCue() = runTest {
        val f = Fixture(this)
        val session = f.owner.startGuidance(route("old-route"))
        runCurrent()
        f.push(session, longitude = 0.009)
        f.awaitCues(1)
        val old = f.runtime.cues.single()
        assertTrue(old.isCurrent())
        f.owner.startGuidance(route("new-route"))
        assertFalse(old.isCurrent())
        advanceTimeBy(1_000)
        f.push(session, longitude = 0.0092)
        f.awaitCues(2)
        assertEquals(2, f.runtime.cues.size)
        val current = f.runtime.cues.last()
        assertTrue(current.isCurrent())

        old.finish(CueDelivery.COMPLETED)

        assertTrue("Old completion cannot remove or consume the current route request", current.isCurrent())
        assertEquals("new-route", f.owner.state.value.route!!.id)
        assertEquals(session, f.owner.state.value.sessionId)
        assertTrue(f.owner.state.value.guidance)
        assertTrue(f.owner.state.value.recording)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.planCalls)
    }

    private class Fixture(scope: TestScope) {
        val now = { 1_000L + scope.testScheduler.currentTime }
        val runtime = Runtime(now)
        val owner = JourneyNavigationCoordinator(runtime, null, now, scope.backgroundScope,
            monotonicClock = { scope.testScheduler.currentTime })

        suspend fun push(session: String, longitude: Double) {
            val fix = NavigationFix(WeatherCoordinate(0.0, longitude), now(), 5f, 10f, 90f)
            owner.onAcceptedLocation(session, fix)
            // Existing coordinator test pattern: allow its real Dispatchers.Default matcher to publish.
            while (owner.state.value.fix?.recordedAt != fix.recordedAt) yield()
        }
        suspend fun awaitCues(count: Int) {
            while (runtime.cues.size < count) yield()
        }
    }

    private data class LocationDemand(val id: String? = null, val transport: Transport? = null,
        val highFrequency: Boolean = false, val suppressAutomatic: Boolean = false)

    /** Only external IO boundaries are substituted; coordinator, engine and ownership requests are real. */
    private class Runtime(private val now: () -> Long) : NavigationRuntime, NavigationVoiceRuntime {
        override val journeys = MutableStateFlow<List<Journey>>(emptyList())
        var started = 0
        var finished = 0
        var interrupted = 0
        var planCalls = 0
        private var running: String? = null
        var highFrequencyJourney: String? = null
        var locationDemand = LocationDemand()
        var onActive: (suspend () -> Unit)? = null
        var onLocate: (suspend () -> Unit)? = null
        var located = NavigationFix(WeatherCoordinate(0.0, 0.003), now(), 5f, 10f, 90f)
        val cues = mutableListOf<NavigationCueRequest>()
        val directSpeech = mutableListOf<String>()
        val preparedModes = mutableListOf<GuidanceVoiceMode>()
        private val voice = MutableStateFlow(SpeechStatus(readiness = EngineReadiness.READY,
            localVoice = LocalVoiceCapability.AVAILABLE, voiceMode = GuidanceVoiceMode.DEFAULT))
        override val speechStatus: StateFlow<SpeechStatus> = voice
        override suspend fun activeJourney(): Journey? {
            onActive?.invoke()
            return journeys.value.firstOrNull { it.endedAt == null && !it.interrupted }
        }
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
            check(stillCurrent())
            started++
            val journey = Journey(id = "owner-$started", transport = transport,
                status = JourneyStatus.CONFIRMED, startedAt = now())
            running = journey.id
            journeys.value = listOf(journey)
            return journey.id
        }
        override suspend fun confirm(id: String, transport: Transport) {
            journeys.value = journeys.value.map {
                if (it.id == id) it.copy(status = JourneyStatus.CONFIRMED, transport = transport) else it
            }
        }
        override suspend fun finish(id: String) {
            finished++
            journeys.value = journeys.value.map { if (it.id == id) it.copy(endedAt = now()) else it }
            if (running == id) running = null
        }
        override suspend fun interrupt(id: String) {
            interrupted++
            journeys.value = journeys.value.map { if (it.id == id) it.copy(interrupted = true, endedAt = now()) else it }
            if (running == id) running = null
        }
        override fun recordingId() = running
        override fun setHighFrequency(journeyId: String?) { highFrequencyJourney = journeyId }
        override fun setLocationSession(id: String?, transport: Transport?, highFrequency: Boolean, suppressAutomatic: Boolean) {
            locationDemand = LocationDemand(id, transport, highFrequency, suppressAutomatic)
        }
        override fun locationActive() = locationDemand.id != null || running != null
        override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute {
            planCalls++
            error("This owner operation must not request a planner")
        }
        override suspend fun locate(): NavigationFix { onLocate?.invoke(); return located }
        override fun render(state: NavigationState) = Unit
        override fun prepareSpeech() = prepareSpeech(GuidanceVoiceMode.DEFAULT)
        override fun prepareSpeech(mode: GuidanceVoiceMode) {
            preparedModes += mode
            voice.value = voice.value.copy(voiceMode = mode)
        }
        override fun speak(text: String) { directSpeech += text }
        override fun cancelSpeech() = Unit // Delayed request completion is deliberately controlled by each case.
        override fun speakCue(request: NavigationCueRequest) { cues += request }
    }

    private fun snapshot(owner: JourneyNavigationCoordinator) = requireNotNull(NavigationStopSnapshot.from(owner.state.value))

    private fun route(id: String = "online", provider: RouteProvider = RouteProvider.VALHALLA) = PlannedRoute(
        id = id,
        stops = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, 0.01))),
        vertices = listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.0, 0.01), 100.0)),
        distanceMeters = 1_111.95, durationSeconds = 100.0, createdAt = 1_000,
        stopElapsedSeconds = listOf(0.0, 100.0), transport = Transport.MOTORCYCLE,
        maneuvers = listOf(RouteManeuver(15, "Turn left", "Turn left", emptyList(), 1, 1, 100.0, 100.0)),
        provider = provider,
    )

    private companion object {
        val FINGERPRINT = "a".repeat(64)
    }
}
