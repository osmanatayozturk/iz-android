package org.iz.navigation.integration.osm

import org.iz.navigation.data.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OsmMapEditTest {
    private val time = 1_800_000_000_000L
    private fun draft() = MapEditDraft(latitude = 41.01, longitude = 29.02, preset = MapPlacePreset.DRINKING_WATER,
        observedAt = time, surveyConfirmed = true)
    private val session = OsmCredentials("token", OsmUser(41, "Mapper"), null, setOf("read_prefs", "write_notes", "write_api"))

    @Test fun unnamedWaterDoesNotSendCategoryAsName() {
        assertEquals(mapOf("amenity" to "drinking_water"), draft().publicTags())
        assertEquals(10, MapPlacePreset.entries.count { it.canCreate })
    }

    @Test fun unconfirmedSurveyCannotBePublished() {
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(draft().copy(surveyConfirmed = false)) }
    }

    @Test fun xmlEscapesPublicFieldsAndDoesNotIncludeLocalReferences() {
        val value = draft().copy(name = "A & B <su>", placeId = "private-place")
        val xml = OsmMapEditXml.nodeUpload(value, 7)
        assertTrue(xml.contains("A &amp; B &lt;su&gt;"))
        assertFalse(xml.contains("private-place"))
        assertFalse(xml.contains(time.toString()))
    }

    @Test fun notesOnlyAccountCannotCreateChangeset() = runBlocking {
        val store = Memory(draft()); val api = Gateway()
        val old = OsmCredentials("token", session.user, null)
        assertFalse(OsmMapEditPublisher(store, { old }, api, { time }).send(store.value.id).success)
        assertEquals(0, api.creates)
    }

    @Test fun newlyAppearedDuplicateRequiresAnotherPreviewBeforeAnyWrite() = runBlocking {
        val store = Memory(draft()); val api = Gateway().apply {
            duplicates = listOf(OsmDuplicateCandidate(OsmRef(OsmType.NODE, 12), "New fountain", 41.01, 29.02))
        }
        val publisher = OsmMapEditPublisher(store, { session }, api, { time })
        assertFalse(publisher.send(store.value.id).success)
        assertEquals(MapEditStatus.DRAFT, store.value.status)
        assertEquals(0, api.creates)
        assertTrue(publisher.send(store.value.id, setOf(OsmRef(OsmType.NODE, 12))).success)
    }

    @Test fun lostUploadResponseIsDurableUnknownAndCannotBeResent() = runBlocking {
        val store = Memory(draft()); val api = Gateway().apply { uploadFailure = IOException("lost") }
        val publisher = OsmMapEditPublisher(store, { session }, api, { time })
        publisher.send(store.value.id)
        assertEquals(MapEditStatus.UNKNOWN, store.value.status)
        assertEquals(7L, store.value.changesetId)
        publisher.send(store.value.id)
        assertEquals(1, api.uploads)
        api.nodes = listOf(OsmCreatedNode(9, 1, 7, 41, 41.01, 29.02, draft().publicTags()))
        assertTrue(publisher.reconcile(store.value.id).success)
        assertEquals(9L, store.value.remoteNodeId)
        assertEquals(1, api.uploads)
    }

    @Test fun closeFailureDoesNotTurnPublishedNodeIntoRetryableDraft() = runBlocking {
        val store = Memory(draft()); val api = Gateway().apply { closeFailure = IOException("lost") }
        val publisher = OsmMapEditPublisher(store, { session }, api, { time })
        assertTrue(publisher.send(store.value.id).success)
        assertEquals(MapEditStatus.SENT, store.value.status)
        assertFalse(store.value.changesetClosed)
        publisher.send(store.value.id)
        api.closeFailure = null
        api.open = true
        api.nodes = listOf(OsmCreatedNode(9, 1, 7, 41, 41.01, 29.02, draft().publicTags()))
        assertTrue(publisher.close(store.value.id).success)
        assertTrue(store.value.changesetClosed)
        assertEquals(1, api.uploads)
    }

    @Test fun retryGetsANewLocalAttemptAndAutomaticCloseRejectsARestoredReplacement() = runBlocking {
        val store = Memory(draft())
        val api = Gateway().apply { uploadFailure = OsmApiException(409) }
        val publisher = OsmMapEditPublisher(store, { session }, api, { time })
        assertFalse(publisher.send(store.value.id).success)
        val first = store.value
        assertEquals(MapEditStatus.FAILED, first.status)
        assertTrue(first.localChangesetCreated)
        assertFalse(first.editableCopy().localChangesetCreated)
        api.uploadFailure = null
        api.onClose = {
            assertTrue(store.value.localChangesetCreated)
            assertNotEquals(first.localAttemptId, store.value.localAttemptId)
            store.value = store.value.copy(localAttemptId = null, localChangesetCreated = false)
        }
        assertTrue(publisher.send(first.id).success)
        assertEquals(0, api.closes)
        assertFalse(store.value.changesetClosed)
        assertFalse(store.value.localChangesetCreated)
    }

    @Test fun reconciliationRejectsDifferentAccountAndAmbiguousNodes() = runBlocking {
        val store = Memory(draft().copy(status = MapEditStatus.UNKNOWN, stage = MapEditStage.UPLOAD_NODE,
            submittedAt = time, submittedBy = 41, changesetId = 7))
        val api = Gateway()
        val other = OsmCredentials("token", OsmUser(42, "Other"), null, session.scopes)
        assertFalse(OsmMapEditPublisher(store, { other }, api).reconcile(store.value.id).success)
        val node = OsmCreatedNode(9, 1, 7, 41, 41.01, 29.02, draft().publicTags())
        api.nodes = listOf(node, node.copy(id = 10))
        assertFalse(OsmMapEditPublisher(store, { session }, api).reconcile(store.value.id).success)
        assertEquals(MapEditStatus.UNKNOWN, store.value.status)
    }

    @Test fun missingChangesetIdCanBeRecheckedWithoutUploadingOrLeavingDraftStuck() = runBlocking {
        val store = Memory(draft().copy(status = MapEditStatus.UNKNOWN, stage = MapEditStage.CREATE_CHANGESET,
            submittedAt = time, submittedBy = 41))
        val api = Gateway()
        OsmMapEditPublisher(store, { session }, api).reconcile(store.value.id)
        assertEquals(MapEditStatus.FAILED, store.value.status)
        assertEquals(0, api.creates)
        assertEquals(0, api.uploads)
    }

    private class Memory(var value: MapEditDraft) : OsmMapEditStore {
        override fun isCurrentForDispatch(expected: MapEditDraft) = value == expected
        override suspend fun get(id: String) = value.takeIf { it.id == id }
        override suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft? {
            if (value.id != id || value.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) return null
            value = value.copy(status = MapEditStatus.SENDING, stage = MapEditStage.CREATE_CHANGESET, submittedBy = userId,
                submittedAt = maxOf(time, (value.submittedAt ?: 0) + 1), changesetId = null, remoteNodeId = null,
                remoteNodeVersion = null, changesetClosed = false, error = null,
                localAttemptId = UUID.randomUUID().toString(), localChangesetCreated = false)
            return value
        }
        override suspend fun recordLocalChangeset(expected: MapEditDraft, changeset: Long): MapEditDraft? {
            if (value != expected) return null
            return expected.withLocalChangeset(changeset).also { value = it }
        }
        override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft): Boolean {
            if (value != expected) return false
            value = updated; return true
        }
    }

    private class Gateway : OsmMapEditGateway {
        var creates = 0; var uploads = 0; var closes = 0
        var onClose: () -> Unit = {}
        var uploadFailure: Exception? = null; var closeFailure: Exception? = null
        var open = false
        var nodes = emptyList<OsmCreatedNode>()
        var duplicates = emptyList<OsmDuplicateCandidate>()
        override suspend fun nearby(draft: MapEditDraft) = duplicates
        override suspend fun createChangeset(token: String, draft: MapEditDraft): Long { creates++; return 7 }
        override suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode {
            uploads++; uploadFailure?.let { throw it }; return OsmUploadedNode(9, 1)
        }
        override suspend fun closeChangeset(token: String, id: Long, canDispatch: () -> Boolean) {
            onClose()
            if (!canDispatch()) throw OsmMapEditNotDispatched()
            closes++
            closeFailure?.let { throw it }
        }
        override suspend fun downloadChangeset(id: Long) = nodes
        override suspend fun downloadSingleCreatedNode(id: Long) = nodes.singleOrNull()
        override suspend fun readChangeset(id: Long) = OsmChangesetInfo(id, 41, open)
    }
}
