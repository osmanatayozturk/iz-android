package org.iz.navigation.data

import org.iz.navigation.integration.*
import org.junit.Assert.*
import org.junit.Test

class BackupSurveyTest {
    private val observation = SurveyObservation(7, "2024-02-29", hours = SurveyFieldObservation(SurveyAction.EDIT, "invalid raw draft", false, "24/7"),
        wheelchair = SurveyFieldObservation(SurveyAction.UNKNOWN))
    private val draft = MapEditDraft(latitude=39.93,longitude=32.85,targetType=OsmType.NODE,targetId=7,surveyPayload=observation.encode())

    @Test fun allIntentsAndInvalidRawHoursPersistWithoutPortableLocalCloseAuthority() {
        for (action in SurveyAction.entries) {
            val row = draft.copy(surveyPayload=observation.copy(hours=observation.hours.copy(action=action)).encode())
            val json = BackupJson.encode(DiarySnapshot(mapEdits=listOf(row)))
            assertEquals(12,json.getInt("version"))
            val restored = BackupJson.decode(json).mapEdits.single()
            assertEquals(row,restored)
            assertFalse(restored.localChangesetCreated); assertNull(restored.localAttemptId)
            assertFalse(json.toString().contains("localChangesetCreated"))
        }
    }

    @Test fun oldArchivesHaveNoEnrichmentIntentAndCannotSmuggleOne() {
        val legacy = draft.copy(surveyPayload=null)
        val json = BackupJson.encode(DiarySnapshot(mapEdits=listOf(legacy))).put("version",9)
        json.getJSONArray("mapEdits").getJSONObject(0).remove("surveyPayload")
        assertEquals(legacy,BackupJson.decode(json).mapEdits.single())
        json.getJSONArray("mapEdits").getJSONObject(0).put("surveyPayload",observation.encode())
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(json) }
    }

    @Test fun movedBackDeviceClockDoesNotDiscardStructurallyValidDraftDuringRestore() {
        val future = draft.copy(surveyPayload=observation.copy(observedOn="2099-01-01").encode())
        assertEquals(future,BackupJson.decode(BackupJson.encode(DiarySnapshot(mapEdits=listOf(future)))).mapEdits.single())
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(future.copy(surveyConfirmed=true)) }
    }

    @Test fun incompleteWeekStateSurvivesTheSameRoomPayloadAndBackupCodec() {
        val week=SurveyWeek(List(7) { SurveyDayHours(SurveyDayState.UNKNOWN) }).editDay(0,
            SurveyDayHours(SurveyDayState.OPEN,listOf(SurveyHoursInterval("",""))))
        val observed=observation.copy(hours=observation.hours.copy(value=week.expression(),formDraft=week.encodeDraft()))
        val row=draft.copy(surveyPayload=observed.encode())
        val restored=BackupJson.decode(BackupJson.encode(DiarySnapshot(mapEdits=listOf(row)))).mapEdits.single()
        assertEquals(row,restored)
        val after=requireNotNull(restored.surveyObservation()).hours
        assertEquals(week.expression(),requireNotNull(SurveyOpeningHours.fromDraft(requireNotNull(after.formDraft))).expression())
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(restored.copy(surveyConfirmed=true)) }
    }

    @Test fun staleStructuredStateCannotOverrideAnAdvancedExpressionAtPublication() {
        val week=SurveyWeek(List(7) { SurveyDayHours(SurveyDayState.UNKNOWN) })
        val changed=observation.copy(hours=observation.hours.copy(value="24/7",formDraft=week.encodeDraft(),fullyObserved=true,before="24/7"))
        assertEquals(changed,decodeSurveyObservation(changed.encode()))
        assertThrows(IllegalArgumentException::class.java) { changed.publicChanges(mapOf("opening_hours" to "24/7")) }
    }
}
