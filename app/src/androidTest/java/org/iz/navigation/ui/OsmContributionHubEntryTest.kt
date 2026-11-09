package org.iz.navigation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmContributionHubEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeMenuOpensContributionHubAndCloseReturnsToMap() {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-osm").performScrollTo().performClick()

        compose.onNodeWithTag("osm-contribution-hub").assertIsDisplayed()
        compose.onNodeWithText("Katkı Merkezi").assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()

        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
    }
}
