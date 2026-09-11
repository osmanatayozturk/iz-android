package com.atay.iz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.Transport
import com.atay.iz.navigation.NavigationState
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.RouteManeuver
import com.atay.iz.weather.RouteVertex
import com.atay.iz.weather.WeatherCoordinate
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
        compose.onNodeWithText("Yönlendirmeyi durdur").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(0, finished) }
        compose.onNodeWithTag("finish-navigation-journey").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, finished) }
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

