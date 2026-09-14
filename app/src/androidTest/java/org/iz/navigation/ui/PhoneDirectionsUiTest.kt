package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneDirectionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allModesAndBothEndpointsRemainEditableWhileOpeningGpsWaits() {
        val state = mutableStateOf(PhoneDirectionsState(locating = true))
        var originSelected = 0
        var destinationSelected = 0
        compose.setContent { IzTheme {
            Content(state.value,
                transport = { state.value = state.value.copy(transport = it) },
                origin = { originSelected++ }, destination = { destinationSelected++ })
        } }
        for (mode in Transport.entries.filter { it != Transport.UNKNOWN }) {
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-mode-${mode.name}"))
            compose.onNodeWithTag("directions-mode-${mode.name}").assertIsEnabled().performClick().assertIsSelected()
        }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-origin"))
        compose.onNodeWithTag("directions-origin").assertIsEnabled().performClick()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-destination"))
        compose.onNodeWithTag("directions-destination").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, originSelected); assertEquals(1, destinationSelected) }
    }

    @Test fun previewIsSeparateFromStartAndStartingLocksRepeatedCommands() {
        val a = RouteStop("A", WeatherCoordinate(41.0, 29.0))
        val b = RouteStop("B", WeatherCoordinate(41.01, 29.01))
        val route = PlannedRoute("preview", listOf(a, b),
            listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 600.0)),
            1_500.0, 600.0, 100L, transport = Transport.CAR)
        val state = mutableStateOf(PhoneDirectionsState(origin = a, originCurrent = false, destination = b))
        var started = 0
        compose.setContent { IzTheme {
            Content(state.value, preview = { state.value = state.value.copy(preview = route) },
                start = { started++; state.value = state.value.copy(starting = true) })
        } }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("start-guidance"))
        compose.onNodeWithTag("start-guidance").assertIsNotEnabled()
        compose.onNodeWithTag("preview-directions").performClick()
        compose.runOnIdle { assertEquals(0, started) }
        compose.onNodeWithTag("start-guidance").assertIsEnabled().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("preview-directions").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, started) }
    }

    @Test fun fullscreenPreviewKeepsTurnInstructionsAvailable() {
        val a = RouteStop("A", WeatherCoordinate(41.0, 29.0))
        val b = RouteStop("B", WeatherCoordinate(41.01, 29.01))
        val route = PlannedRoute("turns", listOf(a, b),
            listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 600.0)),
            1_500.0, 600.0, 100L, transport = Transport.CAR,
            maneuvers = listOf(RouteManeuver(1, "Test sokağına sağa dön", "Sağa dön", emptyList(), 0, 1, 0.0, 600.0)))
        compose.setContent { IzTheme { Content(PhoneDirectionsState(origin = a, destination = b, preview = route)) } }
        compose.onNodeWithContentDescription("Haritayı tam ekran aç").performClick()
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
        compose.onNode(hasTestTag("directions-map-actions") and hasAnyAncestor(hasTestTag("fullscreen_map"))).performClick()
        compose.onNodeWithTag("directions-menu-maneuvers").performClick()
        compose.onNodeWithTag("directions-maneuvers").assertIsDisplayed()
        compose.onNodeWithText("Test sokağına sağa dön").assertIsDisplayed()
    }

    @Test fun guidanceWithoutDiaryStillOffersIndependentSessionControls() {
        val a = RouteStop("A", WeatherCoordinate(41.0, 29.0))
        val b = RouteStop("B", WeatherCoordinate(41.01, 29.01))
        val route = PlannedRoute("live", listOf(a, b),
            listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 600.0)),
            1500.0, 600.0, 100L, transport = Transport.CAR)
        var stopped = 0
        var finished = 0
        compose.setContent { IzTheme {
            PhoneDirectionsContent(ui = PhoneDirectionsState(),
                nav = NavigationState(route = route, guidance = true, recording = false),
                points = emptyList(), commandBusy = false, onClose = {}, chooseOrigin = {},
                chooseDestination = {}, currentOrigin = {}, transport = {}, preview = {}, start = {},
                freeDrive = {}, finish = { finished++ }, mute = {}, stopGuidance = { stopped++ },
                showLive = {}, trafficSettings = {}, lastDestination = null, permissions = {})
        } }
        compose.onNodeWithText("Yol tarifini bitir").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(0, finished) }
        compose.onNodeWithTag("finish-navigation-journey").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, finished) }
    }
    @Test fun onlyActiveEndActionsAreOfferedAndStartingOrBusyLocksEveryEnd() {
        val nav = mutableStateOf(NavigationState(sessionId = "session-a", guidance = true))
        val ui = mutableStateOf(PhoneDirectionsState())
        val busy = mutableStateOf(false)
        compose.setContent { IzTheme { SessionContent(ui.value, nav.value, busy.value) } }
        compose.onNodeWithTag("stop-navigation-guidance").assertIsEnabled()
        compose.onNodeWithTag("stop-navigation-recording").assertDoesNotExist()
        compose.runOnIdle { nav.value = nav.value.copy(recording = true) }
        compose.onNodeWithTag("stop-navigation-recording").assertIsEnabled()
        for (starting in listOf(false, true)) {
            compose.runOnIdle { busy.value = !starting; ui.value = ui.value.copy(starting = starting) }
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("stop-navigation-guidance"))
            compose.onNodeWithTag("stop-navigation-guidance").assertIsNotEnabled()
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("stop-navigation-recording"))
            compose.onNodeWithTag("stop-navigation-recording").assertIsNotEnabled()
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("finish-navigation-journey"))
            compose.onNodeWithTag("finish-navigation-journey").assertIsNotEnabled()
        }
        compose.runOnIdle { busy.value = false; ui.value = PhoneDirectionsState(); nav.value = NavigationState() }
        compose.onNodeWithTag("stop-navigation-guidance").assertDoesNotExist()
        compose.onNodeWithTag("stop-navigation-recording").assertDoesNotExist()
        compose.onNodeWithTag("finish-navigation-journey").assertDoesNotExist()
    }

    @Test fun repeatedIdenticalFailureBecomesVisibleAfterScrollingBackToBottom() {
        val state = mutableStateOf(PhoneDirectionsState())
        val errors = mutableStateOf(0L)
        compose.setContent { IzTheme { SessionContent(state.value, NavigationState(sessionId = "session-a"), false, errors.value) } }
        repeat(2) {
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("finish-navigation-journey"))
            compose.runOnIdle { state.value = state.value.copy(message = "Sentetik işlem hatası"); errors.value++ }
            compose.onNodeWithTag("directions-command-error").assertIsDisplayed().assertTextEquals("Sentetik işlem hatası")
        }
    }

    @Composable
    private fun SessionContent(ui: PhoneDirectionsState, nav: NavigationState, busy: Boolean, errors: Long = 0) {
        PhoneDirectionsContent(ui = ui, nav = nav, points = emptyList(), commandBusy = busy,
            onClose = {}, chooseOrigin = {}, chooseDestination = {}, currentOrigin = {}, transport = {},
            preview = {}, start = {}, freeDrive = {}, finish = {}, mute = {}, stopGuidance = {},
            showLive = {}, trafficSettings = {}, lastDestination = null, permissions = {}, showMap = false, commandErrorVersion = errors)
    }

    @Composable
    private fun Content(state: PhoneDirectionsState, transport: (Transport) -> Unit = {},
        origin: () -> Unit = {}, destination: () -> Unit = {}, preview: () -> Unit = {}, start: () -> Unit = {}) {
        PhoneDirectionsContent(ui = state, nav = NavigationState(), points = emptyList(), commandBusy = false,
            onClose = {}, chooseOrigin = origin, chooseDestination = destination, currentOrigin = {},
            transport = transport, preview = preview, start = start, freeDrive = {}, finish = {}, mute = {},
            stopGuidance = {}, showLive = {}, trafficSettings = {}, lastDestination = null, permissions = {})
    }
}

