package org.iz.navigation.navigation

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.health.HealthPrivacyActivity
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.PlannedBreakCard
import org.iz.navigation.weather.LocationForecast
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteSchedule
import org.iz.navigation.weather.RouteScheduleContext
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import org.iz.navigation.weather.WeatherHour
import org.iz.navigation.weather.WeatherReading
import org.iz.navigation.weather.WeatherThresholds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production coordinator with synthetic GPS/speech and an isolated in-memory Room recorder. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlannedBreakScenarioTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 90_000)
    fun twoPlannedBreaksUpdateWeatherAndResumeCuesWhileRecordingContinues(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals("org.iz.navigation", app.packageName)
        assertEquals("true", args.getString("izMolaliRota"))
        val candidate = requireNotNull(args.getString("izCandidateCommit"))
        check(candidate.matches(Regex("[0-9a-f]{40}")))
        assertTrue("Owned Android emulator required", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertFalse("The real recorder must be idle", TrackingService.isRunning)

        val root = File(checkNotNull(app.getExternalFilesDir(null)), "qa/molali-rota/$candidate")
        check(root.mkdirs()) { "Use a fresh evidence directory" }
        val evidence = JSONObject().put("sourceArgument", candidate)
            .put("gps", "SYNTHETIC_FIXES").put("recorder", "IN_MEMORY_ROOM_ADAPTER")
            .put("speech", "SYNTHETIC_CUE_DELIVERY_NOT_AUDIBLE").put("status", "STARTED")
        val db = Room.inMemoryDatabaseBuilder(app, DiaryDatabase::class.java).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val now = AtomicLong(DEPARTURE)
        val runtime = SyntheticRuntime(db, now::get)
        val coordinator = JourneyNavigationCoordinator(runtime, cache = null, clock = now::get, scope = scope,
            monotonicClock = { now.get() - DEPARTURE })
        val route = route()
        val explicitResume = CompletableDeferred<Boolean>()
        var phase = "planned"

        try {
            val planned = RouteSchedule.planned(route, DEPARTURE)
            assertEquals(2_700.0, planned.drivingSeconds, .001)
            assertEquals(300.0, planned.breakSeconds, .001)
            assertEquals(3_000.0, planned.totalSeconds, .001)
            assertEquals(at(3_000), planned.arrivalAt)
            val stops = RouteSchedule.stops(route, DEPARTURE)
            assertEquals(listOf(at(0), at(900), at(1_920), at(3_000)), stops.map { it.arrivalAt })
            assertEquals(listOf(at(0), at(1_020), at(2_100), at(3_000)), stops.map { it.departureAt })
            assertWeather(route, RouteScheduleContext(DEPARTURE), at(3_000), secondViaAt = at(1_920))
            evidence.put("planned", timing(planned.arrivalAt, planned.breakSeconds))

            val intent = Intent().setComponent(ComponentName(app.packageName, HealthPrivacyActivity::class.java.name))
            ActivityScenario.launch<HealthPrivacyActivity>(intent).use { host ->
                host.onActivity { activity ->
                    check(activity.javaClass == HealthPrivacyActivity::class.java)
                    activity.setContent {
                        val state by coordinator.state.collectAsState()
                        IzTheme {
                            state.plannedBreak?.let { active ->
                                PlannedBreakCard(active, enabled = true, onResume = {
                                    val owner = checkNotNull(NavigationStopSnapshot.from(state))
                                    scope.launch { explicitResume.complete(coordinator.resumePlannedBreak(owner)) }
                                })
                            }
                        }
                    }
                }
                phase = "recorder readiness"
                val session = withContext(Dispatchers.Main.immediate) { coordinator.startGuidance(route, recordJourney = true) }
                withTimeout(5_000) {
                    coordinator.state.first { it.sessionId == session && it.recording && it.locationActive }
                }
                val submitted = mutableListOf<Long>()

                suspend fun send(wallSeconds: Int, drivingSeconds: Int, speed: Float): NavigationState {
                    val fix = NavigationFix(coordinate(drivingSeconds), at(wallSeconds), 4f, speed, 0f)
                    withContext(Dispatchers.Main.immediate) {
                        check(now.get() <= fix.recordedAt)
                        now.set(fix.recordedAt)
                        runtime.record(session, fix)
                        submitted += fix.recordedAt
                        coordinator.onAcceptedLocation(session, fix)
                    }
                    val state = withTimeout(5_000) { coordinator.state.first { it.fix?.recordedAt == fix.recordedAt } }
                    assertEquals(session, state.sessionId)
                    assertEquals(session, state.journey?.id)
                    assertEquals(session, runtime.recordingId())
                    assertTrue("Guidance pause retains recording and GPS", state.recording && state.locationActive)
                    assertFalse("Synthetic adapter is not the production simulation mode", state.simulation)
                    return state
                }

                suspend fun reachBreak(stop: Int, arrival: Int, elapsed: Int): NavigationState {
                    for (offset in 0..36 step 9) assertNull(send(arrival + offset, elapsed, 0f).plannedBreak)
                    val beforePause = runtime.cancellations.get()
                    val confirmed = send(arrival + 45, elapsed, 0f)
                    assertTrue("Confirming this break cancels speech", runtime.cancellations.get() > beforePause)
                    assertBreak(confirmed, stop, at(arrival), 45.0)
                    return confirmed
                }

                phase = "initial cue"
                send(0, 0, 12f)
                runtime.awaitCue("sağa dönün")
                val initialCueCount = runtime.cues.size

                phase = "first break"
                val first = reachBreak(1, 900, 900)
                assertLive(route, first, at(3_000), 255.0, secondViaAt = at(1_920))
                send(954, 900, 0f)
                val firstAtResume = send(960, 900, 0f)
                assertBreak(firstAtResume, 1, at(900), 60.0)
                assertEquals(initialCueCount, runtime.cues.size)
                capture(root, "first-break.png")
                resumeNode().performClick()
                assertTrue(withTimeout(5_000) { explicitResume.await() })
                val early = coordinator.state.value
                assertResumed(early, session)
                assertLive(route, early, at(2_940), 180.0, secondViaAt = at(1_860))
                evidence.put("early", timing(checkNotNull(early.tripTiming).arrivalAt, 180.0))
                resumeNode().assertDoesNotExist()
                send(970, 1_030, 12f)
                runtime.awaitCue("sola dönün", at(960))

                phase = "second break and overtime"
                val beforeSecond = runtime.cues.size
                val second = reachBreak(2, 1_860, 1_800)
                assertLive(route, second, at(2_940), 135.0)
                for (offset in 54..180 step 9) {
                    val held = send(1_860 + offset, 1_800, 0f)
                    assertBreak(held, 2, at(1_860), offset.toDouble())
                    if (offset == 180) assertLive(route, held, at(2_940), 0.0)
                }
                val overtime = send(2_045, 1_800, 0f)
                assertBreak(overtime, 2, at(1_860), 185.0)
                assertLive(route, overtime, at(2_945), 0.0)
                assertEquals(beforeSecond, runtime.cues.size)
                evidence.put("overtime", timing(checkNotNull(overtime.tripTiming).arrivalAt, 0.0))
                capture(root, "second-break-overtime.png")

                phase = "motion resumes guidance"
                assertTrue(send(2_046, 1_910, 3f).paused)
                assertTrue(send(2_049, 1_920, 3f).paused)
                assertEquals("No cues before sustained movement", beforeSecond, runtime.cues.size)
                val moving = send(2_052, 1_930, 3f)
                assertResumed(moving, session)
                resumeNode().assertDoesNotExist()
                send(2_053, 1_940, 3f)
                runtime.awaitCue("keskin sola dönün", at(2_052))

                phase = "recording continuity"
                val owner = checkNotNull(NavigationStopSnapshot.from(coordinator.state.value))
                assertTrue(withContext(Dispatchers.Main.immediate) { coordinator.stopGuidance(owner) })
                val recordingOnly = coordinator.state.value
                assertFalse(recordingOnly.guidance)
                assertTrue(recordingOnly.recording && recordingOnly.locationActive)
                assertEquals(session, recordingOnly.journey?.id)
                send(2_054, 1_950, 3f)
                val points = db.diaryDao().allPoints()
                assertEquals(submitted, points.map { it.recordedAt })
                assertEquals(setOf(session), points.map { it.journeyId }.toSet())
                assertEquals(1, runtime.starts.get())
                assertEquals(0, runtime.finishes.get())
                assertNull(db.diaryDao().allJourneys().single().endedAt)
                assertFalse(TrackingService.isRunning)
                evidence.put("sameJourneyPoints", points.size).put("cueCount", runtime.cues.size)
                    .put("cueTimes", JSONArray(runtime.cues.map { it.at }))
                withContext(Dispatchers.Main.immediate) { coordinator.finishSession(session) }
            }
            evidence.put("status", "ASSERTIONS_PASS")
        } catch (failure: Throwable) {
            evidence.put("status", "FIRST_NONPASS").put("phase", phase)
                .put("failure", failure.message?.take(500))
            throw failure
        } finally {
            withContext(NonCancellable) {
                job.cancelAndJoin()
                db.close()
                File(root, "scenario.json").writeText(evidence.toString(2))
            }
        }
    }

    private fun resumeNode() = compose.onNodeWithTag("planned-break-resume")

    private fun capture(root: File, name: String) {
        resumeNode().assertIsDisplayed()
        compose.onNodeWithTag("planned-break-elapsed").assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun assertBreak(state: NavigationState, stop: Int, startedAt: Long, elapsed: Double) {
        assertTrue(state.guidance && state.paused)
        val active = checkNotNull(state.plannedBreak)
        assertEquals(stop, active.stopIndex)
        assertEquals(startedAt, active.startedAt)
        assertEquals(elapsed, active.elapsedSeconds, .001)
        assertEquals(if (stop == 1) 120 else 180, active.plannedSeconds)
        assertEquals((active.plannedSeconds - elapsed).coerceAtLeast(0.0), active.remainingSeconds, .001)
    }

    private fun assertResumed(state: NavigationState, session: String) {
        assertTrue(state.guidance && !state.paused && state.recording && state.locationActive)
        assertNull(state.plannedBreak)
        assertEquals(session, state.sessionId)
    }

    private fun assertLive(
        route: PlannedRoute, state: NavigationState, arrival: Long, remainingBreaks: Double, secondViaAt: Long? = null,
    ) {
        assertEquals(route, state.route)
        val progress = checkNotNull(state.progress)
        assertEquals(route.durationSeconds - progress.elapsedSeconds, progress.remainingSeconds, .001)
        val actual = checkNotNull(state.tripTiming)
        assertEquals("Arrival stays anchored to the independent clock schedule", arrival.toDouble(), actual.arrivalAt.toDouble(), 2.0)
        assertEquals(remainingBreaks, actual.breakSeconds, .001)
        assertWeather(route, checkNotNull(state.scheduleContext), actual.arrivalAt, secondViaAt)
    }

    private fun assertWeather(
        route: PlannedRoute, context: RouteScheduleContext, arrival: Long, secondViaAt: Long? = null,
    ) {
        val reading = WeatherReading(18.0, 0.0, 0.0, 12.0, 20.0, 0.0)
        val forecasts = WeatherEngine.sampleVertices(route).map { vertex ->
            LocationForecast(vertex.coordinate, listOf(WeatherHour(DEPARTURE, reading), WeatherHour(at(3_600), reading)), DEPARTURE)
        }
        val thresholds = WeatherThresholds()
        val assessment = if (context.breakSecondsByStop == null) {
            WeatherEngine.assess(route, context.referenceAt, forecasts, thresholds)
        } else WeatherEngine.assess(route, context, forecasts, thresholds)
        secondViaAt?.let { expected ->
            val atVia = assessment.samples.single { it.elapsedSeconds == 1_800.0 }
            assertEquals(expected.toDouble(), atVia.arrivalAt.toDouble(), 2.0)
            assertTrue(atVia.complete)
        }
        val sample = assessment.samples.last()
        assertEquals(route.durationSeconds, sample.elapsedSeconds, .001)
        assertEquals(arrival, sample.arrivalAt)
        assertTrue(sample.complete)
    }

    private fun timing(arrival: Long, breaks: Double) = JSONObject().put("arrivalAt", arrival).put("breakSeconds", breaks)

    private class SyntheticRuntime(private val db: DiaryDatabase, private val clock: () -> Long) : NavigationRuntime {
        private val dao = db.diaryDao()
        override val journeys = dao.journeys()
        @Volatile private var active: String? = null
        val starts = AtomicInteger()
        val finishes = AtomicInteger()
        val cancellations = AtomicInteger()
        data class Cue(val at: Long, val text: String)
        val cues = CopyOnWriteArrayList<Cue>()

        override suspend fun activeJourney() = dao.allJourneys().singleOrNull { it.endedAt == null }
        override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
            check(stillCurrent() && active == null)
            val id = "qa-two-breaks"
            dao.save(Journey(id = id, transport = transport, startedAt = clock()))
            active = id
            starts.incrementAndGet()
            return id
        }
        override suspend fun confirm(id: String, transport: Transport) = error("No temporary fixture journey")
        override suspend fun finish(id: String) {
            check(id == active)
            dao.save(checkNotNull(activeJourney()).copy(endedAt = clock()))
            active = null
            finishes.incrementAndGet()
        }
        override suspend fun interrupt(id: String) = error("Fixture journey was interrupted")
        override fun recordingId() = active
        override fun setHighFrequency(journeyId: String?) = Unit
        override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute = error("No live route provider")
        override suspend fun locate(): NavigationFix = error("No device GPS")
        override fun render(state: NavigationState) = Unit
        override fun speak(text: String) = error("No native TTS")
        override fun cancelSpeech() { cancellations.incrementAndGet() }
        override fun speakCue(request: NavigationCueRequest) {
            if (request.isCurrent()) {
                cues += Cue(clock(), request.text)
                request.finish(CueDelivery.COMPLETED)
            } else request.finish(CueDelivery.REJECTED)
        }

        suspend fun record(owner: String, fix: NavigationFix) {
            check(owner == active)
            dao.save(TrackPoint(id = "point-${fix.recordedAt}", journeyId = owner,
                latitude = fix.coordinate.latitude, longitude = fix.coordinate.longitude,
                recordedAt = fix.recordedAt, accuracy = fix.accuracyMeters, speed = fix.speedMps))
        }

        suspend fun awaitCue(text: String, earliest: Long = DEPARTURE) = withTimeout(5_000) {
            while (cues.none { it.at >= earliest && it.text.contains(text, ignoreCase = true) }) delay(20)
        }
    }

    private companion object {
        const val DEPARTURE = 1_799_309_600_000L
        fun at(seconds: Int) = DEPARTURE + seconds * 1_000L
        fun coordinate(seconds: Int) = WeatherCoordinate(41.0 + seconds * .000005, 29.0)
        fun route(): PlannedRoute {
            val stops = listOf(RouteStop("Başlangıç", coordinate(0)), RouteStop("Birinci mola", coordinate(900), 120),
                RouteStop("İkinci mola", coordinate(1_800), 180), RouteStop("Varış", coordinate(2_700)))
            val vertexTimes = listOf(0, 200, 900, 1_320, 1_800, 2_220, 2_700)
            val maneuvers = listOf(1 to 10, 3 to 15, 5 to 14).map { (index, type) ->
                RouteManeuver(type, "Sentetik dönüş", "Sentetik dönüş", emptyList(), index, index + 1,
                    vertexTimes[index].toDouble(), vertexTimes[index + 1].toDouble())
            }
            return PlannedRoute("qa-two-break-route", stops, vertexTimes.map { RouteVertex(coordinate(it), it.toDouble()) },
                WeatherEngine.distanceMeters(stops.first().coordinate, stops.last().coordinate), 2_700.0, DEPARTURE,
                stopElapsedSeconds = listOf(0.0, 900.0, 1_800.0, 2_700.0), transport = Transport.MOTORCYCLE,
                maneuvers = maneuvers)
        }
    }
}
