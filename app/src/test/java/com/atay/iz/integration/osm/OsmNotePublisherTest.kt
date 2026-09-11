package com.atay.iz.integration.osm

import com.atay.iz.data.ContributionDraft
import com.atay.iz.data.ContributionKind
import com.atay.iz.data.ContributionStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class OsmNotePublisherTest {
    private val time = 1_800_000_000_000L
    private val session = OsmCredentials("token", OsmUser(41, "Mapper"), null)
    private fun draft() = ContributionDraft(latitude = 41.01, longitude = 29.02,
        observedAt = time, kind = ContributionKind.MISSING_PLACE, text = "Fountain here")
    private val note = OsmRemoteNote(99, 41.01, 29.02, "open", time + 1000,
        listOf(OsmNoteComment(41, "opened", "Fountain here", time + 1000)))

    @Test fun lostPostResponseMakesDurableUnknownAndNeverResends() = runBlocking {
        val store = MemoryStore(draft())
        val api = Gateway().apply { failure = IOException("Lost reply") }
        val publisher = OsmNotePublisher(store, { session }, api, { time })
        publisher.send(store.value.id)
        assertEquals(ContributionStatus.UNKNOWN, store.value.status)
        assertEquals(time, store.value.submittedAt)
        publisher.send(store.value.id)
        assertEquals(1, api.posts)
    }

    @Test fun serverFailureIsUnknownButExplicitRejectionAllowsUserRequestedRetry() = runBlocking {
        for ((code, expected) in listOf(503 to ContributionStatus.UNKNOWN, 403 to ContributionStatus.FAILED)) {
            val store = MemoryStore(draft())
            val api = Gateway().apply { failure = OsmApiException(code) }
            val publisher = OsmNotePublisher(store, { session }, api, { time })
            publisher.send(store.value.id)
            assertEquals(expected, store.value.status)
            publisher.send(store.value.id)
            assertEquals(if (code == 403) 2 else 1, api.posts)
        }
    }

    @Test fun concurrentSendClaimsTheDraftOnce() = runBlocking {
        val store = MemoryStore(draft())
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val api = Gateway().apply { beforeSend = { entered.complete(Unit); gate.await() } }
        val publisher = OsmNotePublisher(store, { session }, api, { time })
        val first = async { publisher.send(store.value.id) }
        assertTrue("The first call must reach the single POST", withTimeoutOrNull(2000) { entered.await(); true } ?: false)
        assertEquals(ContributionStatus.SENDING, store.value.status)
        assertFalse(publisher.send(store.value.id).success)
        gate.complete(Unit)
        assertTrue(first.await().success)
        assertEquals(1, api.posts)
        assertEquals(99L, store.value.remoteNoteId)
    }

    @Test fun unauthenticatedSendLeavesPrivateDraftUntouched() = runBlocking {
        val store = MemoryStore(draft())
        val api = Gateway()
        assertFalse(OsmNotePublisher(store, { null }, api).send(store.value.id).success)
        assertEquals(ContributionStatus.DRAFT, store.value.status)
        assertEquals(0, api.posts)
    }

    @Test fun unauthorizedResultIdentifiesTheOriginalSessionAfterAccountChange() = runBlocking {
        val store = MemoryStore(draft())
        var current = session
        var rejected: OsmCredentials? = null
        val api = Gateway().apply {
            beforeSend = { current = OsmCredentials("new-token", OsmUser(42, "New account"), null) }
            failure = OsmApiException(401)
        }
        OsmNotePublisher(store, { current }, api, { time }, { rejected = it }).send(store.value.id)
        assertSame(session, rejected)
        assertNotSame(current, rejected)
    }

    @Test fun anonymousSuccessfulResponseCannotClaimAuthenticatedSuccess() = runBlocking {
        val store = MemoryStore(draft())
        val api = Gateway().apply { response = note.copy(comments = listOf(note.comments.first().copy(userId = null))) }
        assertFalse(OsmNotePublisher(store, { session }, api, { time }).send(store.value.id).success)
        assertEquals(ContributionStatus.UNKNOWN, store.value.status)
    }

    @Test fun directAuthenticatedReplyPreservesItsNoteIdDespiteIncorrectPhoneClock() = runBlocking {
        val store = MemoryStore(draft())
        val api = Gateway().apply { response = note.copy(createdAt = time - 600_000) }
        assertTrue(OsmNotePublisher(store, { session }, api, { time }).send(store.value.id).success)
        assertEquals(ContributionStatus.SENT, store.value.status)
        assertEquals(99L, store.value.remoteNoteId)
    }

    @Test fun reconciliationAndRemoteCloseAreReadOnly() = runBlocking {
        val store = MemoryStore(draft().copy(status = ContributionStatus.UNKNOWN, submittedAt = time, submittedBy = 41))
        val api = Gateway()
        val publisher = OsmNotePublisher(store, { session }, api)
        assertTrue(publisher.reconcile(store.value.id).success)
        assertEquals(ContributionStatus.SENT, store.value.status)
        api.response = note.copy(status = "closed")
        assertTrue(publisher.refreshRemoteStatus(store.value.id).success)
        assertEquals("closed", store.value.remoteStatus)
        assertEquals(0, api.posts)
    }

    private inner class Gateway : OsmNotesGateway {
        var posts = 0
        var failure: IOException? = null
        var response = note
        var beforeSend: suspend () -> Unit = {}
        override suspend fun createNote(token: String, latitude: Double, longitude: Double, text: String): OsmRemoteNote {
            posts++
            beforeSend()
            failure?.let { throw it }
            return response
        }
        override suspend fun readNote(id: Long) = response
        override suspend fun nearbyNotes(latitude: Double, longitude: Double) = listOf(response)
    }

    private class MemoryStore(var value: ContributionDraft) : OsmContributionStore {
        override suspend fun get(id: String) = value.takeIf { it.id == id }
        override suspend fun begin(id: String, userId: Long, time: Long): ContributionDraft? {
            if (value.id != id || value.status !in setOf(ContributionStatus.DRAFT, ContributionStatus.FAILED)) return null
            value = value.copy(status = ContributionStatus.SENDING, submittedBy = userId, submittedAt = maxOf(time, (value.submittedAt ?: 0) + 1))
            return value
        }
        override suspend fun finish(id: String, time: Long, status: ContributionStatus, noteId: Long?, remoteStatus: String?, error: String?): Boolean {
            if (value.id != id || value.status != ContributionStatus.SENDING || value.submittedAt != time) return false
            value = value.copy(status = status, remoteNoteId = noteId, remoteStatus = remoteStatus, error = error)
            return true
        }
        override suspend fun reconcile(id: String, time: Long, noteId: Long, remoteStatus: String): Boolean {
            if (value.status != ContributionStatus.UNKNOWN || value.submittedAt != time) return false
            value = value.copy(status = ContributionStatus.SENT, remoteNoteId = noteId, remoteStatus = remoteStatus)
            return true
        }
        override suspend fun refresh(id: String, noteId: Long, remoteStatus: String): Boolean {
            if (value.status != ContributionStatus.SENT || value.remoteNoteId != noteId) return false
            value = value.copy(remoteStatus = remoteStatus)
            return true
        }
    }
}
