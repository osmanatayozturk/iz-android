package org.iz.navigation.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OsmSurveyModelsTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val tags = mapOf("opening_hours" to "24/7", "wheelchair" to "yes", "check_date:opening_hours" to "2025-01-01", "check_date:wheelchair" to "2026-01-01")
    private fun venue(tags: Map<String,String> = this.tags, version: Long = 7) = SurveyVenue(OsmRef(OsmType.NODE, 7), version, "Örnek", 39.93, 32.85, tags)

    @Test fun eachFieldUsesItsOwnCalendarAnniversaryIncludingLeapYear() {
        val v = venue(tags + mapOf("check_date:opening_hours" to "2024-02-29", "check_date:wheelchair" to "2024-03-01"))
        assertTrue(v.tasks(LocalDate.of(2025, 2, 27)).isEmpty())
        assertEquals(listOf(SurveyField.OPENING_HOURS), v.tasks(LocalDate.of(2025, 2, 28)).map { it.field })
        assertEquals(2, v.tasks(LocalDate.of(2025, 3, 1)).size)
        assertEquals(SurveyTaskStatus.DUE, venue().tasks(today).single().status)
    }

    @Test fun missingPartialAndUninterpretableDateDoNotUseObjectModificationTime() {
        val v = venue(mapOf("opening_hours" to "Mo 09:00-17:00; Tu-Su unknown", "wheelchair" to "yes",
            "check_date:opening_hours" to "2026-11-10", "check_date:wheelchair" to "2026-99-99", "timestamp" to "2026-11-11"))
        assertEquals(listOf(SurveyTaskStatus.PARTIAL, SurveyTaskStatus.UNKNOWN_DATE), v.tasks(today).map { it.status })
        assertEquals(2, venue(emptyMap()).tasks(today).count { it.status == SurveyTaskStatus.MISSING })
        assertEquals(SurveyTaskStatus.UNKNOWN_DATE, venue(mapOf("wheelchair" to "yes", "check_date:wheelchair" to "2027-01-01")).tasks(today).last().status)
    }

    @Test fun unchangedVerificationChangesOnlyItsOwnDate() {
        val observation = SurveyObservation(7, today.toString(), hours = SurveyFieldObservation(SurveyAction.VERIFY,
            fullyObserved = true, before = "24/7", beforeDate = "2025-01-01"))
        assertEquals(mapOf("check_date:opening_hours" to today.toString()), observation.publicChanges(tags, today))
    }

    @Test fun unknownAndUntouchedPreserveUnusualOrInvalidExistingData() {
        val original = mapOf("opening_hours" to "invalid legacy hours", "wheelchair" to "designated", "check_date:wheelchair" to "old-date")
        val unknown = SurveyObservation(7, today.toString(), wheelchair = SurveyFieldObservation(SurveyAction.UNKNOWN))
        assertTrue(unknown.publicChanges(original, today).isEmpty())
        val edited = unknown.copy(wheelchair = SurveyFieldObservation(SurveyAction.EDIT, "limited", true, "designated", "old-date"))
        assertEquals(mapOf("wheelchair" to "limited", "check_date:wheelchair" to today.toString()), edited.publicChanges(original, today))
    }

    @Test fun partialHoursChangeNoVerificationDateAndCannotBeCalledFullyObserved() {
        val edited = SurveyFieldObservation(SurveyAction.EDIT, "Mo 09:00-17:00; Tu-Su unknown", false, "24/7", "2025-01-01")
        val observation = SurveyObservation(7, today.toString(), hours = edited)
        assertEquals(mapOf("opening_hours" to edited.value), observation.publicChanges(tags, today))
        assertThrows(IllegalArgumentException::class.java) { observation.copy(hours = edited.copy(fullyObserved = true)).publicChanges(tags, today) }
    }

    @Test fun allWheelchairChoicesMapExplicitlyAndFutureDatesOrStaleBeforeValuesBlock() {
        for (value in listOf("yes", "limited", "no")) {
            val observation = SurveyObservation(7, today.toString(), wheelchair = SurveyFieldObservation(SurveyAction.EDIT, value, true, "yes", "2026-01-01"))
            val changes = observation.publicChanges(tags, today)
            assertEquals(today.toString(), changes["check_date:wheelchair"])
            assertEquals(value, (tags + changes)["wheelchair"])
            assertThrows(IllegalArgumentException::class.java) { observation.copy(observedOn = today.plusDays(1).toString()).publicChanges(tags, today) }
            assertThrows(IllegalArgumentException::class.java) { observation.publicChanges(tags + ("wheelchair" to "no"), today) }
        }
    }

    @Test fun invalidAdvancedRawTextRoundTripsAsDraftButCannotPublish() {
        val observation = SurveyObservation(7, "2024-02-29", hours = SurveyFieldObservation(SurveyAction.EDIT, "Mo nonsense; this raw text must survive", false, "24/7", "2025-01-01"))
        assertEquals(observation, decodeSurveyObservation(observation.encode()))
        assertThrows(IllegalArgumentException::class.java) { observation.publicChanges(tags, today) }
        assertThrows(IllegalArgumentException::class.java) { observation.copy(observedOn = "2024-02-30").publicChanges(tags, today) }
        val tooLong = observation.copy(hours = observation.hours.copy(value = "x".repeat(256)))
        assertEquals(tooLong, decodeSurveyObservation(tooLong.encode()))
        assertThrows(IllegalArgumentException::class.java) { tooLong.publicChanges(tags, today) }
    }

    @Test fun authorizedLocalSuccessHidesStaleSnapshotButNotPartialOrNewerObject() {
        val observation = SurveyObservation(7, today.toString(), hours = SurveyFieldObservation(SurveyAction.VERIFY, fullyObserved = true, before = "24/7", beforeDate = "2025-01-01"))
        val receipt = MapEditDraft(latitude = 39.93, longitude = 32.85, targetType = OsmType.NODE, targetId = 7,
            surveyConfirmed = true, status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
            submittedAt = 10, submittedBy = 41, changesetId = 22, reviewedVersion = 7, reviewedFingerprint = "a".repeat(64),
            appliedVersion = 8, localAttemptId = "local-attempt", localChangesetCreated = true, surveyPayload = observation.encode())
        assertTrue(applySurveyReceipts(venue(), listOf(receipt), 41).tasks(today).isEmpty())
        assertEquals(venue(), applySurveyReceipts(venue(), listOf(receipt.copy(localChangesetCreated = false)), 41))
        assertEquals(venue(), applySurveyReceipts(venue(), listOf(receipt), 42))
        assertEquals(venue(version = 9), applySurveyReceipts(venue(version = 9), listOf(receipt), 41))
        val partial = observation.copy(hours = observation.hours.copy(action = SurveyAction.EDIT, value = "Mo 09:00-17:00", fullyObserved = false))
        val patched = applySurveyReceipts(venue(), listOf(receipt.copy(surveyPayload = partial.encode())), 41)
        assertEquals(SurveyTaskStatus.PARTIAL, patched.tasks(today).single().status)
    }

    @Test fun existingOnlyVenueTypesCannotBecomeNewPlaceCreations() {
        for (preset in listOf(MapPlacePreset.HOSPITAL, MapPlacePreset.UNIVERSITY, MapPlacePreset.PUBLIC_BATH)) {
            assertFalse(preset.canCreate)
            assertThrows(IllegalArgumentException::class.java) { validateMapEdit(MapEditDraft(latitude=0.0,longitude=0.0,preset=preset)) }
        }
    }

    @Test fun existingDesignatedAccessIsCompleteAndCanBeReverifiedWithoutChangingValue() {
        val original = tags + mapOf("wheelchair" to "designated", "check_date:wheelchair" to "2026-01-01")
        assertTrue(venue(original).tasks(today).none { it.field == SurveyField.WHEELCHAIR })
        val due = original + ("check_date:wheelchair" to "2025-01-01")
        assertEquals(SurveyTaskStatus.DUE,venue(due).tasks(today).single { it.field == SurveyField.WHEELCHAIR }.status)
        val verify = SurveyObservation(7,today.toString(),wheelchair=SurveyFieldObservation(SurveyAction.VERIFY,
            fullyObserved=true,before="designated",beforeDate="2025-01-01"))
        assertEquals(mapOf("check_date:wheelchair" to today.toString()),verify.publicChanges(due,today))
        assertThrows(IllegalArgumentException::class.java) {
            verify.copy(wheelchair=verify.wheelchair.copy(action=SurveyAction.EDIT,value="designated")).publicChanges(due,today)
        }
    }

    @Test fun surveyPayloadRejectsDeepContainersBeforeJsonParsingButNotBracketsInsideStrings() {
        val observation = SurveyObservation(7,today.toString(),hours=SurveyFieldObservation(value="[{}]".repeat(80)))
        assertEquals(observation,decodeSurveyObservation(observation.encode()))
        val deeplyNested = observation.encode().dropLast(1) + ",\"extra\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}"
        assertThrows(IllegalArgumentException::class.java) { decodeSurveyObservation(deeplyNested) }
    }

    @Test fun surveyOnlySendingRowsKeepTheExistingNodeAndWayAttemptInvariants() {
        val observation = SurveyObservation(7,today.toString(),hours=SurveyFieldObservation(SurveyAction.VERIFY,
            fullyObserved=true,before="24/7",beforeDate="2025-01-01"))
        for (type in listOf(OsmType.NODE,OsmType.WAY)) {
            val sending = MapEditDraft(latitude=39.93,longitude=32.85,targetType=type,targetId=7,surveyPayload=observation.encode(),
                surveyConfirmed=true,status=MapEditStatus.SENDING,stage=MapEditStage.UPDATE_ELEMENT,
                submittedAt=10,submittedBy=41,changesetId=22,reviewedVersion=7,reviewedFingerprint="a".repeat(64),
                localAttemptId="local-attempt",localChangesetCreated=true)
            validateMapEdit(sending)
            validateMapEdit(sending.copy(status=MapEditStatus.SENT,stage=MapEditStage.CLOSE_CHANGESET,appliedVersion=8))
        }
    }
}
