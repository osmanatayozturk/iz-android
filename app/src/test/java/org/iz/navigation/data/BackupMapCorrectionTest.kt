package org.iz.navigation.data

import org.junit.Assert.*
import org.junit.Test

class BackupMapCorrectionTest {
    private val create = MapEditDraft(latitude = 41.0, longitude = 29.0, name = "Örnek")
    private val correction = create.copy(targetType = OsmType.WAY, targetId = 71L)
    private val sending = correction.copy(
        surveyConfirmed = true, status = MapEditStatus.SENDING, stage = MapEditStage.UPDATE_ELEMENT,
        submittedAt = 10L, submittedBy = 11L, changesetId = 12L,
        reviewedVersion = 3L, reviewedFingerprint = "a".repeat(64),
    )

    @Test fun versionEightRecordsAreNewPlaceDrafts() {
        val legacy = BackupJson.encode(DiarySnapshot(mapEdits = listOf(create))).put("version", 8)
        val row = legacy.getJSONArray("mapEdits").getJSONObject(0)
        for (key in listOf("targetType", "targetId", "reviewedVersion", "reviewedFingerprint", "appliedVersion")) {
            row.remove(key)
        }
        assertEquals(create, BackupJson.decode(legacy).mapEdits.single())
    }

    @Test fun correctionRoundTripsAndAnInterruptedAttemptKeepsItsProofWithoutResending() {
        val json = BackupJson.encode(DiarySnapshot(mapEdits = listOf(sending)))
        assertEquals(9, json.getInt("version"))
        assertEquals(sending.copy(status = MapEditStatus.UNKNOWN), BackupJson.decode(json).mapEdits.single())
        val sent = sending.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET, appliedVersion = 4L)
        assertEquals(sent, BackupJson.decode(BackupJson.encode(DiarySnapshot(mapEdits = listOf(sent)))).mapEdits.single())
    }

    @Test fun anOlderManifestCannotDiscardACorrectionTarget() {
        val json = BackupJson.encode(DiarySnapshot(mapEdits = listOf(correction))).put("version", 8)
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(json) }
    }

    @Test fun correctionProofRequiresAnAllowedTargetAndTheNextExactVersion() {
        assertThrows(IllegalArgumentException::class.java) { validateMapEdit(correction.copy(targetId = null)) }
        assertThrows(IllegalArgumentException::class.java) { validateMapEdit(correction.copy(targetType = OsmType.RELATION)) }
        assertThrows(IllegalArgumentException::class.java) { validateMapEdit(sending.copy(reviewedFingerprint = null)) }
        assertThrows(IllegalArgumentException::class.java) {
            validateMapEdit(sending.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET, appliedVersion = 5L))
        }
    }
}
