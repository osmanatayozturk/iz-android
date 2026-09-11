package org.iz.navigation.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.car.carWeatherPresentation
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Current-location weather uses the production navigation stream, without GPS or HTTP. */
@RunWith(AndroidJUnit4::class)
class FreeDriveWeatherMovementTest {
    private val origin = WeatherCoordinate(41.0, 29.0)
    private fun north(meters: Double) = origin.copy(latitude = origin.latitude + Math.toDegrees(meters / 6_371_000.0))
    private class Owner : RideWeatherNavigationSource {
        override val state = MutableStateFlow(NavigationState())
        override suspend fun activateRoute(route: PlannedRoute): String = error("No route activation expected")
    }
    private class Request(val coordinates: List<WeatherCoordinate>, val at: Long) {
        val response = CompletableDeferred<List<LocationForecast>>()
    }
    private class Provider : WeatherProvider {
        val requests = Channel<Request>(Channel.UNLIMITED)
        val all = mutableListOf<Request>()
        override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
            val request = Request(coordinates.toList(), from)
            all += request
            requests.send(request)
            // A late provider response must be harmless even if the provider ignores cancellation.
            return withContext(NonCancellable) { request.response.await() }
        }
    }
    private inner class Fixture(tickMillis: Long = 86_400_000L) {
        val start = System.currentTimeMillis()
        val clock = AtomicLong(start)
        val owner = Owner()
        val provider = Provider()
        val journey = Journey(id = "free-drive-movement", transport = Transport.CAR, startedAt = start)
        val manager = RideWeatherManager(ApplicationProvider.getApplicationContext<Context>(),
            journeySource = object : RideWeatherJourneySource {
                override val journeys = emptyFlow<List<Journey>>()
                override suspend fun activeJourney(): Journey? = error("Shared navigation owns the journey")
                override suspend fun startJourney(transport: Transport): String = error("No recording expected")
            }, plannerFactory = { error("Free drive must not plan a route") }, providerFactory = { provider },
            output = object : RideWeatherAlertOutput {
                override fun announce(journeyId: String, hazards: Set<WeatherHazard>, assessment: WeatherAssessment,
                    voiceEnabled: Boolean, transport: Transport) = error("Free drive must not announce route hazards")
                override fun clear() = Unit
                override fun testVoice() = Unit
            }, clock = clock::get, tickMillis = tickMillis, navigationSource = owner)

        suspend fun move(meters: Double, elapsed: Long, trip: Journey = journey) = withContext(Dispatchers.Main.immediate) {
            clock.set(start + elapsed)
            owner.state.value = NavigationState(journey = trip, recording = true, routeRevision = 1,
                fix = NavigationFix(north(meters), clock.get(), 5f), gpsStale = false)
            repeat(6) { yield() }
        }
        suspend fun answer(request: Request, temperature: Double = 20.0) = withContext(Dispatchers.Main.immediate) {
            val hour = request.at / 3_600_000L * 3_600_000L
            val reading = WeatherReading(temperature, 10.0, 0.0, 5.0, 8.0, 0.0)
            request.response.complete(request.coordinates.map { coordinate -> LocationForecast(coordinate,
                (-1..2).map { WeatherHour(hour + it * 3_600_000L, reading) }, request.at) })
            repeat(6) { yield() }
        }
        suspend fun initialForecast() {
            move(0.0, 0)
            val first = provider.requests.receive()
            assertEquals(listOf(origin), first.coordinates)
            answer(first)
            assertTrue(manager.state.value.assessment?.complete == true)
        }
        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            owner.state.value = NavigationState()
            manager.stop()
            provider.all.forEach { it.response.complete(emptyList()) }
            repeat(6) { yield() }
            provider.requests.close()
        }
    }
    private fun test(tickMillis: Long = 86_400_000L, block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(5000) {
            val fixture = withContext(Dispatchers.Main.immediate) { Fixture(tickMillis) }
            try { fixture.block() } finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun cumulativeSmallMovesRefreshFromForecastCoverage() = test {
        initialForecast()
        for (index in 1..4) {
            move(index * 50.0, index * 30_000L)
            provider.requests.tryReceive().getOrNull()?.let { answer(it) }
        }
        val live = manager.state.value
        assertTrue("200 m of small moves must request a fresh location forecast", provider.all.size > 1)
        val assessed = requireNotNull(live.assessment?.samples?.singleOrNull()?.coordinate)
        assertTrue("The assessment must remain within current-position coverage",
            WeatherEngine.distanceMeters(north(200.0), assessed) <= 100.0)
        assertTrue("The latest accepted position must have weather coverage", live.assessment?.complete == true)
        assertFalse(live.gpsStale)
        assertNull(live.route)
        assertNull(live.arrivalAt)
    }

    @Test fun movementDuringCooldownRefreshesOnTickWithoutAnotherFix() = test(tickMillis = 25) {
        initialForecast()
        move(150.0, 45_000)
        assertTrue("Movement must respect the 60-second cooldown", provider.requests.tryReceive().isFailure)
        clock.set(start + 59_999)
        withContext(Dispatchers.Main.immediate) { repeat(6) { yield() } }
        assertTrue(provider.requests.tryReceive().isFailure)
        clock.set(start + 60_000)
        val next = provider.requests.receive()
        assertEquals(listOf(north(150.0)), next.coordinates)
        answer(next)
        assertTrue(manager.state.value.assessment?.complete == true)
        assertFalse(manager.state.value.gpsStale)
    }

    @Test fun delayedForecastCannotEraseRefreshNeededForNewerPosition() = test(tickMillis = 25) {
        initialForecast()
        move(150.0, 60_000)
        val old = provider.requests.receive()
        move(300.0, 90_000)
        answer(old, 7.0)
        assertTrue("No overlapping or early replacement request", provider.requests.tryReceive().isFailure)
        assertFalse("A response 150 m behind must not cover the latest fix", manager.state.value.assessment?.complete == true)
        clock.set(start + 120_000)
        val latest = provider.requests.receive()
        assertEquals(listOf(north(300.0)), latest.coordinates)
        answer(latest, 23.0)
        assertEquals(23.0, manager.state.value.assessment?.maxTemperatureC ?: -1.0, 0.0)
        assertTrue(manager.state.value.assessment?.complete == true)
    }

    @Test fun stationaryForecastKeepsFifteenMinuteRefreshInterval() = test(tickMillis = 25) {
        initialForecast()
        move(20.0, 899_999)
        assertTrue("Nearby movement must not force a fetch", provider.requests.tryReceive().isFailure)
        clock.set(start + 900_000)
        val next = provider.requests.receive()
        assertEquals(listOf(north(20.0)), next.coordinates)
        answer(next)
        assertEquals(2, provider.all.size)
    }

    @Test fun previousJourneyResponseCannotReplaceNewSessionForecast() = test {
        move(0.0, 0)
        val old = provider.requests.receive()
        val replacement = journey.copy(id = "replacement-journey", startedAt = start + 1000)
        move(300.0, 1000, replacement)
        val next = provider.requests.receive()
        answer(next, 23.0)
        answer(old, 7.0)
        assertEquals(replacement.id, manager.state.value.journeyId)
        assertEquals(north(300.0), manager.state.value.assessment?.samples?.singleOrNull()?.coordinate)
        assertEquals(23.0, manager.state.value.assessment?.maxTemperatureC ?: -1.0, 0.0)
        assertEquals(2, provider.all.size)
    }

    @Test fun failedRefreshWithValidCacheIsVisibleInCarAndClearsAfterRecovery() = test {
        initialForecast()
        move(0.0, 60_000)
        val failedRefresh = manager.refresh()
        val failedRequest = provider.requests.receive()
        withContext(Dispatchers.Main.immediate) {
            failedRequest.response.completeExceptionally(IOException("test connection failure"))
        }
        failedRefresh.join()
        val cached = manager.state.value
        assertEquals("An available cached forecast remains READY", RideWeatherStatus.READY, cached.status)
        assertTrue(cached.assessment?.complete == true)
        assertTrue(RideWeatherTiming.forecastFresh(cached.assessment?.fetchedAt, clock.get()))
        assertFalse(cached.gpsStale)
        assertEquals(20.0, cached.assessment?.maxTemperatureC ?: -1.0, 0.0)
        assertTrue(cached.message?.contains("test connection failure") == true)
        val failedPresentation = carWeatherPresentation(cached, clock.get())
        assertTrue("Car must expose refresh failure while retaining the valid cached forecast: ${failedPresentation.summary}",
            failedPresentation.summary.startsWith("Tahmin yenilenemedi"))

        move(0.0, 120_000)
        val recoveredRefresh = manager.refresh()
        answer(provider.requests.receive(), 23.0)
        recoveredRefresh.join()
        val recovered = manager.state.value
        assertEquals(RideWeatherStatus.READY, recovered.status)
        assertTrue(recovered.assessment?.complete == true)
        assertEquals(23.0, recovered.assessment?.maxTemperatureC ?: -1.0, 0.0)
        assertFalse(recovered.message?.contains("test connection failure") == true)
        val recoveredPresentation = carWeatherPresentation(recovered, clock.get())
        assertTrue(recoveredPresentation.summary.startsWith("Mevcut konum"))
        assertFalse(recoveredPresentation.summary.contains("Tahmin yenilenemedi"))
        assertEquals(3, provider.all.size)
    }
}
