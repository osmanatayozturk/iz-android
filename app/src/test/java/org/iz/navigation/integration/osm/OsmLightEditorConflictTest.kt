package org.iz.navigation.integration.osm

import java.io.File
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.*
import org.iz.navigation.osmcatalog.*
import org.junit.Assert.*
import org.junit.Test

class OsmLightEditorConflictTest {
    private val catalog by lazy { OsmPresetCatalog.load(File(System.getProperty("iz.catalogue") ?: "src/main/assets/osm-light-catalogue-v1.json").inputStream()) }
    private val account = OsmCredentials("fictional-token", OsmUser(41, "Mapper"), null, setOf("write_api"))
    private fun element(kind: String, version: Long = 3) = OsmCorrectionElement(OsmRef(OsmType.NODE, 77), version, 5, 41,
        "41", "29", emptyList(), mapOf("amenity" to kind, "name" to "Remote name", "phone" to "111", "contact:phone" to "222"))
    private fun edited() = element("cafe").editorDraft(catalog, "10000000-0000-4000-8000-000000000001", 41.0, 29.0)
        .selectPreset(catalog.snapshot("amenity/pharmacy", OsmPresetGeometry.POINT))
        .copy(name = "My edited name", nameAction = MapEditFieldAction.SET, phone = "333", phoneAction = MapEditFieldAction.SET, surveyConfirmed = true)

    @Test fun explicitFreshReviewRebasesTypeOriginButPreservesEditedNamePhoneAndSelectedType() {
        val original = edited()
        val fresh = element("restaurant", 4)
        assertThrows(IllegalArgumentException::class.java) { fresh.review(original, account) }
        val rebased = fresh.rebaseEditorDraft(original, catalog)
        assertEquals("My edited name", rebased.name)
        assertEquals("333", rebased.phone)
        assertEquals("amenity/pharmacy", rebased.presetId)
        assertEquals(original.presetSnapshot, rebased.presetSnapshot)
        assertNotEquals(original.originalPresetSnapshot, rebased.originalPresetSnapshot)
        assertFalse(rebased.surveyConfirmed)
        val review = fresh.review(rebased.copy(surveyConfirmed = true), account)
        assertEquals("restaurant", review.fields.single { it.key == "amenity" }.before)
        assertEquals("pharmacy", review.fields.single { it.key == "amenity" }.after)
        assertEquals("My edited name", review.corrected.tags["name"])
        assertEquals("333", review.corrected.tags["phone"])
        assertFalse(review.corrected.tags.containsKey("contact:phone"))
    }

    @Test fun untouchedTypeAdoptsFreshDisplayWithoutRetaggingTheRemoteObject() {
        val original = element("cafe").editorDraft(catalog, "10000000-0000-4000-8000-000000000001", 41.0, 29.0)
            .copy(phone = "333", phoneAction = MapEditFieldAction.SET, surveyConfirmed = true)
        val fresh = element("restaurant", 4)
        val rebased = fresh.rebaseEditorDraft(original, catalog)
        assertEquals("amenity/restaurant", rebased.presetId)
        assertFalse(rebased.typeChanged)
        val review = fresh.review(rebased.copy(surveyConfirmed = true), account)
        assertEquals("restaurant", review.corrected.tags["amenity"])
        assertEquals(setOf("phone", "contact:phone"), review.fields.map { it.key }.toSet())
    }

    @Test fun versionConflictRequiresExplicitRebaseAndNewConsentWithoutAutomaticSecondWrite() = runBlocking {
        val store = Store(edited())
        val api = Gateway(element("cafe"))
        val publisher = OsmMapCorrectionPublisher(store, { account }, api)
        val first = publisher.preview(store.value)
        assertFalse(publisher.send(first).success)
        assertEquals(MapEditStatus.FAILED, store.value.status)
        assertEquals(1, api.updates)
        val fresh = api.current
        val rebased = fresh.rebaseEditorDraft(store.value, catalog)
        assertEquals(MapEditStatus.DRAFT, rebased.status)
        assertNull(rebased.localAttemptId)
        assertFalse(rebased.localChangesetCreated)
        assertFalse(rebased.surveyConfirmed)
        store.value = rebased.copy(surveyConfirmed = true)
        val second = publisher.preview(store.value)
        assertEquals(4L, second.original.version)
        assertEquals("restaurant", second.fields.single { it.key == "amenity" }.before)
        assertEquals("My edited name", second.corrected.tags["name"])
        assertEquals("333", second.corrected.tags["phone"])
        assertEquals(1, api.updates)
    }

    private class Store(var value: MapEditDraft) : OsmMapEditStore {
        override suspend fun get(id: String) = value.takeIf { it.id == id }
        override fun isCurrentForDispatch(expected: MapEditDraft) = value == expected
        override suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft? = error("Correction must claim through CAS")
        override suspend fun recordLocalChangeset(expected: MapEditDraft, changeset: Long): MapEditDraft? =
            if (value != expected) null else expected.withLocalChangeset(changeset).also { value = it }
        override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft): Boolean {
            if (value != expected) return false
            value = updated; return true
        }
    }
    private inner class Gateway(var current: OsmCorrectionElement) : OsmMapCorrectionGateway {
        var updates = 0
        override suspend fun readElement(ref: OsmRef) = current
        override suspend fun createChangeset(token: String, draft: MapEditDraft) = 7L
        override suspend fun updateElement(token: String, element: OsmCorrectionElement, changeset: Long, canDispatch: () -> Boolean): Long {
            updates++; current = element("restaurant", 4); throw OsmApiException(409)
        }
        override suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode = error("Correction may not create")
        override suspend fun closeChangeset(token: String, id: Long, canDispatch: () -> Boolean) = Unit
        override suspend fun downloadChangeset(id: Long) = emptyList<OsmCreatedNode>()
        override suspend fun downloadSingleCreatedNode(id: Long): OsmCreatedNode? = null
        override suspend fun readChangeset(id: Long) = OsmChangesetInfo(id, 41, true)
        override suspend fun downloadCorrection(changeset: Long): OsmCorrectionElement? = null
    }
}
