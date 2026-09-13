package org.iz.navigation.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val a = WeatherCoordinate(10.0, 20.0)
    private val b = WeatherCoordinate(10.001, 20.001)

    @Test fun plansAndGpxRemainDistinctAndOnlyOpenAfterExplicitAction() {
        val plan = SavedRoutePlan(id = "plan", name = "Plan A", transport = Transport.WALK, stops = listOf(RouteStop("A", a), RouteStop("B", b)))
        val track = ImportedTrack(id = "track", name = "Track A", segments = listOf(TrackSegment("", listOf(a, b))))
        var openedPlan: SavedRoutePlan? = null
        var openedTrack: ImportedTrack? = null
        var renamed: Pair<String, String>? = null
        compose.setContent { IzTheme {
            TravelLibraryContent(listOf(plan), listOf(track), { openedPlan = it }, { openedTrack = it },
                { value, name -> renamed = value.id to name }, {}, { _, _ -> }, {}, {})
        } }
        compose.onNodeWithText("Rota planı").assertIsDisplayed()
        compose.onNodeWithText("GPX izi").assertIsDisplayed()
        compose.waitForIdle(); LibraryUiEvidence.capture("routes-and-gpx")
        compose.runOnIdle { assertNull(openedPlan); assertNull(openedTrack) }
        compose.onNodeWithTag("open-plan-plan").performClick()
        compose.runOnIdle { assertEquals(plan, openedPlan); assertNull(openedTrack) }
        compose.onNodeWithTag("rename-plan-plan").performClick()
        compose.onNodeWithTag("library_name").performTextReplacement("Yeni ad")
        compose.onNodeWithText("Kaydet").performClick()
        compose.runOnIdle { assertEquals("plan" to "Yeni ad", renamed) }
        compose.onNodeWithTag("open-track-track").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(track, openedTrack) }
    }

    @Test fun collectionSelectionExcludesActiveTemporaryAndExpiringJourneysAndOrdersTheRest() {
        val first = Journey(id = "a", title = "İlk yolculuk", startedAt = 1000, endedAt = 5000)
        val second = first.copy(id = "b", title = "İkinci yolculuk")
        val collection = JourneyCollection(id = "c", name = "Gezi A")
        val snapshot = DiarySnapshot(journeys = listOf(first, second, first.copy(id = "active", title = "Aktif", endedAt = null),
            first.copy(id = "temp", title = "Geçici", status = JourneyStatus.TEMPORARY), first.copy(id = "expires", title = "Süreli", expiresAt = 9999)))
        var saved: List<String>? = null
        compose.setContent { IzTheme {
            JourneyCollectionsContent(listOf(collection), emptyList(), snapshot, {}, { _, _ -> }, {}, { _, ids -> saved = ids }, {}, {})
        } }
        compose.onNodeWithTag("open-collection-c").performClick()
        compose.onNodeWithText("Yolculuk seç").performClick()
        compose.onNodeWithText("Aktif").assertDoesNotExist()
        compose.onNodeWithText("Geçici").assertDoesNotExist()
        compose.onNodeWithText("Süreli").assertDoesNotExist()
        compose.onNodeWithTag("select-journey-a").performClick()
        compose.onNodeWithTag("select-journey-b").performScrollTo().performClick()
        compose.onNodeWithTag("move-up-b").performScrollTo().performClick()
        compose.waitForIdle(); LibraryUiEvidence.capture("collection-ordered-selection")
        compose.onNodeWithText("Seçimi kaydet").performClick()
        compose.runOnIdle { assertEquals(listOf("b", "a"), saved) }
    }

    @Test fun emptyCollectionsAreAllowedAndDeletionDoesNotInvokeJourneyActions() {
        val collection = JourneyCollection(id = "empty", name = "Boş gezi")
        var deleted: JourneyCollection? = null
        var opened = false
        compose.setContent { IzTheme {
            JourneyCollectionsContent(listOf(collection), emptyList(), DiarySnapshot(), {}, { _, _ -> }, { deleted = it }, { _, _ -> }, { opened = true }, {})
        } }
        compose.onNodeWithTag("open-collection-empty").performClick()
        compose.onNodeWithText("Henüz yolculuk eklenmedi.").assertIsDisplayed()
        compose.waitForIdle(); LibraryUiEvidence.capture("empty-collection")
        compose.onNodeWithText("Geziyi sil").performClick()
        compose.onNodeWithText("Yolculuklar ve fotoğraflar günlükte kalır.").assertIsDisplayed()
        compose.onNodeWithText("Sil").performClick()
        compose.runOnIdle { assertEquals(collection, deleted); assertFalse(opened) }
    }
}
