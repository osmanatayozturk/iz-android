package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise actual planner controls with an injected map-free rendering boundary. */
@RunWith(AndroidJUnit4::class)
class SharedPlannerLimitUiTest {
    @get:Rule val compose = createComposeRule()
    private fun state(count: Int): PhoneDirectionsState {
        val stops = List(count) { RouteStop("Sentetik durak $it", WeatherCoordinate(41.0 + it * .001, 29.0)) }
        return PhoneDirectionsState(origin = stops.first(), originCurrent = false,
            via = stops.subList(1, stops.lastIndex), destination = stops.last())
    }
    @Composable private fun content(ui: PhoneDirectionsState) {
        PhoneDirectionsContent(ui = ui, nav = NavigationState(), points = emptyList(), commandBusy = false,
            onClose = {}, chooseOrigin = {}, chooseDestination = {}, currentOrigin = {}, transport = {},
            preview = {}, start = {}, freeDrive = {}, finish = {}, mute = {}, stopGuidance = {}, showLive = {},
            trafficSettings = {}, lastDestination = null, permissions = {}, showMap = false,
            addVia = {}, saveRoute = {})
    }

    @Test fun nineteenPlannedStopsStillAllowTheEighteenthVia() {
        compose.setContent { IzTheme { content(state(19)) } }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-add-via"))
        compose.onNodeWithTag("directions-add-via").assertIsEnabled()
    }

    @Test fun twentyPlannedStopsCanBeSavedAndCannotAddAnotherVia() {
        compose.setContent { IzTheme { content(state(20)) } }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-add-via"))
        compose.onNodeWithTag("directions-add-via").assertIsNotEnabled()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-save-route"))
        compose.onNodeWithTag("directions-save-route").assertIsEnabled()
    }
}
