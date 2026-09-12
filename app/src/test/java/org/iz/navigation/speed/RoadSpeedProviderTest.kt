package org.iz.navigation.speed

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoadSpeedProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val point = WeatherCoordinate(0.0, .005)
    private val road = OsmRoad(1, listOf(WeatherCoordinate(0.0, 0.0), WeatherCoordinate(0.0, .02)), listOf(1, 2), mapOf("name" to "Atatürk Caddesi"))
    private val fix = NavigationFix(point, 10_000, 3f, 10f, 90f)
    private val match = RoadMatch(road, true, 0, 0.0)
    private fun credentials(revision: String = "one", enabled: Boolean = true) = TrafficCredentials("fictional-key", false, true, revision, true, false, enabled)
    private fun response(name: String = "Atatürk Caddesi", type: String = "Street", position: String = "0.0,0.005") =
        """{"addresses":[{"matchType":"$type","position":"$position","address":{"streetName":"$name","speedLimit":"30.00MPH"}}]}"""

    @Test fun postedResultRequiresStreetNameAndGeometryAgreement() {
        assertEquals(48.28032, parsePostedSpeed(response(), fix, match)!!.kmh, .00001)
        assertNull(parsePostedSpeed(response(name = "Parallel Road"), fix, match))
        assertNull(parsePostedSpeed(response(type = "AddressPoint"), fix, match))
        assertNull(parsePostedSpeed(response(position = "0.0,0.009"), fix, match))
        assertNull(parsePostedSpeed(response(), fix, match.copy(road = road.copy(tags = emptyMap()))))
    }
    @Test fun reverseRequestIsBoundedAndFreeCapabilityRequired() = runBlocking {
        MockWebServer().use { server ->
            var settings = credentials(enabled = false)
            val client = TomTomReverseSpeedClient({ settings }, server.url("/search/2/reverseGeocode/").toString(), OkHttpClient(), { 10_000 }, TrafficRequestGate())
            assertNull(client.lookup(fix, match, "one") { true })
            assertEquals(0, server.requestCount)
            settings = credentials()
            server.enqueue(MockResponse().setBody(response()))
            assertNotNull(client.lookup(fix, match, "one") { true })
            val request = server.takeRequest()
            assertEquals("true", request.requestUrl!!.queryParameter("returnSpeedLimit"))
            assertEquals("20", request.requestUrl!!.queryParameter("radius"))
            assertEquals("true", request.requestUrl!!.queryParameter("returnMatchType"))
            assertEquals("90.0", request.requestUrl!!.queryParameter("heading"))
            assertNull(request.requestUrl!!.queryParameter("entityType"))
            assertNull(request.requestUrl!!.queryParameter("roadUse"))
            assertNull(client.lookup(fix, match, "one") { true })
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun forbiddenStopsUntilSettingsRevisionAndQuotaResponseIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            var now = 10_000L
            var settings = credentials()
            val client = TomTomReverseSpeedClient({ settings }, server.url("/").toString(), OkHttpClient(), { now }, TrafficRequestGate())
            server.enqueue(MockResponse().setResponseCode(403))
            assertNull(client.lookup(fix, match, "one") { true })
            now += 20_000
            assertNull(client.lookup(fix.copy(recordedAt = now), match, "one") { true })
            assertEquals(1, server.requestCount)
            settings = credentials("two")
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            assertNull(client.lookup(fix.copy(recordedAt = now), match, "two") { true })
            now += 20_000
            assertNull(client.lookup(fix.copy(recordedAt = now), match, "two") { true })
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun revokedAuthorizationNeverSendsOrPublishesARequest() = runBlocking {
        MockWebServer().use { server ->
            val client = TomTomReverseSpeedClient({ credentials() }, server.url("/").toString(), OkHttpClient(), { 10_000 }, TrafficRequestGate())
            assertNull(client.lookup(fix, match, "one") { false })
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun inFlightResponseCannotSurviveRoadOrKeyChange() = runBlocking {
        MockWebServer().use { server ->
            var settings = credentials()
            var sameRoad = true
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    sameRoad = false
                    settings = credentials("replacement")
                    return MockResponse().setBody(response())
                }
            }
            val client = TomTomReverseSpeedClient({ settings }, server.url("/").toString(), OkHttpClient(), { 10_000 }, TrafficRequestGate())
            assertNull(client.lookup(fix, match, "one") { sameRoad })
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun cacheIncludesRoadsWithoutSpeedAndExpiresInTwentyFourHours() = runBlocking {
        var now = System.currentTimeMillis()
        var calls = 0
        var query = ""
        val json = """{"elements":[{"type":"way","id":1,"nodes":[1,2],"tags":{"highway":"primary"},"geometry":[{"lat":0,"lon":0},{"lat":0,"lon":0.02}]}]}"""
        val repository = OsmRoadRepository(folder.newFolder(), { query = it; calls++; json }, { now })
        assertEquals(1, repository.nearby(point).roads.size)
        assertFalse(query.contains("[maxspeed"))
        repository.nearby(point)
        assertEquals(1, calls)
        now += 86_400_001
        repository.nearby(point)
        assertEquals(2, calls)
        assertThrows(IllegalArgumentException::class.java) { parseOsmRoads("""{"remark":"runtime error: timeout","elements":[]}""") }
        Unit
    }
    @Test fun sessionChangeAndFreshnessClearLimitsWithoutAnotherGpsRequest() = runBlocking {
        var now = 10_000L
        val withLimit = road.copy(tags = road.tags + ("maxspeed" to "50"))
        val monitor = RoadSpeedMonitor({ RoadRegion(point, listOf(withLimit), now) }, { credentials(enabled = false) },
            TomTomReverseSpeedClient({ credentials(enabled = false) }), { now })
        var state = NavigationState(sessionId = "session", sessionTransport = Transport.CAR, gpsStale = false, fix = fix)
        monitor.observe(state, now); monitor.refresh()
        repeat(3) {
            now += 1_000
            state = state.copy(fix = fix.copy(recordedAt = now))
            monitor.observe(state, now)
        }
        assertEquals(50.0, monitor.observe(state, now).limit!!.valueKmh!!, .001)
        assertNull(monitor.observe(state, now + 5_001).limit)
        assertNull(monitor.observe(state.copy(sessionId = "new"), now).limit)
        assertFalse(monitor.observe(state.copy(sessionTransport = Transport.BICYCLE), now).visible)
    }

    @Test fun currentTomTomRouteCanFillMissingOsmGeometryButFuturePreviewCannot() = runBlocking {
        var now = 10_000L
        val route = PlannedRoute("route", listOf(RouteStop("A", road.points.first()), RouteStop("B", road.points.last())),
            road.points.mapIndexed { index, coordinate -> RouteVertex(coordinate, index * 200.0) }, 2200.0, 200.0, now,
            transport = Transport.MOTORCYCLE, provider = RouteProvider.TOMTOM,
            speedLimits = listOf(RouteSpeedLimitSection(0, 1, 70.0)), effectiveDepartureAt = now)
        val monitor = RoadSpeedMonitor({ RoadRegion(point, emptyList(), now) }, { credentials() },
            TomTomReverseSpeedClient({ credentials() }), { now })
        var state = NavigationState(sessionId = "session", sessionTransport = Transport.MOTORCYCLE,
            gpsStale = false, fix = fix, guidance = true, route = route)
        monitor.observe(state, now); monitor.refresh()
        repeat(3) {
            now += 1_000
            state = state.copy(fix = fix.copy(recordedAt = now))
            monitor.observe(state, now)
        }
        val speed = monitor.observe(state, now).limit!!
        assertEquals(RoadSpeedSource.TOMTOM_ROUTE, speed.source)
        assertFalse(speed.genericPosted)
        assertEquals(70.0, speed.valueKmh!!, .001)
        assertNull(monitor.observe(state.copy(route = route.copy(speedLimits = listOf(RouteSpeedLimitSection(0, 1, Double.NaN)))), now).limit)
        val future = state.copy(route = route.copy(id = "future", effectiveDepartureAt = now + 3_600_000))
        assertNull(monitor.observe(future, now).limit)
        monitor.invalidateCredentials()
        assertNull(monitor.observe(state, now).limit)
    }

    @Test fun genericObservationExpiresByDistanceEvenOnTheSameMatchedRoad() = runBlocking {
        MockWebServer().use { server ->
            var now = 10_000L
            val client = TomTomReverseSpeedClient({ credentials() }, server.url("/").toString(), OkHttpClient(), { now }, TrafficRequestGate())
            val monitor = RoadSpeedMonitor({ RoadRegion(point, listOf(road), now) }, { credentials() }, client, { now })
            var state = NavigationState(sessionId = "session", sessionTransport = Transport.MOTORCYCLE, gpsStale = false, fix = fix)
            monitor.observe(state, now); monitor.refresh()
            repeat(3) { now += 1_000; state = state.copy(fix = fix.copy(recordedAt = now)); monitor.observe(state, now) }
            server.enqueue(MockResponse().setBody(response()))
            monitor.refresh()
            val posted = monitor.observe(state, now).limit!!
            assertTrue(posted.genericPosted)
            assertEquals("Genel yol sınırı · TomTom", roadSpeedPresentation(RoadSpeedState(limit = posted)).sourceText)
            repeat(3) { index ->
                now += 1_000
                state = state.copy(fix = fix.copy(recordedAt = now, coordinate = WeatherCoordinate(0.0, .005 + (index + 1) * .0003)))
                monitor.observe(state, now)
            }
            assertNull(monitor.observe(state, now).limit)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun guidanceNeverRequestsGenericReverseGeocoding() = runBlocking {
        MockWebServer().use { server ->
            var now = 10_000L
            val client = TomTomReverseSpeedClient({ credentials() }, server.url("/").toString(), OkHttpClient(), { now }, TrafficRequestGate())
            val monitor = RoadSpeedMonitor({ RoadRegion(point, listOf(road), now) }, { credentials() }, client, { now })
            var state = NavigationState(sessionId = "session", sessionTransport = Transport.CAR, gpsStale = false, fix = fix, guidance = true)
            monitor.observe(state, now); monitor.refresh()
            repeat(3) { now += 1_000; state = state.copy(fix = fix.copy(recordedAt = now)); monitor.observe(state, now) }
            server.enqueue(MockResponse().setBody(response()))
            monitor.refresh()
            assertNull(monitor.observe(state, now).limit)
            assertEquals(0, server.requestCount)
        }
    }
}
