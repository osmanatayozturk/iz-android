package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.osm.OsmGpxVisibility
import org.iz.navigation.integration.prepareOsmGpxTrace
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

    @Test fun consentShowsEachPreparedTraceAndUsesTheVisibleBatchVisibility() {
        val traces = listOf("coast", "park").mapIndexed { index, id ->
            val start = 1_000_000L + index * 1_000_000L
            val journey = Journey(id = id, startedAt = start, endedAt = start + 300_000)
            val points = (0..10).map {
                TrackPoint(journeyId = id, latitude = 41.0, longitude = 29.0 + it * 0.005,
                    recordedAt = start + it * 30_000L, accuracy = 5f)
            }
            (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
        }
        val confirmations = mutableListOf<OsmGpxVisibility>()
        compose.setContent {
            var position by remember { mutableIntStateOf(0) }
            var visibility by remember { mutableStateOf(OsmGpxVisibility.IDENTIFIABLE) }
            IzTheme {
                OsmGpsConsentDialog(
                    username = "Fixture user",
                    visibility = visibility,
                    preview = OsmGpsConsentPreview(traces[position], position, traces.size),
                    enabled = true,
                    onVisibility = { visibility = it },
                    onPreview = { position = it },
                    onConfirm = { confirmations += visibility },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("2 GPS izini OSM'ye gönder?").assertIsDisplayed()
        compose.onNodeWithText("Fixture user", substring = true).assertExists()
        compose.onNodeWithText("noktaları ve kayıt zamanlarını", substring = true).assertExists()
        compose.onNodeWithText("herkese açık iz listesinde", substring = true).assertExists()
        compose.onNodeWithTag("osm-gps-visibility-identifiable").assertIsSelected()
        compose.onNodeWithTag("osm-gps-consent-confirm").assertIsEnabled()
        compose.onNodeWithTag("osm-gps-preview-coast")
            .assert(SemanticsMatcher.expectValue(OsmGpsPreparedSha256, traces[0].sha256))
            .assert(SemanticsMatcher.expectValue(OsmGpsPreparedPointCount, 5))
        compose.onNodeWithText("1970-01-01T00:18:10Z", substring = true).assertExists()
        compose.onNodeWithTag("osm-gps-points-open").performScrollTo().performClick()
        compose.onNodeWithTag("osm-gps-points-list").performScrollToNode(hasTestTag("osm-gps-point-0-2"))
        compose.onNodeWithTag("osm-gps-point-0-2").assertTextContains("41.0, 29.025", substring = true)
            .assertTextContains("1970-01-01T00:19:10Z", substring = true)
        compose.onNodeWithTag("osm-gps-points-close").performClick()
        compose.onNodeWithTag("osm-gps-consent-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(OsmGpxVisibility.IDENTIFIABLE), confirmations) }
        compose.onNodeWithTag("osm-gps-preview-next").performScrollTo().performClick()
        compose.onNodeWithTag("osm-gps-preview-park")
            .assert(SemanticsMatcher.expectValue(OsmGpsPreparedSha256, traces[1].sha256))
        compose.onNodeWithTag("osm-gps-visibility-trackable").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("osm-gps-visibility-identifiable").assertIsNotSelected()
        compose.onNodeWithText("Görünürlük Takip Edilebilir").assertExists()
        compose.runOnIdle { assertEquals(listOf(OsmGpxVisibility.IDENTIFIABLE), confirmations) }
        compose.onNodeWithTag("osm-gps-consent-confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf(OsmGpxVisibility.IDENTIFIABLE, OsmGpxVisibility.TRACKABLE), confirmations)
        }
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
