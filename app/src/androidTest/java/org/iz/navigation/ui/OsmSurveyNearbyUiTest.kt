package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SurveyVenue
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.SurveyObservation
import org.iz.navigation.data.SurveyFieldObservation
import org.iz.navigation.data.SurveyAction
import org.iz.navigation.data.encode
import org.iz.navigation.integration.OsmSurveySnapshot
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Map callbacks replace the native renderer. Shared snapshots and venue identities remain real. */
@RunWith(AndroidJUnit4::class)
class OsmSurveyNearbyUiTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 11, 11)
    private val venue = SurveyVenue(OsmRef(OsmType.NODE, 700), 12, "Sentetik kafe", 41.001, 29.001,
        mapOf("amenity" to "cafe", "opening_hours" to "Mo-Fr 09:00-18:00"))
    private val snapshot = OsmSurveySnapshot(WeatherCoordinate(41.0, 29.0), listOf(venue),
        refreshedAt = 1_794_312_000_000L, endpoint = "https://overpass.example.test/api/interpreter", capped = true)

    @Test fun cachedTasksStayHiddenUntilDelayedRoomReceiptsHaveBeenApplied() {
        val observation = SurveyObservation(12, today.toString(),
            hours = SurveyFieldObservation(SurveyAction.VERIFY, fullyObserved = true, before = venue.tags["opening_hours"]),
            wheelchair = SurveyFieldObservation(SurveyAction.EDIT, value = "yes", fullyObserved = true))
        val receipt = MapEditDraft(latitude = venue.latitude, longitude = venue.longitude,
            targetType = venue.ref.type, targetId = venue.ref.id, surveyPayload = observation.encode(),
            status = MapEditStatus.SENT, submittedBy = 41, localChangesetCreated = true,
            localAttemptId = "synthetic-attempt", reviewedVersion = 12, appliedVersion = 13)
        var receipts by mutableStateOf<List<MapEditDraft>?>(null)
        compose.setContent { MaterialTheme {
            OsmSurveyNearbyContent(surveyVisibleSnapshot(snapshot, receipts, 41, today), loading = receipts == null,
                error = null, today = today, onRefresh = {}, onOpen = {}, mapContent = { venues, _ ->
                    venues.forEach { Text("Harita işareti ${it.ref.id}") }
                })
        } }
        compose.onNodeWithText("Harita işareti 700").assertDoesNotExist()
        compose.onNodeWithTag("osm-survey-venue-NODE-700").assertDoesNotExist()
        compose.runOnIdle { receipts = listOf(receipt) }
        compose.onNodeWithText("Harita işareti 700").assertDoesNotExist()
        compose.onNodeWithTag("osm-survey-venue-NODE-700").assertDoesNotExist()
        compose.runOnIdle { assertTrue(requireNotNull(surveyVisibleSnapshot(snapshot, receipts, 41, today)).venues.isEmpty()) }
    }

    private fun show(error: String? = null, onRefresh: () -> Unit, onOpen: (SurveyVenue) -> Unit) {
        compose.setContent { MaterialTheme {
            OsmSurveyNearbyContent(snapshot = snapshot, loading = false, error = error, today = today,
                onRefresh = onRefresh, onOpen = onOpen, mapContent = { venues, open ->
                    Column {
                        venues.forEach { item -> Button(onClick = { open(item) }) { Text("Harita işareti ${item.ref.id}") } }
                    }
                })
        } }
    }

    @Test fun twoFieldTasksShareOneVenueCardAndOneMapDestination() {
        val opened = mutableListOf<SurveyVenue>()
        var refreshes = 0
        show(onRefresh = { refreshes++ }, onOpen = { opened += it })
        compose.onNodeWithText("Harita işareti 700").assertHasClickAction().performClick()
        val row = hasTestTag("osm-survey-venue-NODE-700")
        compose.onNodeWithTag("osm-survey-venues").performScrollToNode(row)
        compose.onAllNodes(row).assertCountEquals(1)
        compose.onNode(row).assertHeightIsAtLeast(48.dp).assertTextContains("Sentetik kafe", substring = true)
            .assertTextContains("Kontrol tarihi bilinmiyor", substring = true)
            .assertTextContains("Eksik", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(listOf(venue, venue), opened)
            assertEquals(0, refreshes)
        }
    }

    @Test fun failedRefreshKeepsCachedVenueAndOffersExplicitRetry() {
        var refreshes = 0
        show(error = "Bağlantı kurulamadı.", onRefresh = { refreshes++ }, onOpen = {})
        compose.onNodeWithTag("osm-survey-cached-at").assertIsDisplayed()
        compose.onNodeWithText("Bazı yakın yerler gösterilemiyor").assertIsDisplayed()
        compose.onNodeWithText("Bağlantı kurulamadı.").assertIsDisplayed()
        compose.onNodeWithTag("osm-survey-venues").performScrollToNode(hasTestTag("osm-survey-venue-NODE-700"))
        compose.onNodeWithTag("osm-survey-venue-NODE-700").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("osm-survey-retry").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertTrue(refreshes == 1) }
    }
}
