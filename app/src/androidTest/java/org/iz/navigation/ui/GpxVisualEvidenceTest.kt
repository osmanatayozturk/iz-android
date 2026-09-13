package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.*
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The same synthetic screen runs under externally selected screen/font sizes. */
@RunWith(AndroidJUnit4::class)
class GpxVisualEvidenceTest {
    @get:Rule val compose = createComposeRule()
    private val track = ImportedTrack(id = "visual-example", name = "Örnek kıyı yürüyüşü", segments = listOf(
        TrackSegment("Sahil bölümü", listOf(WeatherCoordinate(40.985, 29.025),
            WeatherCoordinate(40.983, 29.029), WeatherCoordinate(40.980, 29.034)))))

    @Test fun previewKeepsImportSaveAndStartAccessibleWithoutImplicitActions() {
        var starts = 0
        compose.setContent { IzTheme {
            GpxTrackScreen(track, null, null, true, Transport.WALK, false, false,
                onImport = {}, onSave = {}, onStart = { _, _, _, _, _ -> starts++ },
                onStop = {}, onDismiss = {}, onDismissInterrupted = {})
        } }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("gpx-start") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gpx-close").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { LibraryUiEvidence.capture("gpx-preview-top") }
        compose.onNodeWithTag("gpx-import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gpx-save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gpx-start").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts); LibraryUiEvidence.capture("gpx-preview-actions") }
    }

    @Test fun activeProgressAndStopStayAccessibleWithLongTurkishTrackName() {
        val active = TrackFollowState(track.copy(name = "Kıyı boyunca uzun bir örnek yürüyüş ve dönüş bölümü"),
            TrackFollowSelection(), TrackFollowProgress(150.0, 850.0, 3.0, TrackFollowStatus.TRACKING))
        var stops = 0
        compose.setContent { IzTheme {
            GpxTrackScreen(active.track, active, NavigationFix(track.segments.first().points.first(), 1000, 5f),
                false, Transport.WALK, false, false, onImport = {}, onSave = {},
                onStart = { _, _, _, _, _ -> }, onStop = { stops++ }, onDismiss = {}, onDismissInterrupted = {})
        } }
        compose.onNodeWithTag("gpx-stop").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, stops); LibraryUiEvidence.capture("gpx-active-actions") }
        compose.onNodeWithTag("gpx-stop").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
