package org.iz.navigation.integration.osm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.iz.navigation.integration.OsmDirectedPoint
import org.iz.navigation.osmcommunity.CommunitySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OsmGpxVisibilityRecoveryTest {
    @get:Rule val noBackup = TemporaryFolder()

    private val sessions = MutableStateFlow<CommunitySession?>(session(41))
    private val journal get() = OsmGpxFileReceipts(noBackup.root).visibilityJournal
    private val api = VisibilityHistory { owner, id -> assertTrue(journal.attempts(owner).containsKey(id)) }

    @Test fun unansweredPutsSurviveReopenAndOnlyFreshConfirmedRetryCanWriteAgain() = runBlocking {
        val applied = api.add(41L, 101L)
        val missing = api.add(41L, 102L)
        val independent = api.add(41L, 103L)
        val initial = reopenedController()
        api.replies[applied.id] = VisibilityHistory.Reply.APPLIED_WITHOUT_REPLY
        api.replies[missing.id] = VisibilityHistory.Reply.NOT_APPLIED_WITHOUT_REPLY
        initial.changeToTrackable(41L, listOf(applied.id, missing.id, independent.id))
        val firstPuts = listOf(applied.id, missing.id, independent.id).map { 41L to it }
        assertEquals(firstPuts, api.puts)
        assertEquals(setOf(applied.id, missing.id), journal.attempts(41L).keys)
        assertEquals("trackable", api.entry(41L, applied.id).visibility)
        assertEquals("identifiable", api.entry(41L, missing.id).visibility)
        assertEquals("trackable", api.entry(41L, independent.id).visibility)

        val unopened = reopenedController(refresh = false)
        unopened.changeToTrackable(41L, listOf(missing.id))
        assertEquals(firstPuts, api.puts)
        assertTrue(api.rechecks.isEmpty())

        val readCount = api.lists.size
        unopened.refreshHistory()
        assertEquals(listOf(41L), api.lists.drop(readCount))
        assertEquals(setOf(41L to applied.id, 41L to missing.id), api.rechecks.toSet())
        assertEquals(2, api.rechecks.size)
        assertEquals(firstPuts, api.puts)
        val shown = unopened.history.value as OsmGpxHistoryState.Ready
        assertEquals("trackable", shown.entries.single { it.id == applied.id }.visibility)
        assertTrue(shown.visibilityNotes.getValue(applied.id).contains("doğrulandı"))
        assertTrue(shown.visibilityNotes.getValue(missing.id).contains("yeniden seçip onayla"))
        assertFalse(missing.id in shown.visibilityBlocked)
        assertEquals(setOf(missing.id), journal.attempts(41L).keys)
        assertTrue(applied.samePreservedFields(api.entry(41L, applied.id)))

        api.replies.remove(missing.id)
        unopened.changeToTrackable(41L, listOf(missing.id), canContinue = { false })
        assertEquals(firstPuts, api.puts)
        unopened.changeToTrackable(41L, listOf(missing.id))
        assertEquals(firstPuts + (41L to missing.id), api.puts)
        assertEquals("trackable", api.entry(41L, missing.id).visibility)
        assertTrue(missing.samePreservedFields(api.entry(41L, missing.id)))
        assertTrue(independent.samePreservedFields(api.entry(41L, independent.id)))
        assertTrue(journal.attempts(41L).isEmpty())
    }

    @Test fun reconciliationOnlyReadsAnIndexedIdFromTheRightAccountAndDenialDoesNotReserve() = runBlocking {
        val pending = api.add(41L, 111L)
        val denied = api.add(41L, 112L)
        val other = api.add(42L, 121L)
        val startingOwner = reopenedController()
        api.replies[denied.id] = VisibilityHistory.Reply.DENIED_BEFORE_PUT
        api.replies[pending.id] = VisibilityHistory.Reply.NOT_APPLIED_WITHOUT_REPLY
        startingOwner.changeToTrackable(41L, listOf(denied.id, pending.id, other.id))
        val firstPut = listOf(41L to pending.id)
        assertEquals(firstPut, api.puts)
        assertEquals(setOf(pending.id), journal.attempts(41L).keys)
        assertTrue(journal.attempts(42L).isEmpty())
        assertEquals("identifiable", api.entry(41L, denied.id).visibility)

        sessions.value = session(42)
        val otherOwner = reopenedController()
        otherOwner.changeToTrackable(41L, listOf(pending.id))
        otherOwner.changeToTrackable(42L, listOf(pending.id))
        assertTrue(api.rechecks.isEmpty())
        assertEquals(firstPut, api.puts)
        assertEquals("identifiable", api.entry(42L, other.id).visibility)

        sessions.value = session(41, writable = false)
        api.unlisted += 41L to pending.id
        val incomplete = reopenedController()
        assertTrue(api.rechecks.isEmpty())
        assertTrue(pending.id in (incomplete.history.value as OsmGpxHistoryState.Ready).visibilityBlocked)
        assertEquals(setOf(pending.id), journal.attempts(41L).keys)
        api.unlisted.clear()
        incomplete.refreshHistory()
        incomplete.changeToTrackable(41L, listOf(pending.id))
        assertEquals(listOf(41L to pending.id), api.rechecks)
        assertEquals(firstPut, api.puts)
        assertEquals(setOf(pending.id), journal.attempts(41L).keys)
    }

    private suspend fun reopenedController(refresh: Boolean = true): OsmGpxController = OsmGpxController(
        currentSession = { sessions.value },
        gateway = OsmGpxGateway { _, _, _, _ -> error("Visibility must never POST a trace") },
        store = OsmGpxFileReceipts(noBackup.root),
        authState = MutableStateFlow(OsmAuthUiState()),
        sessions = sessions,
        permissionRequest = {},
        historyGateway = api,
    ).also { if (refresh) it.refreshHistory() }

    private fun session(account: Long, writable: Boolean = true): CommunitySession {
        val scopes = if (writable) setOf(OSM_GPX_READ_SCOPE, OSM_GPX_WRITE_SCOPE) else setOf(OSM_GPX_READ_SCOPE)
        return CommunitySession("qa-visibility-$account", account, "Account $account", scopes, 1)
    }

    private class VisibilityHistory(private val verifyReservation: (Long, Long) -> Unit) : OsmGpxHistoryGateway {
        enum class Reply { CONFIRMED, APPLIED_WITHOUT_REPLY, NOT_APPLIED_WITHOUT_REPLY, DENIED_BEFORE_PUT }

        private val rows = mutableMapOf<Pair<Long, Long>, OsmGpxRemoteMetadata>()
        val replies = mutableMapOf<Long, Reply>()
        val lists = mutableListOf<Long>()
        val rechecks = mutableListOf<Pair<Long, Long>>()
        val puts = mutableListOf<Pair<Long, Long>>()
        val unlisted = mutableSetOf<Pair<Long, Long>>()

        fun add(account: Long, id: Long): OsmGpxRemoteMetadata = OsmGpxRemoteMetadata(
            id = id, name = "qa-$id.gpx", timestampMillis = 1_740_000_000_000L + id,
            pending = false, visibility = "identifiable",
            description = "Synthetic description $id & extra", tags = listOf("QA", "label $id"),
        ).also { rows[account to id] = it }

        fun entry(account: Long, id: Long) = rows.getValue(account to id)

        override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
            verifyAccount(token, expectedUserId)
            lists += expectedUserId
            return rows.filterKeys { it.first == expectedUserId && it !in unlisted }.values.map { row ->
                OsmGpxHistoryEntry(row.id, row.name, row.timestampMillis, row.pending, row.visibility)
            }
        }

        override suspend fun data(token: String, id: Long): OsmGpxRemoteData {
            val account = token.substringAfterLast('-').toLong()
            check(entry(account, id).id == id)
            return OsmGpxRemoteData(
                listOf(listOf(OsmDirectedPoint(.04, -.29), OsmDirectedPoint(.04, -.2898))),
                id.toString(16).padStart(64, '0'),
            )
        }

        override suspend fun readMetadata(
            token: String, expectedUserId: Long, id: Long, canDispatch: () -> Boolean,
        ): OsmGpxRemoteMetadata {
            verifyAccount(token, expectedUserId)
            assertTrue(canDispatch())
            rechecks += expectedUserId to id
            return entry(expectedUserId, id)
        }

        override suspend fun changeToTrackable(
            token: String, expectedUserId: Long, id: Long, canDispatch: () -> Boolean,
            beforePut: (OsmGpxRemoteMetadata) -> Boolean,
        ): Boolean {
            verifyAccount(token, expectedUserId)
            if (!canDispatch()) return false
            val before = entry(expectedUserId, id)
            val reply = replies.getOrDefault(id, Reply.CONFIRMED)
            if (reply == Reply.DENIED_BEFORE_PUT) throw OsmGpxHistoryHttpException(403)
            if (!beforePut(before) || !canDispatch()) return false
            verifyReservation(expectedUserId, id)
            puts += expectedUserId to id
            if (reply != Reply.NOT_APPLIED_WITHOUT_REPLY) {
                rows[expectedUserId to id] = before.copy(visibility = "trackable")
            }
            return reply == Reply.CONFIRMED
        }

        private fun verifyAccount(token: String, account: Long) = assertEquals("qa-visibility-$account", token)
    }
}
