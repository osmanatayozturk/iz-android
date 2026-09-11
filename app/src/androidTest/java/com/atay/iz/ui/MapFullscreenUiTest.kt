package com.atay.iz.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.integration.DiaryMap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapFullscreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewOpensFullscreenWithoutChoosingPlaceAndDragKeepsItOpen() {
        var selections = 0
        compose.setContent {
            MaterialTheme {
                DiaryMap(emptyList(), emptyList(), Modifier.fillMaxWidth().height(240.dp),
                    onMapLongClick = { selections++ })
            }
        }
        compose.onNodeWithContentDescription("Haritayı tam ekran aç").performClick()
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, selections) }
        compose.onNodeWithTag("fullscreen_map").performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .75f), durationMillis = 500)
        }
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tam ekran haritayı kapat").performClick()
        compose.onNodeWithTag("fullscreen_map").assertDoesNotExist()
        compose.onNodeWithContentDescription("Haritayı tam ekran aç").assertIsDisplayed()
        compose.onNodeWithContentDescription("Haritayı büyüt").performClick()
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
    }
}
