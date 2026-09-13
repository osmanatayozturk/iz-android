package org.iz.navigation.ui

import org.iz.navigation.data.Transport
import org.iz.navigation.weather.defaultWeatherSettings
import org.iz.navigation.weather.LocationForecast
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RideWeatherSettings
import org.iz.navigation.weather.RoutePlanner
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.SavedWeatherPlan
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPlannerCoordinatorTest {
    @Test fun baseAuthorizationCancellationDoesNotLeaveThePlannerBusy() = runBlocking {
        var revision = "one"
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                revision = "two"
                throw CancellationException("Credentials changed")
            }
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(credentialRevision = { revision }),
            SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        assertNull(coordinator.state.value.route)
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun matrixAuthorizationCancellationInvalidatesWeatherCandidates() = runBlocking {
        var revision = "one"
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("base", stops, departureAt, transport)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(credentialRevision = { revision },
                suggestOrder = { _, _, _ -> revision = "two"; throw CancellationException("Credentials changed") }),
            SavedWeatherPlan(listOf(origin, origin.copy(label = "via 1"), destination.copy(label = "via 2"), destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.suggestStopOrder()!!.join()
        assertNull(coordinator.state.value.route)
        assertTrue(coordinator.state.value.comparisons.isEmpty())
        assertFalse(coordinator.state.value.ordering)
    }

    @Test fun authorizationCancellationInvalidatesThePreviouslyCommittedBundle() = runBlocking {
        var revision = "one"
        var calls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                if (++calls > 1) { revision = "two"; throw CancellationException("Credentials changed") }
                return route("base", stops, departureAt, transport).copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM)
            }
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(credentialRevision = { revision }),
            SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(1_850_000L)
        yield()
        assertNull(coordinator.state.value.route)
        assertTrue(coordinator.state.value.comparisons.isEmpty())
        assertTrue(coordinator.state.value.verifiedDepartures.isEmpty())
        assertNull(coordinator.state.value.requestedDepartureAt)
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun expiredDepartureSelectionCancelsPendingOrderWithoutLeavingControlsBusy() = runBlocking {
        var now = 10_000L
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("base", stops, departureAt, transport)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(clock = { now },
                suggestOrder = { _, _, _ -> waiting.complete(Unit); release.await(); null }),
            SavedWeatherPlan(listOf(origin, origin.copy(label = "via 1"), destination.copy(label = "via 2"), destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.suggestStopOrder()!!
        waiting.await()
        now = 1_900_000L
        coordinator.selectDeparture(1_850_000L)
        release.complete(Unit)
        yield()
        assertFalse(coordinator.state.value.ordering)
        assertFalse(coordinator.state.value.busy)
        assertNull(coordinator.state.value.requestedDepartureAt)
        assertNotNull(coordinator.state.value.error)
    }

    @Test fun slowWeatherDoesNotExtendRouteCacheLifetime() = runBlocking {
        var now = 10_000L
        var calls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("${++calls}", stops, departureAt, transport)
                .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM, effectiveDepartureAt = departureAt)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(clock = { now },
                weatherProvider = { object : WeatherProvider {
                    override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
                        now += 121_000L
                        return emptyList()
                    }
                } }), SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(50_000L)
        yield()
        assertEquals(1, calls)
        assertNotNull(coordinator.state.value.error)
        assertEquals(50_000L, coordinator.state.value.effectiveDepartureAt)
    }

    @Test fun credentialsChangedDuringSelectedForecastClearOldAndPendingCandidates() = runBlocking {
        var revision = "one"
        var forecasts = 0
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("$departureAt", stops, departureAt, transport)
                .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(credentialRevision = { revision },
                weatherProvider = { object : WeatherProvider {
                    override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
                        if (++forecasts == 2) { waiting.complete(Unit); release.await() }
                        return emptyList()
                    }
                } }), SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(1_850_000L)
        waiting.await()
        revision = "two"
        release.complete(Unit)
        yield()
        assertNull(coordinator.state.value.route)
        assertNull(coordinator.state.value.requestedDepartureAt)
        assertTrue(coordinator.state.value.verifiedDepartures.isEmpty())
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun selectedWeatherWaitsForItsOwnForecastAndEditsDiscardPendingBundle() = runBlocking {
        var plans = 0
        var forecasts = 0
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("route-${++plans}", stops, departureAt, transport)
                .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM, effectiveDepartureAt = departureAt)
        }
        val service = services(planner, { _, departure -> comparisons(departure, true, 1.0) }).copy(
            weatherProvider = { object : WeatherProvider {
                override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
                    if (++forecasts == 2) { waiting.complete(Unit); release.await() }
                    return emptyList()
                }
            } },
        )
        val coordinator = WeatherPlannerCoordinator(this, service, SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(1_850_000L)
        waiting.await()
        assertEquals("route-1", coordinator.state.value.route?.id)
        assertEquals(50_000L, coordinator.state.value.selectedDepartureAt)
        assertEquals(1_850_000L, coordinator.state.value.requestedDepartureAt)
        coordinator.setStops(listOf(origin, destination.copy(label = "Changed")))
        release.complete(Unit)
        yield()
        assertNull(coordinator.state.value.route)
        assertNull(coordinator.state.value.requestedDepartureAt)
        assertTrue(coordinator.state.value.verifiedDepartures.isEmpty())
    }

    @Test fun verifyingOneRowLeavesOtherApproximationsOnTheBaseRoute() = runBlocking {
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("$departureAt", stops, departureAt, transport)
                .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM, effectiveDepartureAt = departureAt)
        }
        val coordinator = WeatherPlannerCoordinator(this, services(planner, { _, departure -> comparisons(departure, true, 100.0) }),
            SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        val untouched = coordinator.state.value.comparisons[2]
        coordinator.selectDeparture(1_850_000L)
        yield()
        assertEquals(untouched, coordinator.state.value.comparisons[2])
        assertFalse(coordinator.state.value.selectedAssessment!!.complete)
        assertTrue(1_850_000L in coordinator.state.value.verifiedDepartures)
        assertEquals(1_850_000L, coordinator.state.value.selectedDepartureAt)
    }

    @Test fun credentialChangeInvalidatesEvenACachedVerifiedDeparture() = runBlocking {
        var revision = "one"
        var calls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("${++calls}", stops, departureAt, transport)
                .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(credentialRevision = { revision }),
            SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        revision = "two"
        coordinator.selectDeparture(50_000L)
        yield()
        assertNull(coordinator.state.value.route)
        assertTrue(coordinator.state.value.verifiedDepartures.isEmpty())
        assertEquals(1, calls)
    }
    @Test fun selectingAnotherDepartureReplansAndCommitsItsOwnGeometry() = runBlocking {
        val calls = mutableListOf<Long>()
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                calls += departureAt
                return route("route-${calls.size}", stops, departureAt, transport)
                    .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM)
            }
        }
        val coordinator = WeatherPlannerCoordinator(this, services(planner, { _, departure -> comparisons(departure, true, 0.0) }),
            SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(1_850_000L)
        yield()
        assertEquals(listOf(50_000L, 1_850_000L), calls)
        assertEquals("route-2", coordinator.state.value.route?.id)
        assertEquals(1_850_000L, coordinator.state.value.selectedDepartureAt)
        coordinator.selectDeparture(50_000L)
        yield()
        assertEquals("route-1", coordinator.state.value.route?.id)
        assertEquals(2, calls.size)
    }

    @Test fun failedSelectedDeparturePreservesTheCommittedRouteAndTime() = runBlocking {
        var count = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                if (++count > 1) error("Route unavailable")
                return route("base", stops, departureAt, transport)
                    .copy(provider = org.iz.navigation.weather.RouteProvider.TOMTOM)
            }
        }
        val coordinator = WeatherPlannerCoordinator(this, services(planner, { _, departure -> comparisons(departure, true, 0.0) }),
            SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(1_850_000L)
        yield()
        assertEquals("base", coordinator.state.value.route?.id)
        assertEquals(50_000L, coordinator.state.value.selectedDepartureAt)
        assertNotNull(coordinator.state.value.error)
    }

    @Test fun approximateRecommendationDoesNotSelectAnUncalculatedDeparture() = runBlocking {
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("base", stops, departureAt, transport)
        }
        val coordinator = WeatherPlannerCoordinator(this, services(planner, { _, departure ->
            comparisons(departure, true, 100.0).mapIndexed { index, item ->
                item.copy(exceededSeconds = if (index == 3) 0.0 else 100.0)
            }
        }), SavedWeatherPlan(listOf(origin, destination), 50_000L))
        coordinator.calculate()!!.join()
        assertEquals(50_000L, coordinator.state.value.selectedDepartureAt)
    }
    @Test fun cancelledConfiguredStartClearsStartingAndCanBeRetried() = runBlocking {
        var calls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                calls++
                if (calls == 2) throw CancellationException("Traffic settings changed")
                return route("route-$calls", stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }),
            SavedWeatherPlan(listOf(origin, destination), 60_000L))
        coordinator.calculate()!!.join()
        coordinator.startFromCurrentLocation(origin.coordinate)!!.join()
        assertFalse("A cancelled settings revision must release Start", coordinator.state.value.starting)
        assertFalse(coordinator.state.value.busy)
        assertTrue(coordinator.state.value.canStart)
        coordinator.startFromCurrentLocation(origin.coordinate)!!.join()
        assertEquals("journey", coordinator.state.value.activeJourneyId)
    }

    @Test fun failedDraftSaveCannotStartRecordingWithoutNavigationHandoff() = runBlocking {
        var failSave = false
        var activations = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("route", stops, departureAt, transport)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) },
                activate = { _, _ -> activations++; "journey" }).copy(savePlan = { if (failSave) error("Disk full") }),
            SavedWeatherPlan(listOf(origin, destination), 60_000L))
        coordinator.calculate()!!.join()
        failSave = true
        coordinator.startFromCurrentLocation(origin.coordinate)!!.join()
        assertEquals("Persist the user draft before starting the recorder", 0, activations)
        assertEquals("Disk full", coordinator.state.value.error)
        assertFalse(coordinator.state.value.starting)
        assertNull(coordinator.state.value.activeJourneyId)
    }

    @Test fun departureComparisonAndExplicitStartUseTheirSeparatePlanners() = runBlocking {
        var comparisonCalls = 0
        var startCalls = 0
        var activated: PlannedRoute? = null
        val comparisonPlanner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                comparisonCalls++
                return route("comparison", stops, departureAt, transport)
            }
        }
        val startPlanner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                startCalls++
                return route("configured-live", stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(comparisonPlanner, { _, departure -> comparisons(departure, true, 0.0) },
                activate = { route, _ -> activated = route; "journey" }).copy(startRoutePlanner = { startPlanner }),
            SavedWeatherPlan(listOf(origin, destination), 60_000L))
        coordinator.calculate()!!.join()
        assertEquals("comparison", coordinator.state.value.route?.id)
        assertEquals(7, coordinator.state.value.comparisons.size)
        coordinator.startFromCurrentLocation(origin.coordinate)!!.join()
        assertEquals("configured-live", activated?.id)
        assertEquals(1, comparisonCalls)
        assertEquals(1, startCalls)
        assertEquals("journey", coordinator.consumeNavigationStarted())
        assertNull("Returning to the planner must not replay navigation handoff", coordinator.consumeNavigationStarted())
        assertNull(coordinator.state.value.activeJourneyId)
    }

    @Test fun currentLocationOriginRefreshesWithoutBecomingAnExtraVia() = runBlocking {
        val saved = mutableListOf<SavedWeatherPlan>()
        var activated: PlannedRoute? = null
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("route", stops, departureAt, transport)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) },
                activate = { route, _ -> activated = route; "journey" }).copy(savePlan = { saved += it }),
            SavedWeatherPlan(listOf(origin.copy(label = "Mevcut konum"), destination), 60_000L, originUsesCurrentLocation = true))
        coordinator.calculate()!!.join()
        val current = WeatherCoordinate(41.1, 29.1)
        coordinator.startFromCurrentLocation(current)!!.join()
        assertEquals(2, activated?.stops?.size)
        assertEquals(current, activated?.stops?.first()?.coordinate)
        assertEquals(current, saved.last().stops.first().coordinate)
        assertEquals(destination, saved.last().stops.last())
    }

    @Test fun startPreservesCustomOriginAndViasWithoutSavingInjectedCurrentStop() = runBlocking {
        val plannedStops = listOf(origin, RouteStop("Mola", WeatherCoordinate(40.5, 29.0)), destination)
        val saved = mutableListOf<SavedWeatherPlan>()
        var activated: PlannedRoute? = null
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("route", stops, departureAt, transport)
        }
        val coordinator = WeatherPlannerCoordinator(this,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) },
                activate = { route, _ -> activated = route; "journey" }).copy(savePlan = { saved += it }),
            SavedWeatherPlan(plannedStops, 60_000L))
        coordinator.calculate()!!.join()
        val current = WeatherCoordinate(41.1, 29.1)
        coordinator.startFromCurrentLocation(current)!!.join()
        assertEquals(current, activated?.stops?.first()?.coordinate)
        assertEquals("Custom origin and every via remain on the started route", plannedStops, activated?.stops?.drop(1))
        assertEquals(plannedStops, saved.last().stops)
        assertEquals(plannedStops, coordinator.state.value.stops)
    }

    @Test fun failedStartWhilePreviewWeatherWaitsClearsBusyAndAllowsRetry() = runBlocking {
        val weatherGate = CompletableDeferred<Unit>()
        var routeCalls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                routeCalls++
                if (routeCalls == 2) error("Start route unavailable")
                return route("route-$routeCalls", stops, departureAt, transport)
            }
        }
        val service = services(planner, { _, departure -> comparisons(departure, true, 0.0) })
            .copy(weatherProvider = { object : WeatherProvider {
                override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long,
                    until: Long): List<LocationForecast> {
                    weatherGate.await()
                    return emptyList()
                }
            } })
        val coordinator = WeatherPlannerCoordinator(this, service,
            SavedWeatherPlan(listOf(origin, destination), 60_000L))
        val preview = coordinator.calculate()!!
        yield()
        assertTrue(coordinator.state.value.busy)
        coordinator.startFromCurrentLocation(origin.coordinate)!!.join()
        weatherGate.complete(Unit)
        preview.join()
        assertFalse("Invalidated weather request must not leave calculation busy", coordinator.state.value.busy)
        assertFalse(coordinator.state.value.starting)
        assertEquals("Start route unavailable", coordinator.state.value.error)
        assertTrue(coordinator.state.value.canStart)
        coordinator.startFromCurrentLocation(origin.coordinate)!!.join()
        assertEquals("journey", coordinator.state.value.activeJourneyId)
    }

    @Test fun startsWhilePreviewWeatherIsStillWaitingAndLatePreviewCannotOverwriteActivation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var activated: PlannedRoute? = null
        var weatherCalls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = route("fresh", stops, departureAt, transport)
        }
        val service = services(planner, { _, departure -> comparisons(departure, true, 0.0) },
            activate = { route, forecasts ->
                assertTrue("Forecast HTTP must not gate recording", forecasts.isEmpty())
                activated = route
                "recording"
            }).copy(weatherProvider = { object : WeatherProvider {
                override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long,
                    until: Long): List<LocationForecast> {
                    weatherCalls++
                    gate.await()
                    return emptyList()
                }
            } })
        val coordinator = WeatherPlannerCoordinator(this, service,
            SavedWeatherPlan(listOf(origin, destination), 60_000L))
        val preview = coordinator.calculate()!!
        yield()
        val fresh = WeatherCoordinate(41.02, 29.02)
        coordinator.startFromCurrentLocation(fresh)!!.join()
        assertEquals(fresh, activated?.stops?.first()?.coordinate)
        assertEquals("recording", coordinator.state.value.activeJourneyId)
        assertEquals(1, weatherCalls)
        gate.complete(Unit)
        preview.join()
        assertEquals(fresh, coordinator.state.value.route?.stops?.first()?.coordinate)
        assertEquals("recording", coordinator.state.value.activeJourneyId)
    }

    @Test fun routeDurationAppearsBeforeWeatherAndSurvivesWeatherFailure() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val stops = listOf(RouteStop("A", WeatherCoordinate(41.0, 29.0)), RouteStop("B", WeatherCoordinate(41.1, 29.1)))
        val planned = route("early-route", stops, 50_000L)
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences) = planned
        }
        val service = services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(
            weatherProvider = { object : WeatherProvider {
                override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
                    gate.await()
                    error("weather unavailable")
                }
            } },
        )
        val coordinator = WeatherPlannerCoordinator(this, service, SavedWeatherPlan(stops, 50_000L))
        val job = coordinator.calculate()!!
        yield()
        assertEquals(planned.durationSeconds, coordinator.state.value.route?.durationSeconds)
        assertTrue(coordinator.state.value.busy)
        assertTrue("Route start must not wait for forecast HTTP", coordinator.state.value.canStart)
        gate.complete(Unit)
        job.join()
        assertEquals(planned, coordinator.state.value.route)
        assertTrue(coordinator.state.value.canStart)
        assertEquals("weather unavailable", coordinator.state.value.error)
    }
    private val origin = RouteStop("Başlangıç", WeatherCoordinate(41.0082, 28.9784))
    private val destination = RouteStop("Varış", WeatherCoordinate(40.1885, 29.0610))

    @Test
    fun olderRequestCannotOverwriteNewerPlan() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                if (departureAt == 1_000L) firstGate.await()
                return route(departureAt.toString(), stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(
            scope = scope,
            initialPlan = SavedWeatherPlan(listOf(origin, destination), 1_000L),
            services = services(
                planner = planner,
                clock = { 0L },
                compare = { planned, departure ->
                    comparisons(departure, complete = true, exceeded = planned.id.toDouble())
                },
            ),
        )

        val first = coordinator.calculate()
        coordinator.setDeparture(2_000L)
        val second = coordinator.calculate()
        second?.join()
        assertEquals("2000", coordinator.state.value.route?.id)

        firstGate.complete(Unit)
        first?.join()
        assertEquals("2000", coordinator.state.value.route?.id)
        assertEquals(2_000L, coordinator.state.value.selectedDepartureAt)
        scope.cancel()
    }

    @Test
    fun startAcceptsIncompletePreviewAndRecalculatesFromFreshOriginAtNow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var returnComplete = false
        val plannerCalls = mutableListOf<Pair<List<RouteStop>, Long>>()
        var activatedRoute: PlannedRoute? = null
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                plannerCalls += stops to departureAt
                return route("route-${plannerCalls.size}", stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(
            scope = scope,
            initialPlan = SavedWeatherPlan(listOf(origin, destination), 60_000L),
            services = services(
                planner = planner,
                compare = { _, departure ->
                    comparisons(departure, complete = returnComplete, exceeded = 0.0)
                },
                activate = { route, _ -> activatedRoute = route; "moto-1" },
            ),
        )

        assertNull(coordinator.startFromCurrentLocation(WeatherCoordinate(39.0, 32.0)))
        coordinator.calculate()?.join()
        assertTrue(coordinator.state.value.canStart)
        assertNull(activatedRoute)
        val freshOrigin = WeatherCoordinate(41.015, 28.979)
        coordinator.startFromCurrentLocation(freshOrigin)?.join()

        assertEquals(50_000L, plannerCalls.last().second)
        assertEquals(freshOrigin, plannerCalls.last().first.first().coordinate)
        assertEquals("moto-1", coordinator.state.value.activeJourneyId)
        assertEquals("route-2", activatedRoute?.id)
        assertTrue(coordinator.state.value.error == null)
        scope.cancel()
    }

    @Test
    fun editsAreIgnoredWhileFreshStartOwnsActivation() = runBlocking {
        val startGate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                calls++
                if (calls == 2) startGate.await()
                return route("route-$calls", stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(
            scope = scope,
            initialPlan = SavedWeatherPlan(listOf(origin, destination), 60_000L),
            services = services(
                planner = planner,
                compare = { _, departure -> comparisons(departure, complete = true, exceeded = 0.0) },
            ),
        )
        coordinator.calculate()?.join()
        val start = coordinator.startFromCurrentLocation(WeatherCoordinate(41.01, 29.01))
        assertTrue(coordinator.state.value.starting)
        assertNull("A repeated Start cannot launch another route request", coordinator.startFromCurrentLocation(origin.coordinate))
        assertEquals(2, calls)

        coordinator.setDeparture(90_000L)
        coordinator.setStops(listOf(origin, destination.copy(label = "Başka hedef")))
        coordinator.selectDeparture(60_000L)
        coordinator.setTransport(Transport.WALK)
        assertEquals(Transport.MOTORCYCLE, coordinator.state.value.transport)
        assertEquals(60_000L, coordinator.state.value.departureAt)

        startGate.complete(Unit)
        start?.join()
        assertTrue(!coordinator.state.value.starting)
        assertEquals("journey", coordinator.state.value.activeJourneyId)
        scope.cancel()
    }

    @Test
    fun modeSettingsStayIndependentAndSavedPlanRestoresModeAndPace() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = Transport.entries.filter { it != Transport.UNKNOWN }.associateWith(::defaultWeatherSettings).toMutableMap()
        val requests = mutableListOf<Pair<Transport, Double?>>()
        val savedPlans = mutableListOf<SavedWeatherPlan>()
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                requests += transport to travelSpeedKmh
                return route("route", stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(
            scope,
            services(planner, { _, departure -> comparisons(departure, true, 0.0) }).copy(
                readSettings = { settings.getValue(it) },
                saveSettings = { value, transport -> settings[transport] = value },
                savePlan = { savedPlans += it },
            ),
            SavedWeatherPlan(listOf(origin, destination), 60_000L, Transport.RUN),
        )
        assertEquals(Transport.RUN, coordinator.state.value.transport)
        val runningSettings = coordinator.state.value.settings.copy(travelSpeedKmh = 12.0, voiceEnabled = true, alertsEnabled = false)
        assertTrue(coordinator.saveSettings(runningSettings))
        coordinator.calculate()?.join()
        assertEquals(Transport.RUN to 12.0, requests.last())
        assertEquals(Transport.RUN, savedPlans.last().transport)
        coordinator.setTransport(Transport.WALK)
        assertNull(coordinator.state.value.route)
        assertEquals(listOf(origin, destination), coordinator.state.value.stops)
        assertEquals(60_000L, coordinator.state.value.departureAt)
        assertEquals(defaultWeatherSettings(Transport.WALK), coordinator.state.value.settings)
        coordinator.calculate()?.join()
        assertEquals(Transport.WALK to 5.1, requests.last())
        coordinator.setTransport(Transport.RUN)
        assertEquals(runningSettings, coordinator.state.value.settings)
        assertEquals(defaultWeatherSettings(Transport.MOTORCYCLE), settings.getValue(Transport.MOTORCYCLE))
        scope.cancel()
    }

    @Test
    fun earlierModeResponseCannotRestorePreviousRoute() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute {
                if (transport == Transport.MOTORCYCLE) gate.await()
                return route(transport.name, stops, departureAt, transport)
            }
        }
        val coordinator = WeatherPlannerCoordinator(
            scope, services(planner, { _, departure -> comparisons(departure, true, 0.0) }),
            SavedWeatherPlan(listOf(origin, destination), 60_000L),
        )
        val first = coordinator.calculate()
        coordinator.setTransport(Transport.BICYCLE)
        assertTrue(!coordinator.state.value.busy)
        coordinator.calculate()?.join()
        gate.complete(Unit)
        first?.join()
        assertEquals(Transport.BICYCLE, coordinator.state.value.transport)
        assertEquals(Transport.BICYCLE, coordinator.state.value.route?.transport)
        assertTrue(coordinator.state.value.canStart)
        scope.cancel()
    }

    @Test
    fun allSixModesReachActivationWithoutChangingSelectedTransport() = runBlocking {
        for (mode in Transport.entries.filter { it != Transport.UNKNOWN }) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var activated: Transport? = null
            val planner = object : RoutePlanner {
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute =
                    route("route", stops, departureAt, transport)
            }
            val coordinator = WeatherPlannerCoordinator(
                scope, services(planner, { _, departure -> comparisons(departure, true, 0.0) },
                    activate = { route, _ -> activated = route.transport; mode.name }),
                SavedWeatherPlan(listOf(origin, destination), 60_000L, mode),
            )
            coordinator.calculate()?.join()
            assertTrue(coordinator.state.value.canStart)
            coordinator.startFromCurrentLocation(WeatherCoordinate(41.01, 29.01))?.join()
            assertEquals(mode, activated)
            assertEquals(mode.name, coordinator.state.value.activeJourneyId)
            scope.cancel()
        }
    }

    @Test
    fun providerReturningDifferentTransportCannotEnableStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute =
                route("wrong-mode", stops, departureAt, Transport.MOTORCYCLE)
        }
        val coordinator = WeatherPlannerCoordinator(
            scope, services(planner, { _, departure -> comparisons(departure, true, 0.0) }),
            SavedWeatherPlan(listOf(origin, destination), 60_000L, Transport.WALK),
        )
        coordinator.calculate()?.join()
        assertNull(coordinator.state.value.route)
        assertTrue(!coordinator.state.value.canStart)
        assertTrue(coordinator.state.value.error?.contains("yolculuk türüyle") == true)
        scope.cancel()
    }

    @Test
    fun recommendationIgnoresIncompleteCandidatesAndBreaksTiesByEarlierDeparture() {
        val comparisons = listOf(
            assessment(1_000L, complete = false, exceeded = 0.0),
            assessment(2_000L, complete = true, exceeded = 90.0),
            assessment(3_000L, complete = true, exceeded = 30.0),
            assessment(4_000L, complete = true, exceeded = 30.0),
        )

        assertEquals(3_000L, recommendedAssessment(comparisons)?.departureAt)
        assertNull(recommendedAssessment(comparisons.filterNot { it.complete }))
    }

    private fun services(
        planner: RoutePlanner,
        compare: (PlannedRoute, Long) -> List<WeatherAssessment>,
        clock: () -> Long = { 50_000L },
        activate: suspend (PlannedRoute, List<LocationForecast>) -> String = { _, _ -> "journey" },
    ) = WeatherPlannerServices(
        routePlanner = { planner },
        weatherProvider = { object : WeatherProvider {
            override suspend fun hourly(
                coordinates: List<WeatherCoordinate>,
                from: Long,
                until: Long,
            ): List<LocationForecast> = emptyList()
        } },
        readSettings = ::defaultWeatherSettings,
        saveSettings = { _, _ -> },
        savePlan = {},
        activate = activate,
        compare = { route, departure, _, _ -> compare(route, departure) },
        clock = clock,
    )

    private fun comparisons(departure: Long, complete: Boolean, exceeded: Double) =
        List(7) { index -> assessment(departure + index * 30L * 60L * 1_000L, complete, exceeded) }
    private fun route(id: String, stops: List<RouteStop>, createdAt: Long, transport: Transport = Transport.MOTORCYCLE) = PlannedRoute(
        id = id,
        stops = stops,
        vertices = listOf(
            RouteVertex(stops.first().coordinate, 0.0),
            RouteVertex(stops.last().coordinate, elapsedSeconds = 3_600.0),
        ),
        distanceMeters = 120_000.0,
        durationSeconds = 3_600.0,
        createdAt = createdAt,
        transport = transport,
        hasHighway = false,
    )

    private fun assessment(departure: Long, complete: Boolean, exceeded: Double) = WeatherAssessment(
        departureAt = departure,
        samples = emptyList(),
        exceededSeconds = exceeded,
        complete = complete,
        minTemperatureC = if (complete) 8.0 else null,
        maxTemperatureC = if (complete) 18.0 else null,
        maxPrecipitationProbabilityPercent = if (complete) 20.0 else null,
        maxWindKmh = if (complete) 12.0 else null,
        maxGustKmh = if (complete) 20.0 else null,
        fetchedAt = if (complete) 900L else null,
    )
}
