package org.iz.navigation.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.placeinfo.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PlaceInformationCardUiTest {
    @get:Rule val compose = createComposeRule()
    private val key = PlaceInfoKey("https://example.invalid/api/0.6", OsmRef(OsmType.NODE, 123))
    private val snapshot = PublicPlaceSnapshot(key, 2, mapOf("name" to "Public cafe", "phone" to "+90 312 555 12 34",
        "website" to "https://example.org", "opening_hours" to "24/7", "wheelchair" to "limited", "payment:cash" to "only"), 1000)
    private var directions = 0
    private var edits = 0
    private val external = mutableListOf<Pair<PlaceInfoAction, Boolean>>()
    private var refreshes = 0
    private val override = mutableStateOf<String?>(null)
    private fun show(state: PlaceInfoState = PlaceInfoState(key = key, snapshot = snapshot), stateInput: State<PlaceInfoState>? = null) {
        compose.setContent { IzTheme {
            PlaceInformationContent(stateInput?.value ?: state, key, "Selection fallback", Instant.parse("2026-11-12T10:00:00Z"),
                ZoneId.of("UTC"), override.value, { override.value = it }, { refreshes++ },
                onDirections = { directions++ }, onSurvey = { edits++ }, onCorrect = { edits++ }, onNote = {},
                onExternal = { action, phone -> external += action to phone },
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        } }
    }
    @Test fun publicSectionsAndExplicitContactsNeverStartNavigationOnOpen() {
        show()
        listOf("place-info-contact", "place-info-hours", "place-info-wheelchair", "place-info-payments", "place-info-contribute")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        compose.runOnIdle { assertEquals(0, directions); assertEquals(0, edits); assertTrue(external.isEmpty()) }
        compose.onNodeWithTag("place-info-phone-0").performScrollTo().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        compose.onNodeWithTag("place-info-web-0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true, false), external.map { it.second }); assertEquals(0, directions); assertEquals(0, edits) }
    }
    @Test fun loadingFailureAndHistoricalDataStayDistinctFromMissingTags() {
        val input = mutableStateOf(PlaceInfoState(key = key, loading = true))
        show(stateInput = input)
        compose.onNodeWithTag("place-info-status").assertTextContains("yükleniyor", substring = true)
        compose.onAllNodesWithText("OSM'de bilgi yok").assertCountEquals(0)
        compose.runOnIdle { input.value = PlaceInfoState(key = key, failed = true) }
        compose.onNodeWithTag("place-info-status").assertTextContains("alınamadı", substring = true)
        compose.onAllNodesWithText("OSM'de bilgi yok").assertCountEquals(0)
        compose.runOnIdle { input.value = PlaceInfoState(key = key, snapshot = snapshot, failed = true, stale = true) }
        compose.onNodeWithTag("place-info-status").assertTextContains("Son alındı", substring = true)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("Kaydedilmiş OSM saatlerine göre", substring = true)
        compose.runOnIdle { assertEquals(0, directions); assertEquals(0, edits); assertTrue(external.isEmpty()) }
    }
    @Test fun unavailableHistoricalSnapshotCannotClaimCurrentHoursOrEdit() {
        show(PlaceInfoState(key = key, snapshot = snapshot, stale = true, unavailableStatus = 410))
        compose.onNodeWithTag("place-info-status").assertTextContains("artık kullanılamıyor", substring = true)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("Güncel saat durumu kullanılamıyor", substring = true)
        compose.onNodeWithTag("place-info-survey").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("place-info-correct").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, edits); assertEquals(0, directions) }
    }
    @Test fun hoursDetailAndZoneChangeStayLocalAndReturnToSameCard() {
        show()
        compose.onNodeWithTag("place-info-hours").performScrollTo().performClick()
        compose.onNodeWithTag("place-info-hours-detail").assertExists()
        compose.onNodeWithTag("place-info-hours-close").performClick()
        compose.onNodeWithTag("place-info-zone-change").performScrollTo().performClick()
        compose.onNodeWithTag("place-info-zone-input").performTextReplacement("Istanbul")
        compose.onNodeWithTag("place-info-zone-options").performScrollTo().performScrollToNode(hasTestTag("place-info-zone-option-Europe/Istanbul"))
        compose.onNodeWithTag("place-info-zone-option-Europe/Istanbul").assertTextContains("UTC+03:00", substring = true).performClick().assertIsSelected()
        compose.onNodeWithTag("place-info-zone-save").performClick()
        compose.onNodeWithTag("place-info-zone").assertTextContains("Europe/Istanbul", substring = true)
        compose.runOnIdle { assertEquals("Europe/Istanbul", override.value); assertEquals(0, refreshes); assertEquals(0, directions); assertEquals(0, edits) }
    }
    @Test fun knownPaymentsUseHumanLabelsWhileUnknownMethodsAndValuesKeepRawDetail() {
        val tagged = snapshot.copy(tags = snapshot.tags + mapOf("payment:novel_method" to "yes", "payment:visa" to "maybe"))
        show(PlaceInfoState(key = key, snapshot = tagged))
        compose.onNodeWithText("Nakit · Yalnızca bu yöntem").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("payment:cash=only", substring = true).assertDoesNotExist()
        compose.onNodeWithText("payment:novel_method=yes", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("payment:visa=maybe", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bu bilgiler OpenStreetMap'ten alınır; değişmiş olabilir.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, edits); assertEquals(0, directions); assertTrue(external.isEmpty()) }
    }
}
