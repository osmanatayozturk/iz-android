package org.iz.navigation.speed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AutoOsmRoadSpeedResolverTest {
    @get:Rule val folder = TemporaryFolder()
    private val center = WeatherCoordinate(0.0, .005)
    private val road = OsmRoad(1, listOf(WeatherCoordinate(0.0, 0.0), WeatherCoordinate(0.0, .02)),
        listOf(1, 2), mapOf("maxspeed" to "50"))

    @Test fun offlineCacheCanShowOsmLimitAndLocationExpiryClearsBothValues() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { _, shouldRequest ->
                assertEquals(false, shouldRequest())
                RoadRegion(center, listOf(road), clock())
            },
            online = { false },
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val states = mutableListOf<RoadSpeedState>()
        backgroundScope.launch { resolver.states(fixes).collect { states += it } }
        runCurrent()

        repeat(4) {
            fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
            runCurrent()
            if (it < 3) advanceTimeBy(1_000)
        }
        assertEquals(36.0, states.last().ownKmh!!, .001)
        assertEquals(50.0, states.last().limit!!.valueKmh!!, .001)
        assertEquals(RoadSpeedSource.OSM, states.last().limit?.source)

        advanceTimeBy(5_001)
        runCurrent()
        assertNull(states.last().ownKmh)
        assertNull(states.last().limit)
    }

    @Test fun parallelRoadWithoutAValueStillPreventsANumericLimit() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val parallel = OsmRoad(2, listOf(WeatherCoordinate(.00005, 0.0), WeatherCoordinate(.00005, .02)),
            listOf(3, 4), emptyMap())
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { _, _ -> RoadRegion(center, listOf(road, parallel), clock()) },
            online = { false }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val states = mutableListOf<RoadSpeedState>()
        backgroundScope.launch { resolver.states(fixes).collect { states += it } }
        runCurrent()

        repeat(4) {
            fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
            runCurrent()
            assertEquals(36.0, states.last().ownKmh!!, .001)
            assertNull(states.last().limit)
            advanceTimeBy(1_000)
        }
    }

    @Test fun unsupportedOrMissingOsmRestrictionsLeaveOnlyTheOwnSpeed() = runTest {
        val clock = { 100_000L + testScheduler.currentTime }
        for (tags in listOf(emptyMap(), mapOf("maxspeed" to "50", "maxspeed:conditional" to "30 @ wet"))) {
            val fixes = MutableSharedFlow<NavigationFix?>()
            val resolver = AutoOsmRoadSpeedResolver(
                nearby = { _, _ -> RoadRegion(center, listOf(road.copy(tags = tags)), clock()) },
                online = { false }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
            )
            val states = mutableListOf<RoadSpeedState>()
            val owner = backgroundScope.launch { resolver.states(fixes).collect { states += it } }
            runCurrent()
            repeat(4) {
                fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
                runCurrent()
                advanceTimeBy(1_000)
            }
            assertEquals(36.0, states.last().ownKmh!!, .001)
            assertNull(states.last().limit)
            owner.cancel()
        }
    }

    @Test fun lateOsmReplyCannotReplaceTheCurrentLocation() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val later = CompletableDeferred<RoadRegion>()
        val destination = WeatherCoordinate(0.0, .035)
        val destinationRoad = road.copy(id = 2, points = listOf(WeatherCoordinate(0.0, .03), WeatherCoordinate(0.0, .04)),
            tags = mapOf("maxspeed" to "70"))
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { point, shouldRequest ->
                if (point == center) {
                    assertTrue(shouldRequest())
                    later.await()
                } else RoadRegion(destination, listOf(destinationRoad), clock())
            },
            online = { true }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val states = mutableListOf<RoadSpeedState>()
        backgroundScope.launch { resolver.states(fixes).collect { states += it } }
        runCurrent()
        fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
        runCurrent()

        advanceTimeBy(1_000)
        fixes.emit(NavigationFix(destination, clock(), 3f, 18f, 90f))
        runCurrent()
        later.complete(RoadRegion(center, listOf(road), clock()))
        runCurrent()
        assertEquals(64.8, states.last().ownKmh!!, .001)
        assertNull(states.last().limit)

        repeat(7) {
            advanceTimeBy(1_000)
            fixes.emit(NavigationFix(destination, clock(), 3f, 18f, 90f))
            runCurrent()
        }
        assertEquals(70.0, states.last().limit!!.valueKmh!!, .001)
        assertEquals(destination, states.last().limit?.coordinate)
        assertTrue(states.none { it.limit?.valueKmh == 50.0 })
    }

    @Test fun revokedFixCancelsPendingLookupAndClearsOwnSpeed() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val cancelled = CompletableDeferred<Unit>()
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { _, _ -> try { awaitCancellation() } finally { cancelled.complete(Unit) } },
            online = { true }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val states = mutableListOf<RoadSpeedState>()
        backgroundScope.launch { resolver.states(fixes).collect { states += it } }
        runCurrent()
        fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
        runCurrent()

        fixes.emit(null)
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertNull(states.last().limit)
        assertNull(states.last().ownKmh)
    }

    @Test fun repeatedCacheMissesUseTheExistingOsmRetryInterval() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val requests = mutableListOf<Long>()
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { _, shouldRequest ->
                if (shouldRequest()) requests += clock()
                null
            },
            online = { true }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { resolver.states(fixes).collect {} }
        runCurrent()

        repeat(31) {
            fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
            runCurrent()
            if (it < 30) advanceTimeBy(1_000)
        }
        assertEquals(listOf(100_000L, 130_000L), requests)
    }

    @Test fun motorcycleLimitUsesItsDirectionalTagAndRetainsTheVehicleIdentity() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val tagged = road.copy(tags = road.tags + mapOf(
            "maxspeed:motorcar" to "70",
            "maxspeed:motorcycle:forward" to "40",
            "maxspeed:motorcycle:backward" to "25",
        ))
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { _, _ -> RoadRegion(center, listOf(tagged), clock()) },
            online = { false }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val states = mutableListOf<RoadSpeedState>()
        backgroundScope.launch { resolver.states(fixes, Transport.MOTORCYCLE).collect { states += it } }
        runCurrent()
        repeat(3) { index ->
            if (index > 0) advanceTimeBy(1_000)
            fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
            runCurrent()
        }

        val limit = checkNotNull(states.last().limit)
        assertEquals(40.0, limit.valueKmh!!, .001)
        assertEquals(RoadSpeedSource.OSM, limit.source)
        assertEquals(Transport.MOTORCYCLE, limit.transport)
    }

    @Test fun passengerKeepsOwnSpeedWithoutLookingUpRoadsOrCheckingTheNetwork() = runTest {
        val fixes = MutableSharedFlow<NavigationFix?>()
        val clock = { 100_000L + testScheduler.currentTime }
        val resolver = AutoOsmRoadSpeedResolver(
            nearby = { _, _ -> throw AssertionError("Passenger must not start a road lookup") },
            online = { throw AssertionError("Passenger must not check the network") },
            clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val states = mutableListOf<RoadSpeedState>()
        backgroundScope.launch { resolver.states(fixes, Transport.PASSENGER).collect { states += it } }
        runCurrent()
        fixes.emit(NavigationFix(center, clock(), 3f, 10f, 90f))
        runCurrent()

        assertEquals(36.0, states.last().ownKmh!!, .001)
        assertNull(states.last().limit)
        advanceTimeBy(5_001)
        runCurrent()
        assertNull(states.last().ownKmh)
    }

    @Test fun sharedRoadCacheIsReadBeforeNetworkPermissionAndExpiredEntriesAreNotUsed() = runBlocking {
        var now = System.currentTimeMillis()
        var requests = 0
        val repository = OsmRoadRepository(folder.newFolder(), {
            requests++
            """{"elements":[{"type":"way","id":1,"nodes":[1,2],"tags":{"maxspeed":"50"},"geometry":[{"lat":0,"lon":0},{"lat":0,"lon":0.02}]}]}"""
        }, { now })

        assertEquals(listOf(1L), repository.nearby(center).roads.map { it.id })
        val cached = repository.nearby(center) { error("A fresh cache entry must bypass the network gate") }
        assertEquals("50", cached?.roads?.single()?.tags?.get("maxspeed"))

        now += 86_400_001
        assertNull(repository.nearby(center) { false })
        assertEquals(1, requests)
    }
}
