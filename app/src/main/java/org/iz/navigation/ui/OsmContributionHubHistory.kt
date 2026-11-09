package org.iz.navigation.ui

import kotlinx.coroutines.CancellationException
import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionStatus
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxHistoryGateway
import org.iz.navigation.integration.osm.OsmGpxHistoryHttpException
import org.iz.navigation.osmcommunity.CommunitySession

internal sealed interface OsmContributionHubHistoryResult {
    data class Ready(val entries: List<OsmGpxHistoryEntry>) : OsmContributionHubHistoryResult
    data object Failed : OsmContributionHubHistoryResult
}

/** Reads independently of the GPS upload controller's verified history snapshot. */
internal class OsmContributionHubHistory(
    private val currentSession: () -> CommunitySession?,
    private val gateway: OsmGpxHistoryGateway,
    private val rejectSession: (CommunitySession) -> Unit,
) {
    suspend fun load(expected: CommunitySession): OsmContributionHubHistoryResult? {
        if (currentSession() !== expected) return null
        val result = try {
            OsmContributionHubHistoryResult.Ready(gateway.list(expected.token, expected.userId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentSession() === expected && error is OsmGpxHistoryHttpException && error.statusCode == 401) {
                rejectSession(expected)
            }
            OsmContributionHubHistoryResult.Failed
        }
        return result.takeIf { currentSession() === expected }
    }
}

internal sealed interface OsmContributionHubRow {
    val key: String
    val timestamp: Long

    data class Note(val draft: ContributionDraft) : OsmContributionHubRow {
        override val key = "note-${draft.id}"
        override val timestamp = draft.submittedAt ?: draft.observedAt
        val editable = draft.status == ContributionStatus.DRAFT || draft.status == ContributionStatus.FAILED
    }

    data class Place(val draft: MapEditDraft) : OsmContributionHubRow {
        override val key = "place-${draft.id}"
        override val timestamp = draft.submittedAt ?: draft.observedAt
        val editable = draft.status == MapEditStatus.DRAFT || draft.status == MapEditStatus.FAILED
    }

    data class Gps(val entry: OsmGpxHistoryEntry) : OsmContributionHubRow {
        override val key = "gps-${entry.id}"
        override val timestamp = entry.timestampMillis
    }
}

internal fun osmContributionHubRows(
    notes: List<ContributionDraft>,
    places: List<MapEditDraft>,
    accountId: Long?,
    gps: List<OsmGpxHistoryEntry>,
): List<OsmContributionHubRow> = buildList<OsmContributionHubRow> {
    notes.filter { it.status == ContributionStatus.DRAFT || accountId != null && it.submittedBy == accountId }
        .mapTo(this) { OsmContributionHubRow.Note(it) }
    places.filter { it.status == MapEditStatus.DRAFT || accountId != null && it.submittedBy == accountId }
        .mapTo(this) { OsmContributionHubRow.Place(it) }
    if (accountId != null) gps.mapTo(this) { OsmContributionHubRow.Gps(it) }
}.sortedWith(compareByDescending<OsmContributionHubRow> { it.timestamp }.thenBy { it.key })
