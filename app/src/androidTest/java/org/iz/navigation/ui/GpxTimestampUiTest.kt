package org.iz.navigation.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpxTimestampUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachOpeningStartsWithoutDatesAndPreviewMatchesExport() {
        val journey = Journey(id = "synthetic", startedAt = 0, endedAt = 10000)
        val points = listOf(TrackPoint(journeyId = journey.id, latitude = 10.0, longitude = 20.0, recordedAt = 1000, accuracy = 5f),
            TrackPoint(journeyId = journey.id, latitude = 10.0001, longitude = 20.0001, recordedAt = 2000, accuracy = 5f))
        var visible by mutableStateOf(true)
        var exported: String? = null
        compose.setContent { IzTheme {
            if (visible) GpxExportSheet(journey, points, { visible = false }, { exported = it })
        } }
        compose.onNodeWithTag("gpx_include_timestamps").performScrollTo().assertIsOff()
        compose.onNodeWithText("tarih ve saatler eklenmeyecek", substring = true).assertIsDisplayed()
        compose.waitForIdle(); LibraryUiEvidence.capture("gpx-dates-off")
        compose.onNodeWithText("GPX dosyasını kaydet").performScrollTo().performClick()
        compose.runOnIdle { assertNotNull(exported); assertFalse(exported!!.contains("<time>")) }
        compose.onNodeWithTag("gpx_include_timestamps").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("kayıt tarih/saatleri bulunacak", substring = true).assertIsDisplayed()
        compose.waitForIdle(); LibraryUiEvidence.capture("gpx-dates-on")
        compose.onNodeWithText("GPX dosyasını kaydet").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(exported!!.contains("<time>1970-01-01T00:00:01Z</time>")) }
        compose.onNodeWithText("Kapat").performScrollTo().performClick()
        compose.onNodeWithTag("gpx_include_timestamps").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("gpx_include_timestamps").performScrollTo().assertIsOff()
    }
}
