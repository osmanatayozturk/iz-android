package org.iz.navigation.weather

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.PlannedBreakState
import org.iz.navigation.wearprotocol.WearWeatherStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedBreakWeatherTest {
    private val stops = listOf(
        RouteStop("Start", WeatherCoordinate(41.0, 29.0)),
        RouteStop("First via", WeatherCoordinate(41.01, 29.0), plannedBreakSeconds = 600),
        RouteStop("Second via", WeatherCoordinate(41.02, 29.0), plannedBreakSeconds = 1_200),
        RouteStop("End", WeatherCoordinate(41.04, 29.0)),
    )
    private val driveTimes = listOf(0.0, 900.0, 1_800.0, 3_600.0)
    private val route = PlannedRoute(
        "break-weather", stops, stops.zip(driveTimes) { stop, time -> RouteVertex(stop.coordinate, time) },
        5_000.0, 3_600.0, 0L, driveTimes,
    )

    @Test fun plannedBreaksShiftDownstreamEncountersAndCanExhaustForecastCoverage() {
        val departure = 3_600_000L
        val reading = WeatherReading(17.0, 10.0, 0.0, 5.0, 8.0, 90.0)
        val hours = listOf(WeatherHour(departure, reading), WeatherHour(7_200_000L, reading))
        val forecasts = WeatherEngine.sampleVertices(route).map { LocationForecast(it.coordinate, hours, departure) }

        val assessment = WeatherEngine.assess(route, departure, forecasts, WeatherThresholds())

        assertEquals(listOf(0.0, 900.0, 1_800.0, 2_700.0, 3_600.0),
            assessment.samples.map { it.elapsedSeconds })
        assertEquals(4_500_000L, assessment.samples[1].arrivalAt)
        assertEquals(6_000_000L, assessment.samples[2].arrivalAt)
        assertEquals(9_000_000L, assessment.samples.last().arrivalAt)
        assertFalse(assessment.samples.last().complete)
        assertFalse(assessment.complete)
    }

    @Test fun automaticPauseKeepsPhoneEncountersWhileManualPauseHidesThemAndWearWaits() {
        val reference = 7_200_000L
        val context = RouteScheduleContext(reference, 900.0, mapOf(1 to 300.0, 2 to 1_200.0))
        val automatic = NavigationState(route = route, paused = true, scheduleContext = context,
            plannedBreak = PlannedBreakState(1, "First via", 600, reference - 300_000L, 300.0, 300.0))

        val phoneContext = requireNotNull(rideWeatherScheduleContext(reference, 900.0, automatic))
        val assessment = WeatherEngine.assess(route, context = phoneContext, forecasts = emptyList(),
            thresholds = WeatherThresholds())
        val timing = RouteSchedule.remaining(route, phoneContext)

        assertEquals(8_400_000L, assessment.samples.first { it.elapsedSeconds == 1_800.0 }.arrivalAt)
        assertEquals(11_400_000L, timing.arrivalAt)
        assertEquals(timing.arrivalAt, assessment.samples.last().arrivalAt)
        assertNull(rideWeatherScheduleContext(reference, 900.0, automatic.copy(plannedBreak = null)))
        val live = RideWeatherLiveState(status = RideWeatherStatus.READY,
            assessment = assessment.copy(complete = true, fetchedAt = reference),
            arrivalAt = timing.arrivalAt, paused = true, tripTiming = timing, plannedBreak = automatic.plannedBreak)
        val wear = requireNotNull(live.pausedWearWeather(reference, navigationPaused = automatic.paused))
        assertEquals(WearWeatherStatus.LOADING, wear.status)
        assertNull(wear.arrivalAt)
    }

    @Test fun legacyLiveContextUsesDrivingOnlyAndSharesFractionalArrivalRounding() {
        val context = requireNotNull(rideWeatherScheduleContext(10L, 3_599.9994, NavigationState(route = route)))
        val assessment = WeatherEngine.assess(route, context, emptyList(), WeatherThresholds())

        assertEquals(11L, assessment.samples.single().arrivalAt)
        assertEquals(11L, RouteSchedule.remaining(route, context).arrivalAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun breakRevisionsRetainForecastAndCooldownWhileOvertimeAssessmentKeepsItsCadence() = runTest {
        val start = 3_600_000L
        val clock = { start + testScheduler.currentTime }
        val owner = object : RideWeatherNavigationSource {
            override val state = MutableStateFlow(NavigationState())
            override suspend fun activateRoute(route: PlannedRoute): String = error("The shared owner is already active")
        }
        var fetches = 0
        val reading = WeatherReading(17.0, 10.0, 0.0, 5.0, 8.0, 90.0)
        val hours = (0..4).map { WeatherHour(it * 3_600_000L, reading) }
        val provider = object : WeatherProvider {
            override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
                fetches++
                return coordinates.map { LocationForecast(it, hours, clock()) }
            }
        }
        fun frame(revision: Long, paused: Boolean): NavigationState {
            val now = clock()
            val schedule = RouteScheduleContext(now, 900.0,
                if (paused || revision > 1) mapOf(2 to 1_200.0) else mapOf(1 to 600.0, 2 to 1_200.0))
            return NavigationState(
                route = route, guidance = true, sessionId = "weather-owner", sessionTransport = route.transport,
                routeRevision = revision, paused = paused, gpsStale = false,
                fix = NavigationFix(stops[1].coordinate, now, 4f),
                progress = NavigationProgress(900.0, 2_700.0, 3_000.0, 0.0, 0, 100.0),
                plannedBreak = if (paused) PlannedBreakState(
                    1, stops[1].label, 600, start - 601_000L, (now - start) / 1_000.0 + 601, 0.0,
                ) else null,
                scheduleContext = schedule, tripTiming = RouteSchedule.remaining(route, schedule),
            )
        }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var manager: RideWeatherManager? = null
        try {
            val weather = RideWeatherManager(
                context = ContextWrapper(null),
                journeySource = object : RideWeatherJourneySource {
                    override val journeys = emptyFlow<List<Journey>>()
                    override suspend fun activeJourney(): Journey? = error("Shared navigation owns the journey")
                    override suspend fun startJourney(transport: Transport): String = error("Shared navigation owns recording")
                },
                plannerFactory = { error("Shared navigation owns routing") },
                providerFactory = { provider },
                output = object : RideWeatherAlertOutput {
                    override fun announce(journeyId: String, hazards: Set<WeatherHazard>, assessment: WeatherAssessment,
                        voiceEnabled: Boolean, transport: Transport) = Unit
                    override fun clear() = Unit
                    override fun testVoice() = Unit
                },
                clock = clock, tickMillis = 120_000L, navigationSource = owner, readSettings = ::defaultWeatherSettings,
            ).also { manager = it }
            owner.state.value = frame(1, paused = false)
            runCurrent()
            assertEquals(1, fetches)
            assertTrue(weather.state.value.assessment?.complete == true)

            owner.state.value = frame(2, paused = true)
            runCurrent()
            val breakAssessment = requireNotNull(weather.state.value.assessment)
            assertTrue(breakAssessment.complete)
            assertEquals(start, breakAssessment.fetchedAt)

            repeat(5) {
                advanceTimeBy(1_000L)
                owner.state.value = frame(2, paused = true)
                runCurrent()
                assertEquals(owner.state.value.tripTiming?.arrivalAt, weather.state.value.arrivalAt)
                assertSame(breakAssessment, weather.state.value.assessment)
            }

            advanceTimeBy(RideWeatherTiming.EVALUATE_MS - 5_000L)
            owner.state.value = frame(2, paused = true)
            runCurrent()
            assertEquals(weather.state.value.arrivalAt, weather.state.value.assessment?.samples?.last()?.arrivalAt)
            assertNotSame(breakAssessment, weather.state.value.assessment)

            advanceTimeBy(1_000L)
            owner.state.value = frame(3, paused = false)
            runCurrent()
            assertFalse(weather.state.value.paused)
            assertEquals(3L, weather.state.value.routeRevision)
            assertTrue(weather.state.value.assessment?.complete == true)
            assertEquals(start, weather.state.value.assessment?.fetchedAt)
            assertEquals(1, fetches)
        } finally {
            manager?.stop()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

}
