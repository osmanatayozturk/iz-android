package org.iz.navigation.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionKind
import org.iz.navigation.data.ContributionStatus
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStage
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.integration.osm.OSM_GPX_READ_SCOPE
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxHistoryGateway
import org.iz.navigation.osmcommunity.CommunitySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsmContributionHubTest {
    private val localNote = ContributionDraft(id = "local-note", latitude = 40.0, longitude = 30.0,
        observedAt = 1L, kind = ContributionKind.OTHER, text = "Yerel bildirim")
    private val localPlace = MapEditDraft(id = "local-place", latitude = 40.0, longitude = 30.0,
        observedAt = 4L, name = "Yerel mekân")
    private val publishedPlace = localPlace.copy(id = "published", status = MapEditStatus.SENT,
        stage = MapEditStage.CLOSE_CHANGESET, submittedBy = 7L, submittedAt = 2L,
        remoteNodeId = 42L, remoteNodeVersion = 1L, changesetId = 43L, changesetClosed = true)
    private val sentNote = localNote.copy(id = "sent", status = ContributionStatus.SENT, submittedBy = 7L,
        submittedAt = 3L, remoteNoteId = 51L, remoteStatus = "open")
    private val trace = OsmGpxHistoryEntry(80L, "iz.gpx", 5L, pending = false)

    @Test fun `common list orders drafts submissions and GPS by their contribution time`() {
        val rows = osmContributionHubRows(listOf(localNote, sentNote), listOf(localPlace, publishedPlace),
            accountId = 7L, gps = listOf(trace))

        assertEquals(listOf(OsmContributionHubRow.Gps(trace), OsmContributionHubRow.Place(localPlace),
            OsmContributionHubRow.Note(sentNote), OsmContributionHubRow.Place(publishedPlace),
            OsmContributionHubRow.Note(localNote)), rows)
    }

    @Test fun `only unsubmitted local drafts are shown without their submitting account`() {
        val notes = listOf(localNote, sentNote, sentNote.copy(id = "failed", status = ContributionStatus.FAILED,
            remoteNoteId = null, remoteStatus = null))
        val places = listOf(localPlace, publishedPlace)
        val expected = listOf(OsmContributionHubRow.Place(localPlace), OsmContributionHubRow.Note(localNote))

        assertEquals(expected, osmContributionHubRows(notes, places, accountId = 9L, gps = emptyList()))
        assertEquals(expected, osmContributionHubRows(notes, places, accountId = null, gps = emptyList()))
    }

    @Test fun `late history from the previous session is discarded during an account change`() = runTest {
        val old = session(7L, 1L)
        val current = session(9L, 2L)
        var session = old
        val response = CompletableDeferred<List<OsmGpxHistoryEntry>>()
        val gateway = OsmGpxHistoryGateway { _, accountId -> if (accountId == old.userId) response.await() else emptyList() }
        val history = OsmContributionHubHistory({ session }, gateway) { }
        val stale = async(start = CoroutineStart.UNDISPATCHED) { history.load(old) }

        session = current
        assertEquals(OsmContributionHubHistoryResult.Ready(emptyList()), history.load(current))
        response.complete(listOf(trace))

        assertNull(stale.await())
    }

    @Test fun `history from an earlier session of the same account is discarded`() = runTest {
        val old = session(7L, 1L)
        val current = session(7L, 2L)
        var session = old
        val response = CompletableDeferred<List<OsmGpxHistoryEntry>>()
        val history = OsmContributionHubHistory({ session }, OsmGpxHistoryGateway { _, _ -> response.await() }) { }
        val stale = async(start = CoroutineStart.UNDISPATCHED) { history.load(old) }

        session = current
        response.complete(listOf(trace))

        assertNull(stale.await())
    }

    @Test fun `history failure is reported separately from local data`() = runTest {
        val session = session(7L, 1L)
        val history = OsmContributionHubHistory({ session }, OsmGpxHistoryGateway { _, _ -> throw IOException() }) { }

        assertEquals(OsmContributionHubHistoryResult.Failed, history.load(session))
    }

    private fun session(id: Long, generation: Long) =
        CommunitySession("synthetic-token", id, "Hesap $id", setOf(OSM_GPX_READ_SCOPE), generation)
}
