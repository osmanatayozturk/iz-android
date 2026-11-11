package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationHomeShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun menuKeepsDiaryFeaturesReachableWithoutStartingTrip() {
        val navigation = (compose.activity.application as IzApplication).navigation
        val before = navigation.state.value
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-journeys").performClick()
        compose.onNodeWithText("Yolculukların").assertIsDisplayed()
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        assertEquals(before.journey?.id, navigation.state.value.journey?.id)
        assertEquals(before.guidance, navigation.state.value.guidance)
    }

    @Test fun plannerCanCloseWithoutDismissingOrScrollingMainMap() {
        openPlannerFromMenu()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("record-navigation-choice"))
        compose.onNodeWithTag("record-navigation-choice").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("close-directions-planner"))
        compose.onNodeWithTag("close-directions-planner").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        openPlannerFromMenu()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("record-navigation-choice"))
        compose.onNodeWithTag("record-navigation-choice").assertIsOn()
    }

    @Test fun menuKeepsDashboardAndLayersReachableWithoutStartingOrChangingSession() {
        val navigation = (compose.activity.application as IzApplication).navigation
        val before = navigation.state.value
        compose.onNodeWithTag("map-layers").assertDoesNotExist()
        compose.onNodeWithTag("open-navigation-dashboard").assertDoesNotExist()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-navigation-dashboard").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-dashboard").assertIsDisplayed()
        compose.onNodeWithTag("close-navigation-dashboard").performClick()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-map-layers").performScrollTo().performClick()
        compose.onNodeWithText("Planlanan rota", substring = true).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(before.guidance, navigation.state.value.guidance)
            assertEquals(before.recording, navigation.state.value.recording)
            assertEquals(before.sessionId, navigation.state.value.sessionId)
            assertEquals(before.journey?.id, navigation.state.value.journey?.id)
        }
    }

    private fun openPlannerFromMenu() {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-route-plan").performScrollTo().performClick()
    }
}

