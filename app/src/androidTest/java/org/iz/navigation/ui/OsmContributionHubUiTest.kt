package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionKind
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmContributionHubUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creationActionsAndUploadedTracesHaveSeparateDestinations() {
        val actions = mutableListOf<String>()
        showHub(onAction = { actions.add(it) })

        compose.onNodeWithText("Katkı Merkezi").assertIsDisplayed()
        listOf("new-note", "new-place", "existing-place", "gps", "uploads", "refresh").forEach { action ->
            scrollTo("osm-contribution-$action").performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("new-note", "new-place", "existing-place", "gps", "uploads", "refresh"), actions)
        }
    }

    @Test fun noteManagementEntryOpensItsExistingDestination() {
        var opened = 0
        showHub(onManageNotes = { opened++ })

        scrollTo("osm-contribution-manage-notes").performClick()

        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun localDraftsCanReopenWithTheirOriginalRecordsWhenHistoryFails() {
        val note = ContributionDraft(id = "note-1", latitude = 40.0, longitude = 30.0,
            observedAt = 1L, kind = ContributionKind.OTHER, text = "Eski bildirim")
        val place = MapEditDraft(id = "place-1", latitude = 41.0, longitude = 29.0,
            observedAt = 2L, name = "Eski mekân")
        val opened = mutableListOf<OsmContributionHubRow>()
        showHub(osmContributionHubRows(listOf(note), listOf(place), 7L, emptyList()),
            OsmContributionHubHistoryResult.Failed, onOpen = { opened.add(it) })

        scrollTo("osm-contribution-history-error").assertIsDisplayed()
        scrollTo("osm-contribution-place-${place.id}").performClick()
        scrollTo("osm-contribution-note-${note.id}").performClick()

        compose.runOnIdle {
            assertEquals(listOf(OsmContributionHubRow.Place(place), OsmContributionHubRow.Note(note)), opened)
        }
    }

    @Test fun uploadedGpsAndPublishedMapPlaceHaveDifferentStatusText() {
        val place = MapEditDraft(id = "published", latitude = 40.0, longitude = 30.0,
            observedAt = 1L, status = MapEditStatus.SENT, submittedAt = 2L, submittedBy = 7L)
        val gps = OsmGpxHistoryEntry(10L, "Sentetik iz", 3L, pending = false)
        showHub(osmContributionHubRows(emptyList(), listOf(place), 7L, listOf(gps)))

        scrollTo("osm-contribution-gps-10")
        compose.onNodeWithText("OSM'ye yüklendi", substring = true).assertIsDisplayed()
        scrollTo("osm-contribution-place-published")
        compose.onNodeWithText("OSM'de yayımlandı", substring = true).assertIsDisplayed()
    }

    @Test fun accountChangeReplacesRemoteRowsAndKeepsTheLocalDraft() {
        val account = mutableLongStateOf(7L)
        val local = MapEditDraft(id = "local", latitude = 40.0, longitude = 30.0, observedAt = 1L)
        val sent = local.copy(id = "sent", status = MapEditStatus.SENT, submittedAt = 2L, submittedBy = 7L)
        showHub(rowsForComposition = {
            val owner = account.longValue
            val gps = OsmGpxHistoryEntry(owner, "Sentetik iz", 3L, pending = false)
            osmContributionHubRows(emptyList(), listOf(local, sent), owner, listOf(gps))
        })

        scrollTo("osm-contribution-gps-7").assertIsDisplayed()
        scrollTo("osm-contribution-place-sent").assertIsDisplayed()
        compose.runOnIdle { account.longValue = 8L }
        scrollTo("osm-contribution-gps-8").assertIsDisplayed()
        scrollTo("osm-contribution-place-local").assertIsDisplayed()
        compose.onNodeWithTag("osm-contribution-gps-7").assertDoesNotExist()
        compose.onNodeWithTag("osm-contribution-place-sent").assertDoesNotExist()
    }

    private fun showHub(
        rows: List<OsmContributionHubRow> = emptyList(),
        history: OsmContributionHubHistoryResult = OsmContributionHubHistoryResult.Ready(emptyList()),
        onAction: (String) -> Unit = {},
        onOpen: (OsmContributionHubRow) -> Unit = {},
        onManageNotes: (() -> Unit)? = null,
        rowsForComposition: (() -> List<OsmContributionHubRow>)? = null,
    ) = compose.setContent {
        MaterialTheme {
            OsmContributionHubContent(rows = rowsForComposition?.invoke() ?: rows, history = history, canRefresh = true,
                onClose = {}, onNewNote = { onAction("new-note") }, onNewPlace = { onAction("new-place") },
                onPickExistingPlace = { onAction("existing-place") }, onGps = { onAction("gps") },
                onGpsHistory = { onAction("uploads") }, onRefresh = { onAction("refresh") }, onOpen = onOpen,
                onManageNotes = onManageNotes)
        }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("osm-contribution-list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }
}
