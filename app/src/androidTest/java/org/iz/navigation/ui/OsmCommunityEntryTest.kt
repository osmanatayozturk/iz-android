package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmCommunityEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun menuOpensCommunityAndReturnsToMainMap() {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-community").performScrollTo().performClick()
        listOf("Mesajlar", "Kişiler", "Hesabım").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
    }
}

