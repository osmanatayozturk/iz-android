package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConfiguredRoutePlannerTest {
    @Test fun walkingRunningAndCyclingNeverReadOrSendTrafficCredentials() = runBlocking {
        val planner = ConfiguredRoutePlanner({ error("No credentials for non-motor modes") }, { fakeRoutePlanner() }, { _, _ -> error("No traffic call") })
        listOf(Transport.WALK, Transport.RUN, Transport.BICYCLE).forEach { mode ->
            val route = planner.plan(trafficStops(), 1L, mode)
            assertEquals(RouteProvider.VALHALLA, route.provider)
            assertNotNull(route.trafficUnavailableReason)
            assertEquals(mode, route.transport)
        }
    }

    @Test fun absentKeyDisabledSettingOrMissingFreeAcknowledgementUsesExplicitFallback() = runBlocking {
        listOf(TrafficCredentials(null, true, true, "1"), TrafficCredentials("fictional-key", false, true, "2"), TrafficCredentials("fictional-key", true, false, "3")).forEach { settings ->
            val planner = ConfiguredRoutePlanner({ settings }, { fakeRoutePlanner() }, { _, _ -> error("No authorized traffic call") })
            val route = planner.plan(trafficStops(), 1L, Transport.CAR)
            assertEquals(RouteProvider.VALHALLA, route.provider)
            assertNotNull(route.trafficUnavailableReason)
        }
    }

    @Test fun providerFailureFallsBackAndDoesNotRetainForeignErrorDetails() = runBlocking {
        val settings = TrafficCredentials("fictional-key", true, true, "1")
        val planner = ConfiguredRoutePlanner({ settings }, { fakeRoutePlanner() }, { _, _ -> fakeRoutePlanner { throw RouteServiceException("https://secret/?key=fictional-key") } })
        val route = planner.plan(trafficStops(), 1L, Transport.MOTORCYCLE)
        assertEquals(RouteProvider.VALHALLA, route.provider)
        assertFalse(route.trafficUnavailableReason!!.contains("fictional-key"))
    }

    @Test fun changedSettingsAndCancellationNeverApplyOldProviderResponseOrFallback() {
        var settings = TrafficCredentials("fictional-key", true, true, "1")
        var fallbackCount = 0
        val planner = ConfiguredRoutePlanner({ settings }, { fakeRoutePlanner { fallbackCount++ } }, { _, _ -> fakeRoutePlanner { settings = TrafficCredentials(null, false, false, "2") } })
        assertThrows(CancellationException::class.java) { runBlocking { planner.plan(trafficStops(), 1L, Transport.CAR) } }
        assertEquals(0, fallbackCount)
        val cancelled = ConfiguredRoutePlanner({ TrafficCredentials("fictional-key", true, true, "3") }, { fakeRoutePlanner { fallbackCount++ } }, { _, _ -> fakeRoutePlanner { throw CancellationException() } })
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.plan(trafficStops(), 1L, Transport.CAR) } }
        assertEquals(0, fallbackCount)
    }

    @Test fun injectedCurrentPositionAllowsSixInternalStops() = runBlocking {
        val stops = (0..5).map { RouteStop("$it", WeatherCoordinate(0.0, it / 1000.0)) }
        assertEquals(6, fakeRoutePlanner().plan(stops, 1L, Transport.CAR).stops.size)
    }

    @Test fun revocationWhileWaitingForSharedGatePreventsAnotherHttpRequest() = runBlocking {
        MockWebServer().use { server ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dispatched = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (dispatched.incrementAndGet() == 1) {
                        arrived.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return MockResponse().setBody(tomTomFixture())
                }
            }
            val gate = TrafficRequestGate()
            val endpoint = server.url("/routing/1/calculateRoute/").toString()
            val first = async(Dispatchers.IO) { TomTomRoutePlanner("fictional-key", endpoint, OkHttpClient(), { 500_000L }, gate).plan(trafficStops(), 1L, Transport.CAR) }
            assertTrue(arrived.await(2, TimeUnit.SECONDS))
            val settings = AtomicReference(TrafficCredentials("fictional-key", true, true, "enabled"))
            val capturedKey = CompletableDeferred<Unit>()
            val configured = ConfiguredRoutePlanner(settings::get, { error("Revoked request must be cancelled") }, { key, ensureAuthorized ->
                capturedKey.complete(Unit)
                TomTomRoutePlanner(key, endpoint, OkHttpClient(), { 500_000L }, gate, ensureAuthorized)
            })
            val queued = async(Dispatchers.IO) { runCatching { configured.plan(trafficStops(), 1L, Transport.CAR) } }
            try {
                withTimeout(2_000) { capturedKey.await() }
                settings.set(TrafficCredentials(null, false, false, "revoked"))
            } finally { release.countDown() }
            first.await()
            assertTrue(queued.await().exceptionOrNull() is CancellationException)
            assertEquals("A revoked captured key must never leave the shared gate", 1, server.requestCount)
        }
    }

    private fun fakeRoutePlanner(before: () -> Unit = {}) = object : RoutePlanner {
        override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?): PlannedRoute {
            before()
            return PlannedRoute("fake", stops, listOf(RouteVertex(stops.first().coordinate, 0.0), RouteVertex(stops.last().coordinate, 100.0)), 444.0, 100.0, 1L, transport = transport, travelSpeedKmh = travelSpeedKmh)
        }
    }
}
