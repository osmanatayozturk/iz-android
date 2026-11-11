package org.iz.navigation.integration.osm

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.*
import org.junit.Assert.*
import org.junit.Test

class OsmSurveyCorrectionTest {
    private val account = OsmCredentials("fixture-token", OsmUser(41, "Fixture"), null, setOf("write_api"))
    private val originalHours = "Mo-Fr 09:00-18:00"
    private val checkedOn = "2025-04-03"

    @Test fun unchangedVerifiedHoursChangeOnlyTheirOwnCheckDate() {
        val original = element()
        val observation = observation(hours = SurveyFieldObservation(
            SurveyAction.VERIFY, fullyObserved = true, before = originalHours, beforeDate = "2024-04-03",
        ))
        val review = original.review(draft(observation), account)
        assertEquals(original.tags + ("check_date:opening_hours" to checkedOn), review.corrected.tags)
        assertEquals(original.latitude, review.corrected.latitude)
        assertEquals(original.longitude, review.corrected.longitude)
        assertEquals(original.version, review.corrected.version)
        assertEquals(setOf("check_date:opening_hours"), review.fields.filter { it.before != it.after }.map { it.key }.toSet())
    }

    @Test fun editedWheelchairOnClosedWayPreservesNodeOrderAndEveryUneditedTag() {
        val original = element(OsmType.WAY)
        val observation = observation(wheelchair = SurveyFieldObservation(
            SurveyAction.EDIT, "limited", fullyObserved = true, before = "yes", beforeDate = "2024-03-01",
        ))
        val review = original.review(draft(observation, OsmType.WAY), account)
        assertEquals(listOf(14L, 3L, 2L, 14L), review.corrected.nodeRefs)
        assertEquals(original.tags + mapOf("wheelchair" to "limited", "check_date:wheelchair" to checkedOn),
            review.corrected.tags)
        val xml = review.corrected.uploadXml(22)
        val modify = OsmMapEditXml.children(OsmMapEditXml.parse(xml), "modify").single()
        val way = OsmMapEditXml.children(modify, "way").single()
        assertEquals(listOf("14", "3", "2", "14"), OsmMapEditXml.children(way, "nd").map { it.getAttribute("ref") })
        assertEquals("8", way.getAttribute("version"))
        assertEquals("22", way.getAttribute("changeset"))
    }

    @Test fun unknownFieldKeepsExistingComplexValueAndCheckDateExactly() {
        val original = element().copy(tags = element().tags +
            ("opening_hours" to "Mo-Fr 09:00-18:00; PH off"))
        val observation = observation(
            hours = SurveyFieldObservation(SurveyAction.UNKNOWN),
            wheelchair = SurveyFieldObservation(SurveyAction.EDIT, "no", true, "yes", "2024-03-01"),
        )
        val review = original.review(draft(observation), account)
        assertEquals("Mo-Fr 09:00-18:00; PH off", review.corrected.tags["opening_hours"])
        assertEquals("2024-04-03", review.corrected.tags["check_date:opening_hours"])
        assertEquals(original.tags + mapOf("wheelchair" to "no", "check_date:wheelchair" to checkedOn),
            review.corrected.tags)
    }

    @Test fun partialHoursMayChangeValueWithoutPretendingWholeFieldWasChecked() {
        val original = element()
        val partial = "Mo-Fr 09:00-18:00; Sa-Su unknown"
        val review = original.review(draft(observation(hours = SurveyFieldObservation(
            SurveyAction.EDIT, partial, false, originalHours, "2024-04-03",
        ))), account)
        assertEquals(original.tags + ("opening_hours" to partial), review.corrected.tags)
        assertEquals("2024-04-03", review.corrected.tags["check_date:opening_hours"])
    }

    @Test fun fullLengthAdvancedInputSurvivesReviewWithoutTruncation() {
        val prefix = "24/7 \""
        val value = prefix + "a".repeat(255 - prefix.length - 1) + "\""
        val review = element().review(draft(observation(hours = SurveyFieldObservation(
            SurveyAction.EDIT, value, true, originalHours, "2024-04-03",
        ))), account)
        assertEquals(255, value.codePointCount(0, value.length))
        assertEquals(value, review.corrected.tags["opening_hours"])
    }

