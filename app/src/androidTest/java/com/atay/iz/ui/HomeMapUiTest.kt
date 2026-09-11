package com.atay.iz.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeMapUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun placeEditorOpensAboveMainMapAndCancelReturnsToMap() {
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNode(hasText("Bir yer kaydet") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Bu yere verdiğin ad").assertIsDisplayed()
        compose.onNodeWithTag("navigation-home-map").assertExists()
        compose.onNodeWithText("Vazgeç").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()


    }
}

