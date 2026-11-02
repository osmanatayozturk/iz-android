package org.iz.navigation.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteEditOwnerIntegrationTest {
    @Test fun previewNeedsExplicitExactCommitAndCannotCommitTwice() = runTest {
        val f = Fixture(this)
        f.start()
        val before = f.owner.state.value
        val owner = editOwner(f.owner)
        val id = requireNotNull(f.owner.previewEdit(RouteEditAction.AddStop(FUEL), owner))
        assertEquals("edited-route", f.owner.edits.value.preview!!.route.id)
        assertEquals("original-route", f.owner.state.value.route!!.id)
        assertEquals(before.routeRevision, f.owner.state.value.routeRevision)
        assertEquals("edit-owner-1", f.owner.state.value.sessionId)
        assertTrue(f.owner.state.value.recording)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(1, f.runtime.requests.size)
        assertEquals(3, f.runtime.requests.single().stops.size)
        assertEquals(WeatherCoordinate(0.0, 0.001), f.runtime.requests.single().stops.first().coordinate)
        assertEquals("Fuel", f.runtime.requests.single().stops[1].label)

        assertTrue(f.owner.commitEdit(owner, id))

        assertEquals("edited-route", f.owner.state.value.route!!.id)
        assertTrue(f.owner.state.value.routeRevision > before.routeRevision)
        assertEquals("edit-owner-1", f.owner.state.value.sessionId)
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.normalPlanCalls)
        assertNull(f.owner.edits.value.preview)
        assertFalse("A consumed preview cannot commit again", f.owner.commitEdit(owner, id))
    }

    @Test fun foreignPreviewAndChangedOwnerRevisionAreRejected() = runTest {
        val f = Fixture(this)
        f.start()
        val owner = editOwner(f.owner)
        val id = requireNotNull(f.owner.previewEdit(RouteEditAction.AddStop(FUEL), owner))
        assertFalse(f.owner.commitEdit(owner, "foreign-preview"))
        assertFalse(f.owner.commitEdit(owner.copy(sessionId = "foreign-session"), id))
        assertFalse(f.owner.commitEdit(owner.copy(routeId = "foreign-route"), id))
        assertFalse(f.owner.commitEdit(owner.copy(routeRevision = owner.routeRevision + 1), id))
        assertEquals("original-route", f.owner.state.value.route!!.id)
        assertEquals(id, f.owner.edits.value.preview!!.id)
        f.owner.startGuidance(baseRoute().copy(distanceMeters = 1_200.0))
        assertTrue(f.owner.state.value.routeRevision > owner.routeRevision)

        assertFalse(f.owner.commitEdit(owner, id))

        assertEquals("original-route", f.owner.state.value.route!!.id)
        assertEquals(1_200.0, f.owner.state.value.route!!.distanceMeters, 0.0)
        assertNull(f.owner.edits.value.preview)
        assertEquals("edit-owner-1", f.owner.state.value.sessionId)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
    }

    @Test fun lateUncancellableResultAfterCancelCannotPublish() = runTest {
        val f = Fixture(this)
        f.start()
        val owner = editOwner(f.owner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var providerReturned = false
        f.runtime.modify = { request -> withContext(NonCancellable) {
            entered.complete(Unit)
            release.await()
            val response = replacement(request)
            providerReturned = true
            response
        } }
        val pending = async { runCatching { f.owner.previewEdit(RouteEditAction.AddStop(FUEL), owner) } }
        entered.await()
        val requestId = requireNotNull(f.owner.edits.value.requestId)
        assertTrue(f.owner.edits.value.loading)
        assertTrue(f.owner.cancelEdit(owner, requestId))
        release.complete(Unit)
        val returned = runCatching { pending.await().getOrNull() }
        runCurrent()
        assertTrue("The non-cooperative provider really produced its late response", providerReturned)

        assertNull("A cancelled request cannot hand back an accepted preview ID", returned.getOrNull())
        assertNull(f.owner.edits.value.preview)
        assertNull(f.owner.edits.value.requestId)
        assertNull(f.owner.edits.value.error)
        assertFalse(f.owner.edits.value.loading)
        assertEquals("original-route", f.owner.state.value.route!!.id)
        assertFalse(f.owner.state.value.paused)
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.requests.size)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
    }

    @Test fun lateUncancellableResultAfterPauseCannotPublish() = runTest {
        val f = Fixture(this)
        f.start()
        val owner = editOwner(f.owner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var providerReturned = false
        f.runtime.modify = { request -> withContext(NonCancellable) {
            entered.complete(Unit)
            release.await()
            val response = replacement(request)
            providerReturned = true
            response
        } }
        val pending = async { runCatching { f.owner.previewEdit(RouteEditAction.AddStop(FUEL), owner) } }
        entered.await()
        assertNotNull(f.owner.edits.value.requestId)
        assertTrue(f.owner.setPaused(requireNotNull(NavigationStopSnapshot.from(f.owner.state.value)), true))
        release.complete(Unit)
        val returned = runCatching { pending.await().getOrNull() }
        runCurrent()
        assertTrue("The non-cooperative provider really produced its late response", providerReturned)

        assertNull(returned.getOrNull())
        assertNull(f.owner.edits.value.preview)
        assertNull(f.owner.edits.value.error)
        assertFalse(f.owner.edits.value.loading)
        assertTrue(f.owner.state.value.paused)
        assertTrue(f.owner.state.value.guidance)
        assertTrue(f.owner.state.value.recording)
        assertEquals("original-route", f.owner.state.value.route!!.id)
        assertEquals("edit-owner-1", f.owner.state.value.sessionId)
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.requests.size)
        assertEquals(0, f.runtime.finished)
    }

    @Test fun returnedPolylineInsideItsClaimedAvoidAreaNeverReplacesLiveRoute() = runTest {
        val f = Fixture(this)
        f.start()
        val owner = editOwner(f.owner)
        f.runtime.modify = { request -> replacement(request).copy(
            // Metadata claims the exact requested areas, but the returned geometry crosses their interior.
            vertices = listOf(RouteVertex(WeatherCoordinate(0.0, 0.001), 0.0),
                RouteVertex(WeatherCoordinate(0.0, 0.003), 20.0),
                RouteVertex(WeatherCoordinate(0.0, 0.006), 50.0),
                RouteVertex(WeatherCoordinate(0.0, 0.01), 90.0)),
            maneuvers = listOf(RouteManeuver(15, "Unsafe turn", "Unsafe turn", emptyList(), 1, 2, 20.0, 50.0)),
            avoidAreas = request.avoidAreas,
        ) }

        val id = f.owner.previewEdit(RouteEditAction.AvoidManeuver(0), owner)

        val requested = f.runtime.requests.single()
        assertEquals(1, requested.avoidAreas.size)
        val area = requested.avoidAreas.single()
        assertTrue(area.west < 0.003 && area.east > 0.006)
        assertTrue(area.south < 0.0 && area.north > 0.0)
        assertNull(id)
        assertNull(f.owner.edits.value.preview)
        assertNotNull(f.owner.edits.value.error)
        assertEquals("original-route", f.owner.state.value.route!!.id)
        assertEquals(owner.routeRevision, f.owner.state.value.routeRevision)
        assertEquals("edit-owner-1", f.owner.state.value.sessionId)
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertTrue(f.owner.state.value.recording)
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
    }

    @Test fun zeroTimeUnvisitedStopIsSkippedUsingTheActualRemainingIndices() = runTest {
        val f = Fixture(this, baseRoute(ambiguousStopTimes = true))
        f.start()
        val owner = editOwner(f.owner)
        // GPS is before the intermediate0.006 stop even though both origin and stop have time0.
        assertEquals(0.0, f.owner.state.value.progress!!.elapsedSeconds, 0.0)
        assertEquals(listOf(1, 2), f.owner.remainingStopIndices(owner))

        val preview = requireNotNull(f.owner.previewEdit(RouteEditAction.SkipStop(1), owner))

        val request = f.runtime.requests.single()
        assertEquals(2, request.stops.size)
        assertEquals(listOf(WeatherCoordinate(0.0, 0.001), WeatherCoordinate(0.0, 0.01)),
            request.stops.map { it.coordinate })
        assertEquals(3, f.owner.state.value.route!!.stops.size)
        assertTrue(f.owner.commitEdit(owner, preview))
        assertEquals("edited-route", f.owner.state.value.route!!.id)
        assertEquals(2, f.owner.state.value.route!!.stops.size)
        assertEquals("Destination", f.owner.state.value.route!!.stops.last().label)
        assertEquals(listOf(1), f.owner.remainingStopIndices(editOwner(f.owner)))
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
    }

    @Test fun nullWalkingSpeedAcceptsNormalizedDefaultPreviewAndCommit() = runTest {
        assertNormalizedDefaultPreviewAndCommit(Transport.WALK, 5.1)
    }

    @Test fun nullBicycleSpeedAcceptsNormalizedDefaultPreviewAndCommit() = runTest {
        assertNormalizedDefaultPreviewAndCommit(Transport.BICYCLE, 18.0)
    }

    @Test fun nullRunningSpeedAcceptsNormalizedDefaultPreviewAndCommit() = runTest {
        assertNormalizedDefaultPreviewAndCommit(Transport.RUN, 10.0)
    }

    @Test fun distinctExplicitSpeedCannotReplaceTheCurrentRouteOrRecorder() = runTest {
        val original = baseRoute().copy(transport = Transport.BICYCLE, travelSpeedKmh = 22.0,
            preferences = RoutePreferences.defaults(Transport.BICYCLE), hasHighway = false)
        val f = Fixture(this, original)
        f.start()
        val before = f.owner.state.value
        val owner = editOwner(f.owner)
        f.runtime.modify = { request -> replacement(request).copy(travelSpeedKmh = 18.0, hasHighway = false) }

        val id = f.owner.previewEdit(RouteEditAction.AddStop(FUEL), owner)

        val request = f.runtime.requests.single()
        assertEquals(Transport.BICYCLE, request.transport)
        assertEquals(22.0, requireNotNull(request.travelSpeedKmh), 0.0)
        assertNull("An explicitly different speed cannot become a preview", id)
        assertNull(f.owner.edits.value.preview)
        assertNotNull(f.owner.edits.value.error)
        val after = f.owner.state.value
        assertEquals(before.route, after.route)
        assertEquals(22.0, requireNotNull(after.route!!.travelSpeedKmh), 0.0)
        assertEquals(before.routeRevision, after.routeRevision)
        assertEquals(before.sessionId, after.sessionId)
        assertTrue(after.recording)
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.normalPlanCalls)
    }

    private suspend fun TestScope.assertNormalizedDefaultPreviewAndCommit(transport: Transport, defaultSpeed: Double) {
        // Independent provider-contract examples: WALK 5.1, BICYCLE 18.0, RUN 10.0 km/h.
        // The persisted route and request deliberately retain null; only the provider normalizes it.
        val original = baseRoute().copy(transport = transport, travelSpeedKmh = null,
            preferences = RoutePreferences.defaults(transport), hasHighway = false)
        val f = Fixture(this, original)
        f.start()
        val before = f.owner.state.value
        val owner = editOwner(f.owner)
        f.runtime.modify = { request -> replacement(request).copy(travelSpeedKmh = defaultSpeed, hasHighway = false) }

        val id = f.owner.previewEdit(RouteEditAction.AddStop(FUEL), owner)

        val request = f.runtime.requests.single()
        assertEquals(transport, request.transport)
        assertNull("An unset default remains a valid nullable request", request.travelSpeedKmh)
        assertEquals(original.preferences, request.preferences)
        assertEquals(original, f.owner.state.value.route)
        assertEquals(before.routeRevision, f.owner.state.value.routeRevision)
        assertNotNull("A provider-normalized $transport default must produce a usable preview", id)
        val preview = requireNotNull(f.owner.edits.value.preview)
        assertEquals(transport, preview.route.transport)
        assertEquals(defaultSpeed, requireNotNull(preview.route.travelSpeedKmh), 0.0)
        assertEquals(request.stops, preview.route.stops)
        assertNull(f.owner.edits.value.error)

        assertTrue(f.owner.commitEdit(owner, requireNotNull(id)))

        val after = f.owner.state.value
        assertEquals("edited-route", after.route!!.id)
        assertEquals(transport, after.route.transport)
        assertEquals(defaultSpeed, requireNotNull(after.route.travelSpeedKmh), 0.0)
        assertEquals(original.preferences, after.route.preferences)
        assertTrue(after.routeRevision > before.routeRevision)
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(transport, f.runtime.journeys.value.single().transport)
        assertTrue(after.guidance)
        assertTrue(after.recording)
        assertEquals("edit-owner-1", f.runtime.recordingId())
        assertEquals(1, f.runtime.started)
        assertEquals(0, f.runtime.finished)
        assertEquals(0, f.runtime.normalPlanCalls)
        assertNull(f.owner.edits.value.preview)
        assertFalse("The normalized preview is consumed once", f.owner.commitEdit(owner, requireNotNull(id)))
    }

    private class Fixture(scope: TestScope, private val route: PlannedRoute = baseRoute()) {
        val now = { 1_000L + scope.testScheduler.currentTime }
        val runtime = Runtime(now)
        val owner = JourneyNavigationCoordinator(runtime, null, now, scope.backgroundScope,
            monotonicClock = { scope.testScheduler.currentTime })
        suspend fun start() {
            owner.startGuidance(route)
            val fix = NavigationFix(WeatherCoordinate(0.0, 0.001), now(), 5f, 10f, 90f)
            owner.onAcceptedLocation("edit-owner-1", fix)
            while (owner.state.value.fix?.recordedAt != fix.recordedAt) yield()
        }
    }

    private class Runtime(private val now: () -> Long) : NavigationRuntime, RouteModificationPlanner {
        override val journeys = MutableStateFlow<List<Journey>>(emptyList())
        private var running: String? = null
        var started = 0
        var finished = 0
        var normalPlanCalls = 0
        val requests = mutableListOf<RouteModificationRequest>()
        var modify: suspend (RouteModificationRequest) -> PlannedRoute = { replacement(it) }
        override suspend fun activeJourney() = journeys.value.firstOrNull { it.endedAt == null && !it.interrupted }
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
            check(stillCurrent())
            started++
            val journey = Journey(id = "edit-owner-$started", transport = transport,
                status = JourneyStatus.CONFIRMED, startedAt = now())
            running = journey.id
            journeys.value = listOf(journey)
            return journey.id
        }
        override suspend fun confirm(id: String, transport: Transport) {
            journeys.value = journeys.value.map { if (it.id == id) it.copy(status = JourneyStatus.CONFIRMED, transport = transport) else it }
        }
        override suspend fun finish(id: String) {
            finished++
            journeys.value = journeys.value.map { if (it.id == id) it.copy(endedAt = now()) else it }
            if (running == id) running = null
        }
        override suspend fun interrupt(id: String) {
            journeys.value = journeys.value.map { if (it.id == id) it.copy(interrupted = true, endedAt = now()) else it }
            if (running == id) running = null
        }
        override fun recordingId() = running
        override fun setHighFrequency(journeyId: String?) = Unit
        override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute {
            normalPlanCalls++
            error("Typed route-edit requests must use RouteModificationPlanner")
        }
        override suspend fun planModified(request: RouteModificationRequest): PlannedRoute {
            requests += request
            return modify(request)
        }
        override suspend fun locate() = NavigationFix(WeatherCoordinate(0.0, 0.001), now(), 5f, 10f, 90f)
        override fun render(state: NavigationState) = Unit
        override fun speak(text: String) = Unit
        override fun cancelSpeech() = Unit
    }

    private fun editOwner(owner: JourneyNavigationCoordinator) = requireNotNull(RouteEditOwner.from(owner.state.value))

    private companion object {
        val FUEL = RouteStop("Fuel", WeatherCoordinate(0.0, 0.002))
        fun baseRoute(ambiguousStopTimes: Boolean = false): PlannedRoute {
            val stops = if (ambiguousStopTimes) listOf(RouteStop("Origin", WeatherCoordinate(0.0, 0.0)),
                RouteStop("Unvisited", WeatherCoordinate(0.0, 0.006)), RouteStop("Destination", WeatherCoordinate(0.0, 0.01)))
                else listOf(RouteStop("Origin", WeatherCoordinate(0.0, 0.0)), RouteStop("Destination", WeatherCoordinate(0.0, 0.01)))
            val vertices = if (ambiguousStopTimes) listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0),
                RouteVertex(WeatherCoordinate(0.0, 0.006), 0.0), RouteVertex(WeatherCoordinate(0.0, 0.01), 100.0))
                else listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.0, 0.003), 30.0),
                    RouteVertex(WeatherCoordinate(0.0, 0.006), 60.0), RouteVertex(WeatherCoordinate(0.0, 0.01), 100.0))
            return PlannedRoute("original-route", stops, vertices, 1_111.95, 100.0, 1_000,
                stopElapsedSeconds = if (ambiguousStopTimes) listOf(0.0, 0.0, 100.0) else listOf(0.0, 100.0),
                transport = Transport.MOTORCYCLE,
                maneuvers = listOf(RouteManeuver(15, "Turn left", "Turn left", emptyList(), 1,
                    2, if (ambiguousStopTimes) 0.0 else 30.0,
                    if (ambiguousStopTimes) 100.0 else 60.0)))
        }
        fun replacement(request: RouteModificationRequest): PlannedRoute = PlannedRoute("edited-route",
            request.stops, request.stops.mapIndexed { index, stop -> RouteVertex(stop.coordinate, index * 45.0) },
            1_000.0, 90.0, request.departureAt, transport = request.transport,
            travelSpeedKmh = request.travelSpeedKmh, preferences = request.preferences,
            maneuvers = listOf(RouteManeuver(15, "Edited turn", "Edited turn", emptyList(), 0,
                request.stops.lastIndex, 0.0, 90.0)), avoidAreas = request.avoidAreas)
    }
}
