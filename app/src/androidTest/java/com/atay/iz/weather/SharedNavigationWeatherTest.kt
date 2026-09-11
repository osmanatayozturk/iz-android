package com.atay.iz.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.Journey
import com.atay.iz.data.Transport
import com.atay.iz.navigation.NavigationFix
import com.atay.iz.navigation.NavigationProgress
import com.atay.iz.navigation.NavigationState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the same injected navigation stream used in production, without a second GPS owner. */
@RunWith(AndroidJUnit4::class)
class SharedNavigationWeatherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val origin = WeatherCoordinate(41.0, 29.0)
    private val target = WeatherCoordinate(41.02, 29.02)
    private val journey = Journey(id = "shared-weather-test", transport = Transport.CAR, startedAt = now)
    private fun route(id: String) = PlannedRoute(id,
        listOf(RouteStop("A", origin), RouteStop("B", target)),
        listOf(RouteVertex(origin, 0.0), RouteVertex(target, 600.0)),
        2000.0, 600.0, now, transport = Transport.CAR)

    private class Owner : RideWeatherNavigationSource {
        override val state = MutableStateFlow(NavigationState())
        var activations = 0
        var guidanceStarts = 0
        override suspend fun activateRoute(route: PlannedRoute): String {
            activations++
            state.value = state.value.copy(route = route, guidance = false, routeRevision = state.value.routeRevision + 1)
            return requireNotNull(state.value.journey).id
        }
        override suspend fun startGuidance(route: PlannedRoute): String {
            guidanceStarts++
            state.value = state.value.copy(route = route, guidance = true, routeRevision = state.value.routeRevision + 1)
            return requireNotNull(state.value.journey).id
        }
    }
    private class Request(val routeCoordinates: List<WeatherCoordinate>) {
        val response = CompletableDeferred<List<LocationForecast>>()
        val returned = CompletableDeferred<Unit>()
    }
    private class Provider : WeatherProvider {
        val requests = Channel<Request>(Channel.UNLIMITED)
        override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
            val request = Request(coordinates)
            requests.send(request)
            return withContext(NonCancellable) {
                try { request.response.await() } finally { request.returned.complete(Unit) }
            }
        }
    }
    private fun state(route: PlannedRoute?) = NavigationState(journey = journey, recording = true,
        route = route, guidance = route != null, routeRevision = 1,
        fix = NavigationFix(origin, now, 5f), gpsStale = false,
        progress = route?.let { NavigationProgress(100.0, 500.0, 1700.0, 0.0, 0, 200.0) })
    private fun manager(owner: Owner, provider: Provider) = RideWeatherManager(context,
        journeySource = object : RideWeatherJourneySource {
            override val journeys = emptyFlow<List<Journey>>()
            override suspend fun activeJourney(): Journey? = error("Shared state owns journey identity")
            override suspend fun startJourney(transport: Transport): String = error("Shared owner starts recording")
        },
        plannerFactory = { error("Weather must never reroute shared navigation") },
        providerFactory = { provider },
        output = object : RideWeatherAlertOutput {
            override fun announce(journeyId: String, hazards: Set<WeatherHazard>, assessment: WeatherAssessment,
                voiceEnabled: Boolean, transport: Transport) = Unit
            override fun clear() = Unit
            override fun testVoice() = Unit
        }, clock = { now }, tickMillis = 25, navigationSource = owner)

    @Test fun routeAndProgressPublishBeforeWeatherAndSurviveFailure() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(route("first"))
            val request = provider.requests.receive()
            val live = manager.state.first { it.remainingSeconds == 500.0 }
            assertEquals("first", live.route?.id)
            assertEquals(now + 500_000L, live.arrivalAt)
            request.response.completeExceptionally(IllegalStateException("offline"))
            manager.state.first { it.message?.contains("offline") == true }
            assertEquals("first", manager.state.value.route?.id)
            assertEquals(RideWeatherStatus.ERROR, manager.state.value.status)
            assertEquals(500.0, manager.state.value.remainingSeconds ?: -1.0, 0.0)
            assertEquals(now + 500_000L, manager.state.value.arrivalAt)
            assertTrue(owner.state.value.recording)
            assertTrue(owner.state.value.guidance)
            manager.stop()
        }
    }

    @Test fun explicitGuidanceStartReenablesStoppedWeatherOnTheSameJourney() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(route("first")).copy(guidance = false)
            val firstRequest = provider.requests.receive()
            manager.stop()
            manager.state.first { it.status == RideWeatherStatus.OFF }
            firstRequest.response.complete(emptyList())
            firstRequest.returned.await()
            assertEquals(journey.id, manager.activateGuidance(route("second"), emptyList()))
            val resumed = provider.requests.receive()
            assertEquals(1, owner.guidanceStarts)
            assertEquals(0, owner.activations)
            assertTrue(owner.state.value.guidance)
            assertTrue(owner.state.value.recording)
            assertEquals(journey.id, manager.state.value.journeyId)
            assertEquals("second", manager.state.value.route?.id)
            assertNotEquals(RideWeatherStatus.OFF, manager.state.value.status)
            resumed.response.complete(emptyList())
            resumed.returned.await()
            manager.stop()
        }
    }

    @Test fun legacyRouteActivationDoesNotStartSpokenGuidance() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(route("first")).copy(guidance = false)
            val firstRequest = provider.requests.receive()
            firstRequest.response.complete(emptyList())
            firstRequest.returned.await()
            assertEquals(journey.id, manager.activate(route("second"), emptyList()))
            val replacement = provider.requests.receive()
            assertEquals(1, owner.activations)
            assertEquals(0, owner.guidanceStarts)
            assertFalse(owner.state.value.guidance)
            assertTrue(owner.state.value.recording)
            replacement.response.complete(emptyList())
            replacement.returned.await()
            manager.stop()
        }
    }

    @Test fun stoppingWeatherKeepsNavigationAndIgnoresLateWeatherAndFurtherFixes() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(route("first"))
            val request = provider.requests.receive()
            manager.stop()
            manager.state.first { it.status == RideWeatherStatus.OFF }
            owner.state.value = owner.state.value.copy(fix = NavigationFix(target, now + 1, 5f))
            request.response.complete(emptyList())
            request.returned.await()
            withContext(Dispatchers.Main.immediate) { Unit }
            assertEquals(RideWeatherStatus.OFF, manager.state.value.status)
            assertTrue(owner.state.value.recording)
            assertTrue(owner.state.value.guidance)
            assertNull(manager.wearWeather(journey.id))
        }
    }

    @Test fun rerouteRevisionPublishesImmediatelyAndDiscardsOldResponse() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(route("first"))
            val old = provider.requests.receive()
            owner.state.value = state(route("replacement")).copy(routeRevision = 2)
            val replacement = provider.requests.receive()
            assertEquals("replacement", manager.state.value.route?.id)
            old.response.complete(emptyList())
            old.returned.await()
            replacement.response.complete(emptyList())
            replacement.returned.await()
            withContext(Dispatchers.Main.immediate) { Unit }
            assertEquals("replacement", manager.state.value.route?.id)
            assertEquals(0, owner.activations)
            manager.stop()
        }
    }

    @Test fun freeDriveWeatherHasOneCurrentCoordinateAndNoRouteOrArrival() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(null)
            val request = provider.requests.receive()
            assertEquals(listOf(origin), request.routeCoordinates)
            val value = manager.state.first { it.message?.contains("Mevcut konum havası") == true }
            assertNull(value.route)
            assertNull(value.arrivalAt)
            assertNull(value.remainingSeconds)
            request.response.complete(emptyList())
            manager.stop()
        }
    }

    @Test fun noRecordGuidanceKeepsWeatherAcrossRecordingChangesWithoutWearData() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            val sessionId = "navigation-without-diary"
            owner.state.value = state(route("same-route")).copy(journey = null, recording = false,
                sessionId = sessionId, sessionTransport = Transport.CAR, locationActive = true)
            val request = provider.requests.receive()
            assertEquals("same-route", manager.state.value.route?.id)
            assertNull(manager.wearWeather(sessionId))
            owner.state.value = owner.state.value.copy(journey = journey, recording = true)
            withContext(Dispatchers.Main.immediate) { Unit }
            assertFalse(provider.requests.tryReceive().isSuccess)
            request.response.complete(emptyList())
            request.returned.await()
            withContext(Dispatchers.Main.immediate) { Unit }
            assertNotEquals(RideWeatherStatus.OFF, manager.state.value.status)
            assertNotNull(manager.wearWeather(journey.id))
            owner.state.value = owner.state.value.copy(journey = null, recording = false)
            manager.onTrackingStopped(journey.id)
            withContext(Dispatchers.Main.immediate) { Unit }
            assertEquals("same-route", manager.state.value.route?.id)
            assertNotEquals(RideWeatherStatus.OFF, manager.state.value.status)
            assertNull(manager.wearWeather(journey.id))
            assertEquals(0, owner.guidanceStarts)
            assertEquals(0, owner.activations)
            manager.stop()
        }
    }

    @Test fun stoppingNoRecordWeatherStaysOffUntilANewSession() = runBlocking {
        withTimeout(5000) {
            val owner = Owner()
            val provider = Provider()
            val manager = manager(owner, provider)
            owner.state.value = state(route("no-record")).copy(journey = null, recording = false,
                sessionId = "first", sessionTransport = Transport.CAR, locationActive = true)
            val first = provider.requests.receive()
            manager.stop()
            manager.state.first { it.status == RideWeatherStatus.OFF }
            owner.state.value = owner.state.value.copy(fix = NavigationFix(target, now + 1, 5f))
            first.response.complete(emptyList())
            first.returned.await()
            withContext(Dispatchers.Main.immediate) { Unit }
            assertEquals(RideWeatherStatus.OFF, manager.state.value.status)
            owner.state.value = owner.state.value.copy(sessionId = "second")
            val second = provider.requests.receive()
            assertNotEquals(RideWeatherStatus.OFF, manager.state.value.status)
            second.response.complete(emptyList())
            second.returned.await()
            manager.stop()
        }
    }
}
