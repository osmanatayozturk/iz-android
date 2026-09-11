package com.atay.iz.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeatmapUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun passengerAndAllFiltersHaveSeparateEmptyStates() {
        compose.setContent { IzTheme { HeatmapScreen(DiaryState(), now = 100_000) } }
        compose.onNodeWithTag("heatmap_filter_PASSENGER").performClick()
        compose.onNodeWithText("Yolcu için henüz iz yok").assertIsDisplayed()
        compose.onNodeWithTag("heatmap_filter_PASSENGER").assertIsSelected()
        compose.onNodeWithTag("heatmap_filter_all").performClick()
        compose.onNodeWithText("İzlerin burada birikecek").assertIsDisplayed()
        compose.onNodeWithTag("heatmap_filter_all").assertIsSelected()
    }
}
