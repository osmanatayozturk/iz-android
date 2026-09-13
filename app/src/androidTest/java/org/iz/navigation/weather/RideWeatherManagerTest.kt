package org.iz.navigation.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.tracking.TrackingCoordinator
import org.iz.navigation.tracking.TrackingService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room lifecycle + controlled network/clock boundaries.
 */
@RunWith(AndroidJUnit4::class)
class RideWeatherManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val clock = AtomicLong()
    private val voiceSuspensions = AtomicLong()
    private val provider = ControlledProvider()
    private val plannerCalls = CopyOnWriteArrayList<Pair<List<RouteStop>, Long>>()
    private val plannerProfiles = CopyOnWriteArrayList<Pair<Transport, Double?>>()
    private val requestedStarts = CopyOnWriteArrayList<Transport>()
    private val clearedAlerts = AtomicLong()
    private val announcements = CopyOnWriteArrayList<Announcement>()
    @Volatile private var plannedReplacement: PlannedRoute? = null
    private val weatherPreferences = context.getSharedPreferences("ride_weather_settings_v1", Context.MODE_PRIVATE)
    private var originalSettings: Map<String, *> = emptyMap<String, String>()
    private var settingsTouched = false
    private val nextActiveRead = AtomicReference<PendingActiveRead?>(null)
    private val activeReads = CopyOnWriteArrayList<PendingActiveRead>()
    private lateinit var manager: RideWeatherManager
    private var ownsDiary = false
    private lateinit var first: Journey
    private lateinit var route: PlannedRoute

    @Before fun setUp() = runBlocking<Unit> {
        assertFalse("Manager tests require a stopped real GPS service", TrackingService.isRunning)
        ownsDiary = true
        repository.restore(DiarySnapshot())
        originalSettings = weatherPreferences.all.toMap()
        weatherPreferences.edit().clear().commit()
        settingsTouched = true
        WeatherSettingsStore(context).save(RideWeatherSettings())
        clock.set(System.currentTimeMillis())
        first = repository.createJourney(Transport.MOTORCYCLE, false, now = clock.get() - 1_000)
        route = makeRoute("first")
        manager = createManager()
    }

    @After fun tearDown() = runBlocking<Unit> {
        if (::manager.isInitialized) {
            manager.stop()
            awaitManagerTurn()
        }
        provider.close()
        activeReads.forEach { it.release.complete(null) }
        try {
            withTimeout(5_000) { provider.joinRequests() }
        } finally {
            try {
                if (ownsDiary) repository.restore(DiarySnapshot())
            } finally {
                if (settingsTouched) {
                    val editor = weatherPreferences.edit().clear()
                    originalSettings.forEach { (key, value) ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    assertTrue("Restore the test's original weather settings", editor.commit())
                }
            }
        }
    }

    @Test fun sameIdMotorcycleEditStopsWeatherButKeepsRunningJourneyAndSteps() = test {
        activate()
        assertTrue(repository.updateJourneyDetails(first.id, "Run", "Keep diary", Transport.RUN))
        awaitOff()
        assertNull(manager.wearWeather(first.id))
        val current = requireNotNull(repository.getJourney(first.id))
        assertEquals(Transport.RUN, current.transport)
        assertNull(current.endedAt)
        assertTrue(repository.recordWalkingSteps(first.id, 42))
        assertEquals(42L, repository.getJourney(first.id)?.stepCount)
        assertTrue("Planned route must never become recorded GPS", repository.journeyPoints(first.id).isEmpty())
    }

    @Test fun gpsProgressPublishesRemainingTimeBeforeTheNextWeatherAssessment() = test {
        activate()
        acceptOriginFix()
        awaitManagerTurn()
        val initialAssessment = manager.state.value.assessment
        clock.addAndGet(5_000)
        manager.onAcceptedLocation(first.id, route.vertices[1].coordinate, clock.get(), 5f)
        val halfway = withTimeout(5_000) {
            manager.state.first { it.remainingSeconds?.let { value -> kotlin.math.abs(value - 900.0) < 0.01 } == true }
        }
        assertEquals(clock.get() + 900_000L, halfway.arrivalAt)
        assertSame("ETA must not force an extra weather assessment", initialAssessment, halfway.assessment)
        assertTrue(plannerCalls.isEmpty())
        assertTrue(provider.requests.tryReceive().isFailure)
        clock.addAndGet(5_000)
        manager.onAcceptedLocation(first.id, route.vertices[1].coordinate, clock.get(), 5f)
        val paused = withTimeout(5_000) { manager.state.first { it.arrivalAt == halfway.arrivalAt!! + 5_000L } }
        assertEquals(halfway.remainingSeconds!!, paused.remainingSeconds!!, 0.001)
    }

    @Test fun gpsExpiryFreezesArrivalEvenWhenWeatherRefreshesAndThenRecovers() = test {
        activate()
        acceptOriginFix()
        awaitManagerTurn()
        val initial = manager.state.value
        clock.addAndGet(90_000)
        val stale = withTimeout(5_000) { manager.state.first { it.gpsStale } }
        assertEquals(initial.arrivalAt, stale.arrivalAt)
        assertEquals(initial.timingUpdatedAt, stale.timingUpdatedAt)
        clock.addAndGet(30_000)
        manager.preferencesChanged()
        awaitManagerTurn()
        assertEquals(stale.arrivalAt, manager.state.value.arrivalAt)
        manager.onAcceptedLocation(first.id, route.vertices[1].coordinate, clock.get(), 5f)
        val recovered = withTimeout(5_000) { manager.state.first { !it.gpsStale } }
        assertEquals(clock.get() + 900_000L, recovered.arrivalAt)
    }

    @Test fun everySupportedModeAttachesToItsOwnJourneyWithoutRecordingPlannedPoints() = test {
        for (mode in Transport.entries.filter { it != Transport.UNKNOWN }) {
            assertTrue(repository.updateJourneyDetails(first.id, "Mode test", "", mode))
            val modeRoute = route.copy(transport = mode)
            assertEquals(first.id, manager.activate(modeRoute, forecasts(modeRoute)))
            assertEquals(mode, manager.state.value.route?.transport)
            assertEquals(mode, repository.activeJourney()?.transport)
            assertNotNull(manager.wearWeather(first.id))
            assertTrue(repository.journeyPoints(first.id).isEmpty())
            manager.stop()
            awaitManagerTurn()
        }
    }

    @Test fun mismatchedPlanCannotAttachToOrChangeAnotherModeJourney() = test {
        val walkingRoute = route.copy(transport = Transport.WALK)
        try {
            manager.activate(walkingRoute, forecasts(walkingRoute))
            fail("A walking plan must not attach to the motorcycle journey")
        } catch (_: IllegalArgumentException) { }
        assertEquals(Transport.MOTORCYCLE, repository.activeJourney()?.transport)
        assertEquals(first.id, repository.activeJourney()?.id)
        assertEquals(RideWeatherStatus.OFF, manager.state.value.status)
    }

    @Test fun newWeatherJourneysStartInEachRequestedMode() = test {
        manager.stop(); awaitManagerTurn()
        repository.finishJourney(first.id)
        manager = createManager(allowStart = true)
        val modes = Transport.entries.filter { it != Transport.UNKNOWN }
        for (mode in modes) {
            val modeRoute = route.copy(transport = mode)
            val id = manager.activate(modeRoute, forecasts(modeRoute))
            assertEquals(mode, repository.getJourney(id)?.transport)
            assertEquals(id, repository.activeJourney()?.id)
            assertTrue(repository.journeyPoints(id).isEmpty())
            manager.stop(); awaitManagerTurn()
            repository.finishJourney(id)
        }
        assertEquals(modes, requestedStarts.toList())
    }

    @Test fun activeRunningWeatherUsesOnlyRunningAlertsAndVoicePreferences() = test {
        val store = WeatherSettingsStore(context)
        store.save(defaultWeatherSettings(Transport.RUN).copy(voiceEnabled = true,
            thresholds = WeatherThresholds(hotC = 30.0)), Transport.RUN)
        assertTrue(repository.updateJourneyDetails(first.id, "Run", "", Transport.RUN))
        route = route.copy(transport = Transport.RUN)
        manager.activate(route, forecasts(route, temperature = 39.0))
        acceptOriginFix(); awaitManagerTurn()
        assertTrue(announcements.single().voiceEnabled)
        assertEquals(setOf(WeatherHazard.HEAT), announcements.single().hazards)
        val cleared = clearedAlerts.get()
        store.save(store.read(Transport.MOTORCYCLE).copy(alertsEnabled = false), Transport.MOTORCYCLE)
        manager.preferencesChanged(); awaitManagerTurn()
        assertEquals(cleared, clearedAlerts.get())
        store.save(store.read(Transport.RUN).copy(alertsEnabled = false), Transport.RUN)
        manager.preferencesChanged(); awaitManagerTurn()
        assertTrue(clearedAlerts.get() > cleared)
        assertEquals(RideWeatherStatus.READY, manager.state.value.status)
        assertEquals(1, announcements.size)
    }

    @Test fun blockedForecastDoesNotHoldTrackingMutexOrPreventAcceptedPointPersistence() = test {
        activate()
        val request = beginRefresh()
        val point = TrackPoint(journeyId = first.id, latitude = 41.01, longitude = 29.01,
            recordedAt = clock.get(), accuracy = 5f)
        withTimeout(2_000) {
            TrackingCoordinator.mutex.withLock {
                repository.addPoint(point)
                manager.onAcceptedLocation(first.id, WeatherCoordinate(point.latitude, point.longitude),
                    point.recordedAt, point.accuracy)
            }
        }
        assertFalse("HTTP must remain blocked during the GPS check", request.release.isCompleted)
        assertEquals(listOf(point), repository.journeyPoints(first.id))
        request.release.complete(forecasts(route, temperature = 24.0))
        awaitRequestProcessed(request)
        assertEquals(listOf(point), repository.journeyPoints(first.id))
        assertNull(requireNotNull(repository.getJourney(first.id)).endedAt)
        assertEquals(first.id, repository.activeJourney()?.id)
    }

    @Test fun stopRejectsNonCancellableLateForecastWithoutFinishingDiary() = test {
        activate()
        val request = beginRefresh()
        manager.stop()
        awaitOff()
        request.release.complete(forecasts(route, temperature = 39.0))
        awaitRequestProcessed(request)
        assertEquals(RideWeatherStatus.OFF, manager.state.value.status)
        assertNull(manager.wearWeather(first.id))
        assertNull(requireNotNull(repository.getJourney(first.id)).endedAt)
        assertEquals(first.id, repository.activeJourney()?.id)
    }

    @Test fun restoredSameIdClosesSessionBeforeLateForecastWithoutExplicitStopMaskingIt() = test {
        activate()
        val request = beginRefresh()
        val replacement = first.copy(title = "Restored replacement", note = "Same ID, different diary")
        repository.restore(DiarySnapshot(journeys = listOf(replacement)))
        val persisted = requireNotNull(repository.getJourney(first.id))
        assertEquals("Restored replacement", persisted.title)
        assertNotNull(persisted.endedAt)
        assertTrue(persisted.interrupted)
        assertNull(repository.activeJourney())
        // No explicit tracking-stop hook: repository observation must invalidate the session.
        awaitOff()
        request.release.complete(forecasts(route, temperature = 39.0))
        awaitRequestProcessed(request)
        assertEquals(RideWeatherStatus.OFF, manager.state.value.status)
        assertNull(manager.wearWeather(first.id))
        assertEquals(persisted, repository.getJourney(first.id))
    }

    @Test fun oldTrackingStopAndLateForecastCannotReplaceNewSession() = test {
        activate()
        val oldRequest = beginRefresh()
        repository.finishJourney(first.id)
        awaitOff()
        val second = repository.createJourney(Transport.MOTORCYCLE, false, now = clock.get())
        val secondRoute = makeRoute("second", latitudeOffset = 0.1)
        assertNotEquals(first.id, second.id)
        assertEquals(second.id, manager.activate(secondRoute, forecasts(secondRoute, temperature = 18.0)))
        manager.onTrackingStopped(first.id)
        awaitManagerTurn()
        assertCurrent(second, secondRoute, 18.0)

        oldRequest.release.complete(forecasts(route, temperature = 39.0))
        awaitRequestProcessed(oldRequest)
        assertCurrent(second, secondRoute, 18.0)

        // Positive control: B still accepts its own new forecast after rejecting A's late data.
        val newRequest = beginRefresh()
        newRequest.release.complete(forecasts(secondRoute, temperature = 27.0))
        awaitRequestProcessed(newRequest)
        assertCurrent(second, secondRoute, 27.0)
        manager.onTrackingStopped(second.id)
        awaitOff()
        assertNull(manager.wearWeather(second.id))
        assertNull(requireNotNull(repository.getJourney(second.id)).endedAt)
    }

    @Test fun lateActiveJourneyValidationCannotStopReplacementSession() = test {
        activate()
        val oldRequest = beginRefresh()
        val validation = PendingActiveRead()
        activeReads.add(validation)
        assertTrue(nextActiveRead.compareAndSet(null, validation))
        oldRequest.release.complete(forecasts(route, temperature = 24.0))
        withTimeout(5_000) { validation.entered.await() }

        repository.finishJourney(first.id)
        awaitOff()
        val second = repository.createJourney(Transport.MOTORCYCLE, false, now = clock.get())
        val secondRoute = makeRoute("second-during-validation", latitudeOffset = 0.1)
        assertEquals(second.id, manager.activate(secondRoute, forecasts(secondRoute, temperature = 18.0)))
        assertCurrent(second, secondRoute, 18.0)

        // A had already passed its token check before this suspended source read.
        // Returning the now-active B must not let the obsolete A handler reset B.
        validation.release.complete(second)
        awaitRequestProcessed(oldRequest)
        assertCurrent(second, secondRoute, 18.0)
    }

    @Test fun watchFreshnessUsesForecastRetrievalTimeInsteadOfPackagingTime() = test {
        val fetchedAt = clock.get() - 10 * 60_000
        manager.activate(route, forecasts(route, fetchedAt = fetchedAt))
        acceptOriginFix()
        val before = requireNotNull(manager.wearWeather(first.id))
        assertEquals(fetchedAt, before.calculatedAt)
        assertEquals(fetchedAt + 60 * 60_000, before.validUntil)
        clock.addAndGet(30_000)
        val after = requireNotNull(manager.wearWeather(first.id))
        assertEquals(before.calculatedAt, after.calculatedAt)
        assertEquals(before.validUntil, after.validUntil)
    }

    @Test fun losingGpsDoesNotRelabelCachedForecastAsNewlyRetrieved() = test {
        val fetchedAt = clock.get() - 10 * 60_000
        manager.activate(route, forecasts(route, fetchedAt = fetchedAt))
        acceptOriginFix()
        clock.addAndGet(91_000)
        withTimeout(5_000) { manager.state.first { it.gpsStale } }
        val stale = requireNotNull(manager.wearWeather(first.id))
        assertEquals("GPS loss must not fabricate a new forecast retrieval time",
            fetchedAt, stale.calculatedAt)
        assertEquals(fetchedAt + 60 * 60_000, stale.validUntil)
    }

    @Test fun failedRestoreKeepsActiveSessionAndCurrentDiary() = test {
        activate()
        val before = manager.state.value
        val invalid = DiarySnapshot(journeys = listOf(first, first.copy(title = "Duplicate ID")))
        var rejected = false
        try {
            repository.restore(invalid)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Invalid restore fixture must be rejected before replacement", rejected)
        awaitManagerTurn()
        assertEquals(first.id, repository.activeJourney()?.id)
        assertEquals(before.journeyId, manager.state.value.journeyId)
        assertEquals(before.route?.id, manager.state.value.route?.id)
        assertNotEquals(RideWeatherStatus.OFF, manager.state.value.status)
        assertNotNull(manager.wearWeather(first.id))
    }

    @Test fun threeAccurateOffRouteFixesRerouteThroughOnlyUnvisitedStopsAndApplyWeather() = test {
        val visited = RouteStop("Visited via", WeatherCoordinate(41.005, 29.005))
        val unvisited = RouteStop("Upcoming via", WeatherCoordinate(41.015, 29.015))
        val target = route.stops.last()
        route = route.copy(stops = listOf(route.stops.first(), visited, unvisited, target),
            stopElapsedSeconds = listOf(0.0, 450.0, 1_350.0, 1_800.0))
        activate()
        clock.addAndGet(300_000)
        manager.onAcceptedLocation(first.id, WeatherCoordinate(41.01, 29.01), clock.get(), 5f)
        val halfway = withTimeout(5_000) {
            manager.state.first { !it.gpsStale && it.remainingMeters < 1_500.0 }
        }
        assertEquals("The visited via must be behind current progress", 1_400.0, halfway.remainingMeters, 5.0)

        val offRoute = WeatherCoordinate(41.01, 29.05)
        val replacement = PlannedRoute(
            id = "remaining-route", stops = listOf(RouteStop("New origin", offRoute), unvisited, target),
            vertices = listOf(RouteVertex(offRoute, 0.0),
                RouteVertex(unvisited.coordinate, 900.0), RouteVertex(target.coordinate, 1_800.0)),
            distanceMeters = 3_500.0, durationSeconds = 1_800.0, createdAt = clock.get(),
            stopElapsedSeconds = listOf(0.0, 900.0, 1_800.0),
        )
        plannedReplacement = replacement
        val acceptedAt = mutableListOf<Long>()
        repeat(3) { index ->
            // Each transition acknowledges the previous consumed fix despite the conflated
            // channel. These observations span >=15s; exact 15s boundaries are gate unit tests.
            clock.addAndGet(91_000)
            withTimeout(5_000) { manager.state.first { it.gpsStale } }
            acceptedAt += clock.get()
            manager.onAcceptedLocation(first.id, offRoute, clock.get(), 5f)
            withTimeout(5_000) { manager.state.first { !it.gpsStale } }
            awaitManagerTurn()
            if (index < 2) assertTrue("Fewer than three fixes must not reroute", plannerCalls.isEmpty())
        }
        assertTrue(acceptedAt.last() - acceptedAt.first() >= 15_000)
        val weatherRequest = withTimeout(5_000) { provider.requests.receive() }
        assertEquals(1, plannerCalls.size)
        val (requestedStops, departureAt) = plannerCalls.single()
        assertEquals(offRoute, requestedStops.first().coordinate)
        assertEquals(listOf(unvisited, target), requestedStops.drop(1))
        assertEquals(acceptedAt.last(), departureAt)
        weatherRequest.release.complete(forecasts(replacement, temperature = 26.0))
        awaitRequestProcessed(weatherRequest)

        assertCurrent(first, replacement, 26.0)
        assertEquals(1, plannerCalls.size)
        assertTrue("Planned and rerouted geometry must not be saved as GPS", repository.journeyPoints(first.id).isEmpty())
    }

    @Test fun staleGpsAndForecastSuppressAlertsUntilBothAreFresh() = test {
        activate()
        acceptOriginFix()
        awaitManagerTurn()
        assertTrue(announcements.isEmpty())

        clock.addAndGet(91_000)
        withTimeout(5_000) { manager.state.first { it.gpsStale } }
        val withStaleGps = beginRefresh()
        withStaleGps.release.complete(forecasts(route, temperature = 90.0))
        awaitRequestProcessed(withStaleGps)
        val gpsBlocked = manager.state.value
        assertTrue(gpsBlocked.gpsStale)
        assertTrue(requireNotNull(gpsBlocked.assessment).complete)
        assertTrue(gpsBlocked.assessment!!.samples.any { WeatherHazard.HEAT in it.hazards })
        assertEquals(clock.get(), gpsBlocked.assessment!!.fetchedAt)
        assertTrue("Fresh hazardous forecast still needs fresh GPS", announcements.isEmpty())

        val expiredAt = clock.get() - 61 * 60_000
        manager.activate(route, forecasts(route, temperature = 90.0, fetchedAt = expiredAt))
        acceptOriginFix()
        awaitManagerTurn()
        val forecastBlocked = manager.state.value
        assertFalse(forecastBlocked.gpsStale)
        assertTrue(requireNotNull(forecastBlocked.assessment).complete)
        assertTrue(forecastBlocked.assessment!!.samples.any { WeatherHazard.HEAT in it.hazards })
        assertEquals(expiredAt, forecastBlocked.assessment!!.fetchedAt)
        assertTrue("Fresh GPS still needs a fresh forecast", announcements.isEmpty())

        // beginRefresh advances time once by61s, keeping this last GPS below its90s TTL.
        val fresh = beginRefresh()
        fresh.release.complete(forecasts(route, temperature = 90.0))
        awaitRequestProcessed(fresh)
        assertFalse(manager.state.value.gpsStale)
        val event = announcements.single()
        assertEquals(first.id, event.journeyId)
        assertEquals(setOf(WeatherHazard.HEAT), event.hazards)
        assertEquals(clock.get(), event.assessment.fetchedAt)
        assertTrue(event.assessment.complete)
        assertFalse(event.voiceEnabled)
    }


    @Test fun confirmedDeviationWaitsForInflightForecastThenReroutes() = test {
        assertTrue(repository.updateJourneyDetails(first.id, "Run", "", Transport.RUN))
        route = route.copy(transport = Transport.RUN, travelSpeedKmh = 12.0)
        activate()
        val normal = beginRefresh()
        val offRoute = WeatherCoordinate(41.01,29.05)
        val replacement = PlannedRoute("queued-route",
            listOf(RouteStop("New origin",offRoute),route.stops.last()),
            listOf(RouteVertex(offRoute,0.0),RouteVertex(route.stops.last().coordinate,1_800.0)),
            3_500.0,1_800.0,clock.get())
        plannedReplacement = replacement.copy(transport = Transport.RUN, travelSpeedKmh = 12.0)
        repeat(3) {
            clock.addAndGet(91_000)
            withTimeout(5_000) { manager.state.first { it.gpsStale } }
            manager.onAcceptedLocation(first.id,offRoute,clock.get(),5f)
            withTimeout(5_000) { manager.state.first { !it.gpsStale } }
        }
        assertTrue("Do not overlap route and forecast requests",plannerCalls.isEmpty())
        normal.release.complete(forecasts(route))
        awaitRequestProcessed(normal)
        val replacementWeather = withTimeout(5_000) { provider.requests.receive() }
        assertEquals(1,plannerCalls.size)
        assertEquals(Transport.RUN to 12.0, plannerProfiles.single())
        assertEquals(offRoute,plannerCalls.single().first.first().coordinate)
        replacementWeather.release.complete(forecasts(replacement,temperature=26.0))
        awaitRequestProcessed(replacementWeather)
        assertCurrent(first,replacement,26.0)
    }

    @Test fun voiceOffAndGpsExpiryInvalidatePendingAutomaticSpeech() = test {
        val store = WeatherSettingsStore(context)
        store.save(store.read().copy(voiceEnabled=true))
        manager.activate(route,forecasts(route,temperature=39.0))
        acceptOriginFix()
        awaitManagerTurn()
        assertTrue(announcements.single().voiceEnabled)
        val beforeVoiceOff = voiceSuspensions.get()
        store.save(store.read().copy(voiceEnabled=false))
        manager.refresh().join()
        assertTrue("Turning voice off must cancel pending automated speech",voiceSuspensions.get()>beforeVoiceOff)
        store.save(store.read().copy(voiceEnabled=true))
        manager.refresh().join()
        val beforeStale = voiceSuspensions.get()
        clock.addAndGet(91_000)
        withTimeout(5_000) { manager.state.first { it.gpsStale } }
        // Cancellation must precede the observable stale transition; do not add a scheduler barrier.
        assertTrue("GPS expiry must cancel pending automated speech",voiceSuspensions.get()>beforeStale)
    }


    @Test fun incompleteForecastCannotPublishWatchBelowThresholdState() = test {
        val partial = forecasts(route).map { forecast ->
            forecast.copy(hours=forecast.hours.map { hour ->
                hour.copy(reading=hour.reading.copy(temperatureC=null))
            })
        }
        manager.activate(route,partial)
        acceptOriginFix()
        awaitManagerTurn()
        assertFalse(requireNotNull(manager.state.value.assessment).complete)
        val watch = requireNotNull(manager.wearWeather(first.id))
        assertEquals(org.iz.navigation.wearprotocol.WearWeatherStatus.ERROR,watch.status)
        assertEquals(partial.first().fetchedAt,watch.calculatedAt)
    }

    @Test fun fractionalProgressEndpointHazardCanPublishEveryWatchVersion() = test {
        val duration = 100.763
        val progress = 1.2345
        val origin = route.vertices.first().coordinate
        val destination = route.vertices.last().coordinate
        val shortRoute = route.copy(id = "fractional", durationSeconds = duration,
            vertices = listOf(RouteVertex(origin, 0.0), RouteVertex(destination, duration)))
        manager.activate(shortRoute, forecasts(shortRoute, temperature = 0.0))
        val fraction = progress / duration
        manager.onAcceptedLocation(first.id, WeatherCoordinate(
            origin.latitude + (destination.latitude - origin.latitude) * fraction,
            origin.longitude + (destination.longitude - origin.longitude) * fraction,
        ), clock.get(), 5f)
        withTimeout(5_000) { manager.state.first { !it.gpsStale } }
        val weather = requireNotNull(manager.wearWeather(first.id))
        assertNotNull(weather.hazardStartsAt)
        val snapshot = org.iz.navigation.wearprotocol.WearSnapshot(
            generatedAt = clock.get(), journeyId = first.id,
            mode = org.iz.navigation.wearprotocol.WearMode.MOTORCYCLE,
            startedAt = first.startedAt, recording = true, weather = weather)
        for (version in 1..3) {
            val bytes = org.iz.navigation.wearprotocol.WearProtocol.encodeSnapshot(snapshot, version)
            assertNotNull(org.iz.navigation.wearprotocol.WearProtocol.decodeSnapshot(bytes))
        }
    }


    private fun test(block: suspend () -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) { block() }
    }

    private fun createManager(allowStart: Boolean = false): RideWeatherManager = RideWeatherManager(
        context = context,
        journeySource = object : RideWeatherJourneySource {
            override val journeys get() = repository.journeys
            override suspend fun activeJourney(): Journey? {
                val pending = nextActiveRead.getAndSet(null) ?: return repository.activeJourney()
                return withContext(NonCancellable) {
                    pending.entered.complete(Unit)
                    pending.release.await()
                }
            }
            override suspend fun startJourney(transport: Transport): String {
                check(allowStart) { "Attachment tests must reuse the existing journey" }
                requestedStarts.add(transport)
                return repository.createJourney(transport, false, now = clock.get()).id
            }
        },
        plannerFactory = {
            object : RoutePlanner {
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long,
                    transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                    plannerCalls.add(stops.toList() to departureAt)
                    plannerProfiles.add(transport to travelSpeedKmh)
                    return requireNotNull(plannedReplacement) {
                        "On-route test fixtures must not request a new route"
                    }
                }
            }
        },
        providerFactory = { provider },
        output = object : RideWeatherAlertOutput {
            override fun announce(journeyId: String, hazards: Set<WeatherHazard>,
                assessment: WeatherAssessment, voiceEnabled: Boolean, transport: Transport) {
                announcements.add(Announcement(journeyId, hazards.toSet(), assessment, voiceEnabled))
            }
            override fun clear() { clearedAlerts.incrementAndGet() }
            override fun testVoice() = Unit
            override fun suspendVoice() { voiceSuspensions.incrementAndGet() }
        },
        clock = clock::get,
        tickMillis = 25,
    )

    private suspend fun awaitManagerTurn() {
        // stop/old-stop handlers contain no suspensions and run on Main. This barrier
        // runs after the already-posted handler. It is NOT the network completion barrier.
        withContext(Dispatchers.Main.immediate) { Unit }
    }

    private suspend fun awaitRequestProcessed(request: PendingRequest) {
        withTimeout(5_000) {
            request.returned.await()
            // Captured outside NonCancellable: this is the actual finite manager
            // network job, so join waits past provider return and state assignment.
            request.ownerJob.join()
        }
    }

    private suspend fun acceptOriginFix() {
        manager.onAcceptedLocation(first.id, route.vertices.first().coordinate, clock.get(), 5f)
        withTimeout(5_000) { manager.state.first { it.journeyId == first.id && !it.gpsStale } }
    }

    private suspend fun activate() {
        assertEquals(first.id, manager.activate(route, forecasts(route)))
        withTimeout(5_000) {
            manager.state.first { it.journeyId == first.id && it.status == RideWeatherStatus.READY }
        }
    }

    private suspend fun beginRefresh(): PendingRequest {
        clock.addAndGet(61_000)
        manager.refresh()
        return withTimeout(5_000) { provider.requests.receive() }
    }

    private suspend fun awaitOff() {
        withTimeout(5_000) { manager.state.first { it.status == RideWeatherStatus.OFF } }
    }

    private suspend fun assertCurrent(journey: Journey, planned: PlannedRoute, temperature: Double) {
        val state = manager.state.value
        assertEquals(journey.id, state.journeyId)
        assertEquals(planned.id, state.route?.id)
        assertEquals(temperature, requireNotNull(state.assessment?.minTemperatureC), 0.001)
        assertNotNull(manager.wearWeather(journey.id))
        assertNull(requireNotNull(repository.getJourney(journey.id)).endedAt)
        assertEquals(journey.id, repository.activeJourney()?.id)
    }

    private fun makeRoute(id: String, latitudeOffset: Double = 0.0): PlannedRoute {
        val a = WeatherCoordinate(41.0 + latitudeOffset, 29.0)
        val b = WeatherCoordinate(41.02 + latitudeOffset, 29.02)
        return PlannedRoute(id = id, stops = listOf(RouteStop("Origin", a), RouteStop("Target", b)),
            vertices = listOf(RouteVertex(a, 0.0),
                RouteVertex(WeatherCoordinate(41.01 + latitudeOffset, 29.01), 900.0),
                RouteVertex(b, 1_800.0)), distanceMeters = 2_800.0, durationSeconds = 1_800.0,
            createdAt = clock.get())
    }

    private fun forecasts(planned: PlannedRoute, temperature: Double = 20.0,
        fetchedAt: Long = clock.get()): List<LocationForecast> {
        val hour = clock.get() / 3_600_000 * 3_600_000
        return WeatherEngine.sampleVertices(planned).map { vertex ->
            LocationForecast(vertex.coordinate, (-2..6).map { offset ->
                WeatherHour(hour + offset * 3_600_000L,
                    WeatherReading(temperature, 0.0, 0.0, 5.0, 10.0, 90.0))
            }, fetchedAt)
        }
    }

    private data class Announcement(val journeyId: String, val hazards: Set<WeatherHazard>,
        val assessment: WeatherAssessment, val voiceEnabled: Boolean)

    private class PendingActiveRead {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Journey?>()
    }

    private class PendingRequest(val ownerJob: Job) {
        val release = CompletableDeferred<List<LocationForecast>>()
        val returned = CompletableDeferred<Unit>()
    }

    private class ControlledProvider : WeatherProvider {
        val requests = Channel<PendingRequest>(Channel.UNLIMITED)
        private val outstanding = CopyOnWriteArrayList<PendingRequest>()
        @Volatile private var closing = false

        override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long,
            until: Long): List<LocationForecast> {
            val request = PendingRequest(currentCoroutineContext().job)
            outstanding.add(request)
            if (closing) request.release.complete(emptyList())
            requests.send(request)
            return withContext(NonCancellable) {
                try { request.release.await() }
                finally { request.returned.complete(Unit) }
            }
        }

        fun close() {
            closing = true
            outstanding.forEach { it.release.complete(emptyList()) }
        }

        suspend fun joinRequests() {
            outstanding.forEach { it.ownerJob.join() }
        }
    }
}
