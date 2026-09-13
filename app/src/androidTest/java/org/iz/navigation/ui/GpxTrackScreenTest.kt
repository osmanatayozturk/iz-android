package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.*
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpxTrackScreenTest {
    @get:Rule val compose = createComposeRule()
    private val track = ImportedTrack(id = "synthetic-gpx", name = "Örnek gezi", segments = listOf(
        TrackSegment("Birinci bölüm", listOf(WeatherCoordinate(41.0, 29.0), WeatherCoordinate(41.0, 29.02))),
        TrackSegment("İkinci bölüm", listOf(WeatherCoordinate(41.2, 29.2), WeatherCoordinate(41.2, 29.22)))))

    @Test fun emptyModeRequestsImportAndDismissesInterruptedNoticeOnlyOnTap() {
        var imports = 0; var dismissals = 0
        compose.setContent { TestScreen(null, interrupted = true, onImport = { imports++ }, onDismissInterrupted = { dismissals++ }) }
        compose.onNodeWithTag("gpx-import").performClick()
        compose.onNodeWithTag("gpx-interrupted").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, imports); assertEquals(0, dismissals) }
        compose.onNodeWithTag("gpx-dismiss-interrupted").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun previewDoesNotStartOrSaveAndExplicitStartPreservesRecordingTransport() {
        val starts = mutableListOf<Pair<Transport, Boolean>>()
        var saves = 0; var stops = 0; var dismissals = 0
        compose.setContent { TestScreen(track, currentTransport = Transport.MOTORCYCLE,
            onStart = { _, _, transport, replace -> starts += transport to replace },
            onSave = { saves++ }, onStop = { stops++ }, onDismiss = { dismissals++ }) }
        compose.onNodeWithTag("gpx-transport").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(starts.isEmpty()); assertEquals(0, saves); assertEquals(0, stops) }
        startPreview()
        compose.runOnIdle { assertEquals(listOf(Transport.MOTORCYCLE to false), starts); assertEquals(0, stops) }
        compose.onNodeWithTag("gpx-close").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismissals); assertEquals(0, stops) }
    }

    @Test fun replacementRequiresConcreteConfirmationAndCancelDoesNotStopAnything() {
        val starts = mutableListOf<Boolean>(); var stops = 0
        compose.setContent { TestScreen(track, existingNavigation = true,
            onStart = { _, _, _, replace -> starts += replace }, onStop = { stops++ }) }
        startPreview()
        compose.runOnIdle { assertTrue(starts.isEmpty()); assertEquals(0, stops) }
        compose.onNodeWithTag("gpx-cancel-replace").performClick()
        compose.runOnIdle { assertTrue(starts.isEmpty()); assertEquals(0, stops) }
        startPreview()
        compose.onNodeWithTag("gpx-confirm-replace").performClick()
        compose.runOnIdle { assertEquals(listOf(true), starts); assertEquals(0, stops) }
    }

    @Test fun activeModeShowsSegmentProgressWithoutEtaAndStopsOnlyThroughCallback() {
        var stops = 0; var starts = 0
        val state = TrackFollowState(track, TrackFollowSelection(segmentIndex = 1, reversed = true),
            TrackFollowProgress(100.0, 700.0, 12.0, TrackFollowStatus.TRACKING))
        compose.setContent { TestScreen(track, active = state, onStop = { stops++ }, onStart = { _, _, _, _ -> starts++ }) }
        compose.onNodeWithTag("gpx-progress").performScrollTo().assertTextContains("700 m", substring = true)
        compose.onNodeWithTag("gpx-segment").assertTextContains("İkinci bölüm", substring = true)
        compose.onNodeWithTag("gpx-stop").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(0, starts) }
    }

    @Test fun saveRequiresUserSuppliedNameAndDoesNotStartTrack() {
        val saved = mutableListOf<ImportedTrack>(); var starts = 0
        compose.setContent { TestScreen(track, onSave = { saved += it }, onStart = { _, _, _, _ -> starts++ }) }
        compose.onNodeWithTag("gpx-save").performScrollTo().performClick()
        compose.onNodeWithTag("gpx-save-name").performTextReplacement("Hafta sonu izi")
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithTag("gpx-confirm-save").performClick()
        compose.runOnIdle {
            assertEquals("Hafta sonu izi", saved.single().name)
            assertEquals(track.segments, saved.single().segments)
            assertEquals(0, starts)
        }
    }

    @Test fun completionNeverStartsNextSegmentAutomatically() {
        val selections = mutableListOf<TrackFollowSelection>()
        val state = TrackFollowState(track, TrackFollowSelection(), TrackFollowProgress(1500.0, 0.0, 0.0, TrackFollowStatus.SEGMENT_COMPLETE))
        compose.setContent { TestScreen(track, active = state, onStart = { _, selection, _, _ -> selections += selection }) }
        compose.onNodeWithTag("gpx-next-segment").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        startPreview()
        compose.onNodeWithTag("gpx-confirm-replace").performClick()
        compose.runOnIdle { assertEquals(1, selections.single().segmentIndex) }
    }

    @Test fun reversingActivePreviewResetsProjectedStartAndNeedsExplicitRestart() {
        val selections = mutableListOf<TrackFollowSelection>()
        val state = TrackFollowState(track, TrackFollowSelection(startFraction = .5),
            TrackFollowProgress(0.0, 800.0, null, TrackFollowStatus.WAITING_FOR_GPS))
        compose.setContent { TestScreen(track, active = state, onStart = { _, selection, _, _ -> selections += selection }) }
        compose.onNodeWithTag("gpx-reverse").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        startPreview()
        compose.onNodeWithTag("gpx-confirm-replace").performClick()
        compose.runOnIdle { assertEquals(TrackFollowSelection(reversed = true), selections.single()) }
    }

    @Test fun ambiguousProgressReselectActionDoesNotRestartOrStopSession() {
        var starts = 0; var stops = 0
        val state = TrackFollowState(track, TrackFollowSelection(),
            TrackFollowProgress(100.0, 700.0, 0.0, TrackFollowStatus.NEEDS_START_POINT))
        compose.setContent { TestScreen(track, active = state,
            onStart = { _, _, _, _ -> starts++ }, onStop = { stops++ }) }
        compose.onNodeWithTag("gpx-reselect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, stops) }
        compose.onNodeWithTag("gpx-map").assertIsDisplayed()
    }

    @Test fun completedSegmentRemainsCompleteWhenLastGpsBecomesStale() {
        val state = TrackFollowState(track, TrackFollowSelection(),
            TrackFollowProgress(1500.0, 0.0, 0.0, TrackFollowStatus.SEGMENT_COMPLETE))
        compose.setContent { TestScreen(track, active = state, gpsStale = true) }
        compose.onNodeWithTag("gpx-status").performScrollTo().assertTextEquals("Bölüm tamamlandı.")
    }

    @Test fun replacementIdentityChangeRequiresNewConfirmation() {
        val key = mutableStateOf("session-a")
        var starts = 0
        compose.setContent { TestScreen(track, existingNavigation = true, replacementKey = key.value,
            onStart = { _, _, _, _ -> starts++ }) }
        startPreview()
        compose.runOnIdle { key.value = "session-b" }
        compose.onNodeWithTag("gpx-confirm-replace").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }
        startPreview()
        compose.onNodeWithTag("gpx-confirm-replace").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun replacementSurvivesProgressButNotActiveSelectionChange() {
        val state = mutableStateOf(TrackFollowState(track, TrackFollowSelection(),
            TrackFollowProgress(100.0, 700.0, 0.0, TrackFollowStatus.TRACKING)))
        val starts = mutableListOf<TrackFollowSelection>()
        compose.setContent { TestScreen(track, active = state.value, replacementKey = "same-session",
            onStart = { _, selection, _, _ -> starts += selection }) }
        startPreview()
        compose.runOnIdle { state.value = state.value.copy(progress =
            TrackFollowProgress(110.0, 690.0, 2.0, TrackFollowStatus.TRACKING)) }
        compose.onNodeWithTag("gpx-confirm-replace").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selection = TrackFollowSelection(reversed = true)) }
        compose.onNodeWithTag("gpx-confirm-replace").assertDoesNotExist()
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
        startPreview()
        compose.onNodeWithTag("gpx-confirm-replace").performClick()
        compose.runOnIdle { assertEquals(listOf(TrackFollowSelection(reversed = true)), starts) }
    }

    @Test fun importingAnotherTrackDiscardsThePreviousNameDialog() {
        val displayed = mutableStateOf(track)
        val saved = mutableListOf<ImportedTrack>()
        compose.setContent { TestScreen(displayed.value, onSave = { saved += it }) }
        compose.onNodeWithTag("gpx-save").performScrollTo().performClick()
        compose.onNodeWithTag("gpx-save-name").performTextReplacement("Eski dosyanın adı")
        compose.runOnIdle { displayed.value = track.copy(id = "another-synthetic-gpx", name = "Yeni dosya") }
        compose.onNodeWithTag("gpx-confirm-save").assertDoesNotExist()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithTag("gpx-save").performScrollTo().performClick()
        compose.onNodeWithTag("gpx-save-name").assertTextContains("Yeni dosya")
    }

    private fun startPreview() {
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("gpx-start") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gpx-start").performScrollTo().performClick()
    }

    @Composable private fun TestScreen(track: ImportedTrack?, active: TrackFollowState? = null, gpsStale: Boolean = false,
        currentTransport: Transport? = null, existingNavigation: Boolean = false, interrupted: Boolean = false,
        replacementKey: String? = null,
        onImport: () -> Unit = {}, onSave: (ImportedTrack) -> Unit = {},
        onStart: (ImportedTrack, TrackFollowSelection, Transport, Boolean) -> Unit = { _, _, _, _ -> },
        onStop: () -> Unit = {}, onDismiss: () -> Unit = {}, onDismissInterrupted: () -> Unit = {}) {
        IzTheme { GpxTrackScreen(track, active,
            NavigationFix(WeatherCoordinate(41.0, 29.0), System.currentTimeMillis(), 5f), gpsStale,
            currentTransport, existingNavigation, interrupted, onImport = onImport, onSave = onSave,
            onStart = onStart, onStop = onStop, onDismiss = onDismiss, onDismissInterrupted = onDismissInterrupted,
            replacementKey = replacementKey) }
    }
}
