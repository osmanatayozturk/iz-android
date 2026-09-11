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
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("record-navigation-choice").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("close-directions-planner").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("record-navigation-choice").performScrollTo().assertIsOn()
    }
}