    @Test fun staleVersionValueOrCheckDateCannotBeReviewed() {
        val original = element()
        val valid = observation(hours = SurveyFieldObservation(
            SurveyAction.VERIFY, fullyObserved = true, before = originalHours, beforeDate = "2024-04-03",
        ))
        val stale = listOf(valid.copy(baseVersion = 7), valid.copy(hours = valid.hours.copy(before = "24/7")),
            valid.copy(hours = valid.hours.copy(beforeDate = null)))
        stale.forEach { observation ->
            assertThrows(IllegalArgumentException::class.java) { original.review(draft(observation), account) }
        }
    }

    @Test fun malformedEditedHoursAndNoObservedChangesCannotBePublished() {
        val invalid = observation(hours = SurveyFieldObservation(
            SurveyAction.EDIT, "not opening hours", true, originalHours, "2024-04-03",
        ))
        assertThrows(IllegalArgumentException::class.java) { element().review(draft(invalid), account) }
        assertThrows(IllegalArgumentException::class.java) {
            element().review(draft(observation(hours = SurveyFieldObservation(SurveyAction.UNKNOWN))), account)
        }
    }

    @Test fun newVenuePresetsCanBeCorrectedButOpenOrSelfTouchingWaysCannot() {
        for (preset in listOf(MapPlacePreset.HOSPITAL, MapPlacePreset.UNIVERSITY, MapPlacePreset.PUBLIC_BATH)) {
            val original = element().copy(tags = element().tags + (preset.key to preset.value))
            val observation = observation(wheelchair = SurveyFieldObservation(
                SurveyAction.EDIT, "limited", true, "yes", "2024-03-01",
            ))
            assertEquals(preset, original.preset())
            assertEquals("limited", original.review(draft(observation).copy(preset = preset), account).corrected.tags["wheelchair"])
        }
        val observation = observation(wheelchair = SurveyFieldObservation(
            SurveyAction.EDIT, "limited", true, "yes", "2024-03-01",
        ))
        for (refs in listOf(listOf(14L, 3L, 2L), listOf(14L, 3L, 14L, 2L, 14L))) {
            assertThrows(OsmUnsupportedCorrection::class.java) {
                element(OsmType.WAY).copy(nodeRefs = refs).review(draft(observation, OsmType.WAY), account)
            }
        }
    }

    @Test fun sameAccountFreshVersionAndLocalCloseProvenanceRemainRequired() = runBlocking {
        val original = element()
        val pending = draft(observation(hours = SurveyFieldObservation(
            SurveyAction.VERIFY, fullyObserved = true, before = originalHours, beforeDate = "2024-04-03",
        )))
        val store = Memory(pending)
        val gateway = Gateway(original)
        var currentAccount = account
        val publisher = OsmMapCorrectionPublisher(store, { currentAccount }, gateway, { 100L })
        val review = publisher.preview(pending)
        currentAccount = OsmCredentials("other-token", OsmUser(42, "Other"), null, setOf("write_api"))
        assertFalse(publisher.send(review).success)
        assertEquals(0, gateway.creates)
        currentAccount = account
        gateway.original = original.copy(version = 9)
        assertFalse(publisher.send(review).success)
        assertEquals(0, gateway.creates)
        gateway.original = original
        assertTrue(publisher.send(publisher.preview(pending)).success)
        assertEquals(1, gateway.updates)
        assertEquals(9L, store.value.appliedVersion)
        assertTrue(store.value.localChangesetCreated)
        assertNotNull(store.value.localAttemptId)
        assertEquals(1, gateway.closes)
        val restored = store.value.copy(changesetClosed = false, localAttemptId = null, localChangesetCreated = false)
        store.value = restored
        assertFalse(publisher.close(restored).success)
        assertEquals(1, gateway.closes)
    }

