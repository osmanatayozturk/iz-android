package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmGpsBatchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startsUnselectedAndPreviewsOnlyAnExplicitlyOpenedRow() {
        val coast = OsmGpsCandidate(OsmGpxReceiptKey("coast", "a".repeat(64)), "Sahil", 1L, 2_000.0)
        val park = OsmGpsCandidate(OsmGpxReceiptKey("park", "b".repeat(64)), "Park", 2L, 3_000.0)
        val opened = mutableListOf<OsmGpxReceiptKey>()
        val selection = OsmGpsSelection().apply { bind(42L) }
        val all = setOf(coast.key, park.key)
        compose.setContent {
            IzTheme {
                Column(Modifier.fillMaxSize()) {
                    OsmGpsCandidates(listOf(coast, park), selection.keysFor(42L), enabled = true,
                        onToggleAll = { selection.toggleAll(all) },
                        onToggle = selection::toggle,
                        onPreview = { opened += it })
                }
            }
        }
        val list = compose.onNodeWithTag("osm-gps-list")
        val first = hasTestTag("osm-gps-journey-coast")
        val second = hasTestTag("osm-gps-journey-park")
        list.performScrollToNode(first)
        compose.onNode(first).assertIsOff()
        list.performScrollToNode(second)
        compose.onNode(second).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(emptyList<OsmGpxReceiptKey>(), opened) }
        list.performScrollToNode(hasTestTag("osm-gps-preview-open-park"))
        compose.onNodeWithTag("osm-gps-preview-open-park").performClick()
        compose.runOnIdle { assertEquals(listOf(park.key), opened) }
        compose.onNodeWithTag("osm-gps-select-all").performClick()
        list.performScrollToNode(first)
        compose.onNode(first).assertIsOn()
        list.performScrollToNode(second)
        compose.onNode(second).assertIsOn()
        compose.runOnIdle { selection.bind(43L) }
        list.performScrollToNode(second)
        compose.onNode(second).assertIsOff()
    }

    @Test fun consentShowsAccountCountAndVisibilityBeforeExplicitConfirmation() {
        var confirmations = 0
        compose.setContent {
            IzTheme { OsmGpsConsentDialog("Fixture user", 2, onConfirm = { confirmations++ }, onDismiss = {}) }
        }
        compose.onNodeWithText("2 GPS izini OSM'ye gönder?").assertIsDisplayed()
        compose.onNodeWithText("Fixture user", substring = true).assertExists()
        compose.onNodeWithText("Görünürlük: Tanımlanabilir (Identifiable)").assertExists()
        compose.runOnIdle { assertEquals(0, confirmations) }
        compose.onNodeWithTag("osm-gps-consent-confirm").performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun historyOpensNewestMetadataEvenWithoutALocalJourney() {
        val old = OsmGpxHistoryEntry(10L, "older.gpx", 1L, false)
        val recent = OsmGpxHistoryEntry(20L, "newer.gpx", 2L, true)
        var opened: Long? = null
        compose.setContent {
            IzTheme {
                Column(Modifier.fillMaxSize()) {
                    OsmGpsHistoryList(listOf(old, recent), onOpen = { opened = it.id })
                }
            }
        }
        val newer = compose.onNodeWithTag("osm-gps-history-20")
        val older = compose.onNodeWithTag("osm-gps-history-10")
        newer.assertIsDisplayed()
        older.assertIsDisplayed()
        val recentTop = newer.fetchSemanticsNode().boundsInRoot.top
        val oldTop = older.fetchSemanticsNode().boundsInRoot.top
        check(recentTop < oldTop)
        compose.onNodeWithText("İşleniyor").assertExists()
        newer.performClick()
        compose.runOnIdle { assertEquals(20L, opened) }
    }

    @Test fun selectionSurvivesSameAccountAndClearsOnAccountChange() {
        val coast = OsmGpsCandidate(OsmGpxReceiptKey("coast", "a".repeat(64)), "Sahil", 1L, 2_000.0)
        val selection = OsmGpsSelection().also { it.bind(1L) }
        compose.setContent {
            IzTheme {
                Column(Modifier.fillMaxSize()) {
                    OsmGpsCandidates(listOf(coast), selection.keysFor(1L), enabled = true,
                        onToggleAll = { selection.toggleAll(setOf(coast.key)) },
                        onToggle = selection::toggle, onPreview = {})
                }
            }
        }
        val row = hasTestTag("osm-gps-journey-coast")
        compose.onNodeWithTag("osm-gps-list").performScrollToNode(row)
        compose.onNode(row).performClick().assertIsOn()
        compose.runOnIdle { selection.bind(1L) }
        compose.onNode(row).assertIsOn()
        compose.runOnIdle { selection.bind(2L) }
        compose.onNode(row).assertIsOff()
    }
}
