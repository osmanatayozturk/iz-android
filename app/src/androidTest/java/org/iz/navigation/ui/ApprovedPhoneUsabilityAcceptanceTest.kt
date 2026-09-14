package org.iz.navigation.ui

import android.content.Intent
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.PlaceSource
import org.iz.navigation.navigation.NavigationStopSnapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real MainActivity/default-client acceptance, not the Phone callback suite.
 * Run only on the assigned offline AVD, after final source/APK allocation, with:
 * -e izApprovedAcceptance isolated-offline -e izCandidateCommit <full-final-commit>
 * -e izExpectedAvd <allocated-isolated-avd>
 *
 * No live recording, group/auth, physical-device power button or final UI/UX acceptance is claimed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ApprovedPhoneUsabilityAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: ApprovedPhoneUsabilityFixture
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity

    @Before fun guardExistingDataBeforeLaunchingActivity() {
        fixture = ApprovedPhoneUsabilityFixture(ApplicationProvider.getApplicationContext<IzApplication>())
        fixture.prepare()
    }

    @After fun releaseOnlyOwnedSyntheticState() {
        try { scenario?.close() } finally {
            if (::fixture.isInitialized) fixture.close()
        }
    }

    @Test fun defaultSearchInDiaryPersistsSelectedOsmPlaceAndOnlyItsVisit() = evidenceCase("diary_receiver") {
        fixture.seedSearch()
        launch()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-add-place").performClick()
        compose.onNodeWithText("OSM’de yer eşleştir").performScrollTo().performClick()
        searchAndSelectSecondBuilding()
        compose.onNodeWithText("Bu ziyarete özel not").performScrollTo().performTextReplacement(fixture.visitNote)
        closeSoftKeyboard()
        compose.onNodeWithText("Ziyareti kaydet").performClick()

        compose.waitUntil(10_000) {
            fixture.io { fixture.repository.snapshot().visits.any { it.note == fixture.visitNote } }
        }
        val snapshot = fixture.io { fixture.repository.snapshot() }
        val visit = snapshot.visits.single { it.note == fixture.visitNote }
        val place = snapshot.places.single { it.id == visit.placeId }
        assertEquals(fixture.selected.name, place.name)
        assertEquals(fixture.selected.latitude, requireNotNull(place.latitude), 0.0)
        assertEquals(fixture.selected.longitude, requireNotNull(place.longitude), 0.0)
        assertEquals(fixture.selected.osmRef?.type, place.osmType)
        assertEquals(fixture.selected.osmRef?.id, place.osmId)
        assertEquals(PlaceSource.OSM, place.source)
        assertNull(visit.journeyId)
        assertFalse(snapshot.places.any { it.osmId == fixture.first.osmRef?.id })
        fixture.assertSearchStayedCached()
        fixture.capture("diary_saved", JSONObject().put("placeId", place.id).put("visitId", visit.id)
            .put("osmId", place.osmId).put("latitude", place.latitude).put("longitude", place.longitude))
    }

    // IME-001 red evidence: Test's 2e1b441/801695d reproduction, 320dp/font2/Gboard.
    // These tests require that matrix to be allocated externally; they never change device settings.
    @Test fun nestedSearchEmptyKeepsActionsTouchableAboveIme() =
        verifyNestedSearchImeClose(localError = false)

    @Test fun nestedSearchLocalErrorKeepsActionsTouchableAboveIme() =
        verifyNestedSearchImeClose(localError = true)

    private fun verifyNestedSearchImeClose(localError: Boolean) = evidenceCase(
        if (localError) "nested_search_error_ime" else "nested_search_empty_ime",
    ) {
        val config = fixture.app.resources.configuration
        assertEquals("Allocate the real 320dp display before running IME-001", 320, config.screenWidthDp)
        assertEquals("Allocate actual system font2, not a CompositionLocal", 2f, config.fontScale, .01f)
        assertTrue("IME-001 requires the allocated Gboard",
            android.provider.Settings.Secure.getString(fixture.app.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
                .startsWith("com.google.android.inputmethod.latin/"))
        val baseline = fixture.io { fixture.repository.snapshot() }
        val query = if (localError) "a".repeat(201) else fixture.seedEmptySearch()
        launch()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-add-place").performClick()
        compose.onNodeWithText("Bu ziyarete özel not").performScrollTo().performTextReplacement(fixture.visitNote)
        closeSoftKeyboard() // Only setup: the search's IME must remain open through Ara and Kapat.
        compose.onNodeWithText("OSM’de yer eşleştir").performScrollTo().performClick()
        waitForTag("osm-search-query")
        compose.onNodeWithTag("osm-search-query").performTextReplacement(query).performClick()
        compose.waitUntil(5_000) { OsmDialogImeTouch.keyboardIsVisible() }
        val stateTag = if (localError) "osm-search-error" else "osm-search-empty"
        fixture.capture("ime_before_submit", OsmDialogImeTouch.tapButton("Ara", reveal = true))
        waitForTag(stateTag)
        if (localError) compose.onNodeWithTag(stateTag).assertTextEquals("Arama en fazla 200 karakter olabilir.")
        // Fail before any workaround: the whole fixed footer hit area must already be above IME.
        fixture.capture("ime_after_result", OsmDialogImeTouch.buttonFacts("Kapat"))
        compose.onNodeWithTag("osm-search-query").assertTextContains(query)
        fixture.capture("ime_resubmit", OsmDialogImeTouch.tapButton("Ara", reveal = true))
        waitForTag(stateTag)
        fixture.assertSearchStayedCached()
        fixture.capture("ime_before_close", OsmDialogImeTouch.buttonFacts("Kapat"))
        OsmDialogImeTouch.tapButton("Kapat") // Real screen DOWN/UP, never semantics OnClick.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("osm-search-query").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(fixture.visitNote).assertExists()
        compose.onNodeWithText("Ziyareti kaydet").assertExists()
        assertEquals("Closing search must not save/delete a visit", baseline, fixture.io { fixture.repository.snapshot() })
        fixture.assertSearchStayedCached()
    }

    @Test fun defaultSearchReachesRealDirectionsDestinationAndPreservesOtherStops() = evidenceCase("route_receiver") {
        fixture.seedSearch()
        launch()
        compose.onNodeWithTag("open-navigation").performClick()
        waitForTag("navigation-screen")
        val vm = navigationViewModel()
        scenario!!.onActivity {
            vm.directions.setOrigin(fixture.origin)
            vm.directions.setDestination(fixture.previousDestination)
            vm.directions.addVia(fixture.via)
        }
        compose.onNodeWithTag("directions-destination").performScrollTo().performClick()
        compose.onNodeWithText("OpenStreetMap'te ara").performClick()
        searchAndSelectSecondBuilding()

        compose.runOnIdle {
            assertEquals(fixture.selectedStop, vm.ui.value.destination)
            assertEquals(fixture.origin, vm.ui.value.origin)
            assertEquals(listOf(fixture.via), vm.ui.value.via)
            assertFalse(vm.ui.value.originCurrent)
            assertNull(fixture.app.navigation.state.value.sessionId)
            assertFalse(fixture.app.navigation.state.value.guidance)
        }
        assertFalse(windowKeepsScreenOn())
        fixture.assertSearchStayedCached()
        fixture.capture("route_destination", JSONObject().put("destination", vm.ui.value.destination.toString())
            .put("origin", vm.ui.value.origin.toString()).put("via", vm.ui.value.via.toString()))
    }

    @Test fun defaultSearchReachesRealWeatherStopWithoutRequestingForecast() = evidenceCase("weather_receiver") {
        fixture.seedSearch()
        launch()
        compose.onNodeWithTag("home-weather").performClick()
        waitForTag("weather_planner")
        lateinit var vm: WeatherPlannerViewModel
        scenario!!.onActivity {
            vm = ViewModelProvider(it)[WeatherPlannerViewModel::class.java]
            vm.setStops(listOf(fixture.origin, fixture.via, fixture.previousDestination))
        }
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasText("Varış"))
        compose.onAllNodesWithText("Kayıtlı / Ara / Harita").onLast().performScrollTo().performClick()
        compose.onNodeWithText("OpenStreetMap'te ara").performClick()
        searchAndSelectSecondBuilding()
        compose.runOnIdle {
            assertEquals(listOf(fixture.origin, fixture.via, fixture.selectedStop), vm.state.value.stops)
            assertNull(vm.state.value.route)
            assertTrue(vm.state.value.forecasts.isEmpty())
            assertNull(vm.state.value.activeJourneyId)
            assertNull(fixture.app.navigation.state.value.sessionId)
        }
        assertFalse(windowKeepsScreenOn())
        fixture.assertSearchStayedCached()
        fixture.capture("weather_destination", JSONObject().put("stops", vm.state.value.stops.toString())
            .put("forecastCalculated", false))
    }

    @Test fun realActiveCardAndNereyePreserveSimulatedSessionAndPlanningChoices() = evidenceCase("main_menu") {
        launch()
        fixture.startSimulation()
        waitForTag("live-guidance-card")
        waitForScreenFlag(true)
        val vm = navigationViewModel()
        scenario!!.onActivity {
            vm.directions.setOrigin(fixture.origin)
            vm.directions.setDestination(fixture.previousDestination)
            vm.directions.addVia(fixture.via)
        }
        val session = NavigationStopSnapshot.from(fixture.app.navigation.state.value)
        val route = fixture.app.navigation.state.value.route
        val planned = vm.ui.value
        for (entry in listOf("live-guidance-card", "open-navigation")) {
            compose.onNodeWithTag(entry).performClick()
            waitForTag("navigation-screen")
            compose.onNodeWithTag("active-session-actions").assertExists()
            compose.runOnIdle {
                assertEquals(session, NavigationStopSnapshot.from(fixture.app.navigation.state.value))
                assertEquals(route, fixture.app.navigation.state.value.route)
                assertEquals(planned.stops, vm.ui.value.stops)
                assertEquals(planned.preferences, vm.ui.value.preferences)
                assertEquals(planned.recordJourney, vm.ui.value.recordJourney)
            }
            assertTrue(windowKeepsScreenOn())
            fixture.capture("panel_from_" + entry.replace('-', '_'), windowFacts())
            compose.onNodeWithTag("close-directions-planner").performScrollTo().performClick()
            compose.onNodeWithTag("navigation-screen").assertDoesNotExist()
            assertEquals(session, NavigationStopSnapshot.from(fixture.app.navigation.state.value))
        }
        // Uses the real shared coordinator with a simulation, not evidence of a live recorder/group.
        assertTrue(fixture.app.navigation.state.value.simulation)
    }

    @Test fun actualActivityWindowTracksForegroundAndEndingSimulatedGuidance() = evidenceCase("main_window") {
        launch()
        assertFalse(windowKeepsScreenOn())
        fixture.startSimulation()
        waitForScreenFlag(true)
        fixture.capture("window_resumed", windowFacts())
        val beforeBackground = NavigationStopSnapshot.from(fixture.app.navigation.state.value)

        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitForScreenFlag(false)
        assertEquals(beforeBackground, NavigationStopSnapshot.from(fixture.app.navigation.state.value))
        fixture.capture("window_background", windowFacts())
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitForScreenFlag(true)
        fixture.capture("window_resumed_again", windowFacts())

        assertTrue(fixture.stopOwnedGuidance())
        waitForScreenFlag(false)
        assertFalse(fixture.app.navigation.state.value.guidance)
        fixture.capture("window_guidance_ended", windowFacts())
        // FLAG/lifecycle evidence only; the separate paired test measures actual display state.
    }

    @Test fun pairedActualTimeoutAndShellSleepExcludeAlwaysAwakeRunner() = evidenceCase("paired_timeout") {
        launch()
        fixture.withPairedTimeoutControls {
            assertFalse(windowKeepsScreenOn())
            val baseline = fixture.observeScreen("paired_no_guidance_before")
            // A runner/charging/device policy that prevents real screen-off invalidates the experiment.
            assumeTrue("No-guidance negative control did not turn the display off; inspect power evidence",
                baseline.sawDisplayOff)
            fixture.wakeAllocatedScreen()
            waitForTag("navigation-home")
            fixture.startSimulation()
            waitForScreenFlag(true)
            val session = NavigationStopSnapshot.from(fixture.app.navigation.state.value)
            assertTrue("Guidance must retain an actually ON interactive display past the same timeout",
                fixture.observeScreen("paired_guidance").allInteractive)
            assertEquals(session, NavigationStopSnapshot.from(fixture.app.navigation.state.value))
            assertTrue(windowKeepsScreenOn())

            compose.onNodeWithTag("open-navigation").performClick()
            waitForTag("active-session-actions")
            assertTrue(fixture.observeScreen("paired_guidance_panel").allInteractive)
            compose.onNodeWithTag("stop-navigation-guidance").performScrollTo().performClick()
            waitForTag("confirm-session-end")
            assertTrue(fixture.observeScreen("paired_guidance_confirmation").allInteractive)
            compose.onNodeWithTag("cancel-session-end").performClick()
            assertEquals(session, NavigationStopSnapshot.from(fixture.app.navigation.state.value))

            scenario!!.moveToState(Lifecycle.State.CREATED)
            waitForScreenFlag(false)
            assertTrue(fixture.observeScreen("paired_guidance_background").sawDisplayOff)
            fixture.wakeAllocatedScreen()
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            waitForScreenFlag(true)
            assertEquals(session, NavigationStopSnapshot.from(fixture.app.navigation.state.value))

            fixture.sleepAllocatedScreen()
            assertTrue("Explicit emulator sleep input must turn the actual display off",
                fixture.observeScreen("paired_shell_sleep", 3_000).sawDisplayOff)
            fixture.wakeAllocatedScreen()
            waitForScreenFlag(true)
            assertEquals(session, NavigationStopSnapshot.from(fixture.app.navigation.state.value))
            assertTrue(fixture.stopOwnedGuidance())
            waitForScreenFlag(false)
            assertTrue("After guidance, actual screen-off must return under identical settings",
                fixture.observeScreen("paired_no_guidance_after").sawDisplayOff)
            fixture.wakeAllocatedScreen()
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(fixture.app, MainActivity::class.java))
        scenario!!.onActivity { activity = it }
        waitForTag("navigation-home")
    }

    private fun searchAndSelectSecondBuilding() {
        waitForTag("osm-search-query")
        compose.onNodeWithTag("osm-search-query").performTextReplacement(fixture.query)
        closeSoftKeyboard()
        compose.onNodeWithTag("osm-search-submit").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(requireNotNull(fixture.selected.displayName)).fetchSemanticsNodes().isNotEmpty()
        }
        fixture.capture("search_results")
        // Final Phone contract selects same-name buildings by full display_name, not a guessed row index.
        compose.onNodeWithText(requireNotNull(fixture.selected.displayName)).performScrollTo().performClick()
        compose.onNodeWithTag("osm-search-query").assertDoesNotExist()
    }

    private fun navigationViewModel(): PhoneNavigationViewModel {
        lateinit var result: PhoneNavigationViewModel
        scenario!!.onActivity { result = ViewModelProvider(it)[PhoneNavigationViewModel::class.java] }
        return result
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun windowKeepsScreenOn(): Boolean {
        var flag = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            flag = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        return flag
    }

    private fun waitForScreenFlag(expected: Boolean) = compose.waitUntil(5_000) { windowKeepsScreenOn() == expected }

    private fun windowFacts(): JSONObject = JSONObject()
        .put("keepScreenOnFlag", windowKeepsScreenOn())
        .put("isInteractive", checkNotNull(fixture.app.getSystemService(PowerManager::class.java)).isInteractive)
        .put("guidance", fixture.app.navigation.state.value.guidance)
        .put("simulation", fixture.app.navigation.state.value.simulation)
        .put("sessionId", fixture.app.navigation.state.value.sessionId)

    private fun evidenceCase(label: String, action: () -> Unit) {
        try {
            action()
            fixture.capture(label + "_assertions_passed")
        } catch (failure: Throwable) {
            runCatching { fixture.capture(label + "_failure", JSONObject().put("exceptionType", failure.javaClass.name)) }
            throw failure
        }
    }
}