    @Test fun conflictAndLostResponseKeepExistingRetryAndReconciliationRules() = runBlocking {
        for (failure in listOf(OsmApiException(409), IOException("lost reply"))) {
            val original = element()
            val pending = draft(observation(hours = SurveyFieldObservation(
                SurveyAction.VERIFY, fullyObserved = true, before = originalHours, beforeDate = "2024-04-03",
            )))
            val store = Memory(pending)
            val gateway = Gateway(original).apply { updateFailure = failure }
            val publisher = OsmMapCorrectionPublisher(store, { account }, gateway, { 100L })
            val review = publisher.preview(pending)
            assertFalse(publisher.send(review).success)
            assertEquals(1, gateway.updates)
            assertEquals(if (failure is OsmApiException) MapEditStatus.FAILED else MapEditStatus.UNKNOWN, store.value.status)
            if (failure !is OsmApiException) {
                assertFalse(publisher.send(review).success)
                assertEquals(1, gateway.updates)
                assertTrue(publisher.reconcile(store.value.id).success)
                assertEquals(MapEditStatus.SENT, store.value.status)
                assertEquals(9L, store.value.appliedVersion)
                assertEquals(1, gateway.updates)
            }
        }
    }

    private fun observation(
        hours: SurveyFieldObservation = SurveyFieldObservation(),
        wheelchair: SurveyFieldObservation = SurveyFieldObservation(),
    ) = SurveyObservation(8, checkedOn, hours, wheelchair)

    private fun draft(observation: SurveyObservation, type: OsmType = OsmType.NODE) =
        MapEditDraft(latitude = 41.1234567, longitude = 29.2345678, surveyConfirmed = true,
            targetType = type, targetId = 7, surveyPayload = observation.encode())

    private fun element(type: OsmType = OsmType.NODE) = OsmCorrectionElement(
        OsmRef(type, 7), 8, 11, 41,
        if (type == OsmType.NODE) "41.1234567" else null,
        if (type == OsmType.NODE) "29.2345678" else null,
        if (type == OsmType.WAY) listOf(14, 3, 2, 14) else emptyList(),
        linkedMapOf("amenity" to "cafe", "name" to "Existing", "addr:street" to "Map Street",
            "operator" to "Existing\nOperator", "opening_hours" to originalHours, "check_date:opening_hours" to "2024-04-03",
            "wheelchair" to "yes", "check_date:wheelchair" to "2024-03-01"),
    )

    private class Memory(var value: MapEditDraft) : OsmMapEditStore {
        override suspend fun get(id: String) = value.takeIf { it.id == id }
        override fun isCurrentForDispatch(expected: MapEditDraft) = value == expected
        override suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft? = error("Correction uses compareAndSet")
        override suspend fun recordLocalChangeset(expected: MapEditDraft, changeset: Long): MapEditDraft? {
            if (value != expected) return null
            return expected.withLocalChangeset(changeset).also { value = it }
        }
        override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft): Boolean {
            if (value != expected) return false
            value = updated
            return true
        }
    }

    private class Gateway(var original: OsmCorrectionElement) : OsmMapCorrectionGateway {
        var creates = 0
        var updates = 0
        var closes = 0
        var updateFailure: Exception? = null
        var applied: OsmCorrectionElement? = null
        override suspend fun readElement(ref: OsmRef): OsmCorrectionElement {
            assertEquals(original.ref, ref)
            return original
        }
        override suspend fun createChangeset(token: String, draft: MapEditDraft): Long {
            assertEquals("fixture-token", token)
            assertNotNull(draft.localAttemptId)
            creates++
            return 22
        }
        override suspend fun updateElement(token: String, element: OsmCorrectionElement, changeset: Long,
            canDispatch: () -> Boolean): Long {
            check(canDispatch())
            assertEquals(22L, changeset)
            assertEquals("fixture-token", token)
            updates++
            applied = element.copy(version = 9, changesetId = 22, userId = 41)
            updateFailure?.let { throw it }
            return 9
        }
        override suspend fun closeChangeset(token: String, id: Long, canDispatch: () -> Boolean) {
            if (!canDispatch()) throw OsmMapEditNotDispatched()
            closes++
        }
        override suspend fun downloadCorrection(changeset: Long) = applied
        override suspend fun readChangeset(id: Long) = OsmChangesetInfo(id, 41, true)
        override suspend fun uploadNode(token: String, draft: MapEditDraft, changesetId: Long): OsmUploadedNode =
            error("Survey must never create a new node")
        override suspend fun downloadChangeset(id: Long) = emptyList<OsmCreatedNode>()
        override suspend fun downloadSingleCreatedNode(id: Long): OsmCreatedNode? = null
    }
}
