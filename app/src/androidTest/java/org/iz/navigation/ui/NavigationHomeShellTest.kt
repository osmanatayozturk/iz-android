package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.lean.LeanMount
import org.iz.navigation.lean.LeanSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.onNodeWithTag("home-menu").assertContentDescriptionEquals("Harita menüsü").performClick()
        compose.onNodeWithTag("menu-journeys").performClick()
        compose.onNode(hasText("Yolculuklar") and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        assertEquals(before.journey?.id, navigation.state.value.journey?.id)
        assertEquals(before.guidance, navigation.state.value.guidance)
    }

    @Test fun liveLeanSettingsOnBothMountsLeaveReportRecordingOff() {
        val app = compose.activity.application
        val report = LeanSettingsStore.get(app)
        val display = LiveLeanDisplaySettings.get(app)
        val original = report.state.value
        val originalDisplay = display.enabled.value
        try {
            report.enabled(false)
            report.mount(LeanMount.UNKNOWN)
            display.setEnabled(false)
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-calibrate"))
            compose.onNodeWithTag("settings-open-calibrate").performClick()
            val option = compose.onNodeWithTag("live-lean-setting")
            option.performScrollTo().assertTextContains("Canlı yatış tahmini").assertIsNotEnabled()
            for ((label, mount) in listOf("Gidon" to LeanMount.HANDLEBAR, "Gövde" to LeanMount.BODY)) {
                compose.onNodeWithText(label).performScrollTo().performClick()
                option.performScrollTo().assertIsEnabled().assertIsOff().performClick().assertIsOn()
                compose.runOnIdle {
                    assertEquals(mount, report.state.value.mount)
                    assertTrue(display.enabled.value)
                    assertEquals(false, report.state.value.enabled)
                }
                option.performClick().assertIsOff()
            }
        } finally {
            report.mount(original.mount)
            original.calibration?.let(report::reference) ?: report.clearCalibration()
            report.enabled(original.enabled)
            display.setEnabled(originalDisplay)
        }
    }

    @Test fun idleBarOpensHistoryDestinationsAndLeavesMapControlsVisible() {
        val navigation = (compose.activity.application as IzApplication).navigation
        val before = navigation.state.value
        val bar = compose.onNodeWithTag("home-primary-navigation").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithTag("home-menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("open-navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(search.center.y, menu.center.y, 1f)
        listOf("directions-recenter", "home-record", "home-menu").forEach { tag ->
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            val control = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag overlaps the navigation bar", !control.overlaps(bar))
        }
        val attribution = compose.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(!attribution.overlaps(bar))
        compose.onNodeWithTag("home-nav-map").assertTextContains("Harita").assertIsSelected().assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.onNodeWithTag("home-nav-journeys").assertTextContains("Yolculuklar").assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp).performClick()
        compose.onNode(hasText("Yolculuklar") and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithTag("home-nav-osm").assertTextContains("OSM katkısı").assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("osm-contribution-hub").assertIsDisplayed()
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithTag("home-primary-navigation").assertIsDisplayed()
        compose.runOnIdle {
            val after = navigation.state.value
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(before.journey?.id, after.journey?.id)
            assertEquals(before.recording, after.recording)
            assertEquals(before.guidance, after.guidance)
        }
    }

    @Test fun plannerCanCloseWithoutDismissingOrScrollingMainMap() {
        openPlannerFromMenu()
        compose.onNodeWithTag("home-primary-navigation").assertDoesNotExist()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("record-navigation-choice"))
        compose.onNodeWithTag("record-navigation-choice").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("close-directions-planner"))
        compose.onNodeWithTag("close-directions-planner").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.onNodeWithTag("home-primary-navigation").assertIsDisplayed()
        openPlannerFromMenu()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("record-navigation-choice"))
        compose.onNodeWithTag("record-navigation-choice").assertIsOn()
    }

    @Test fun menuKeepsDashboardAndLayersReachableWithoutStartingOrChangingSession() {
        val navigation = (compose.activity.application as IzApplication).navigation
        val before = navigation.state.value
        compose.onNodeWithTag("map-layers").assertDoesNotExist()
        compose.onNodeWithTag("open-navigation-dashboard").assertDoesNotExist()
        compose.onNodeWithTag("home-menu").assertContentDescriptionEquals("Harita menüsü").performClick()
        compose.onNodeWithTag("menu-navigation-dashboard").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-dashboard").assertIsDisplayed()
        compose.onNodeWithTag("home-primary-navigation").assertDoesNotExist()
        compose.onNodeWithTag("close-navigation-dashboard").performClick()
        compose.onNodeWithTag("home-menu").assertContentDescriptionEquals("Harita menüsü").performClick()
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
        compose.onNodeWithTag("home-menu").assertContentDescriptionEquals("Harita menüsü").performClick()
        compose.onNodeWithTag("menu-route-plan").performScrollTo().performClick()
    }
}

