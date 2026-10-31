package org.iz.navigation.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEditDeletionTest {
    private fun draft() = MapEditDraft(
        id = "00000000-0000-0000-0000-000000000001",
        latitude = 0.0,
        longitude = 1.0,
        name = "First draft",
        observedAt = 100L,
        surveyConfirmed = true,
    )

    private fun attempted(status: MapEditStatus) = draft().copy(
        status = status,
        stage = if (status == MapEditStatus.SENT) MapEditStage.CLOSE_CHANGESET else MapEditStage.CREATE_CHANGESET,
        submittedAt = 200L,
        submittedBy = 7L,
        changesetId = 11L,
        remoteNodeId = if (status == MapEditStatus.SENT) 13L else null,
        remoteNodeVersion = if (status == MapEditStatus.SENT) 1L else null,
        changesetClosed = status == MapEditStatus.SENT,
        error = if (status == MapEditStatus.FAILED) "Rejected" else null,
    )

    @Test fun matchingDraftCanBeDeleted() {
        val expected = draft()
        assertTrue(mapEditDeletionMatches(expected, expected.copy()))
    }

    @Test fun matchingFailedDraftCanBeDeleted() {
        val expected = attempted(MapEditStatus.FAILED)
        assertTrue(mapEditDeletionMatches(expected, expected.copy()))
    }

    @Test fun missingOrDifferentIdCannotBeDeleted() {
        val expected = draft()
        assertFalse(mapEditDeletionMatches(expected, null))
        assertFalse(mapEditDeletionMatches(expected, expected.copy(id = "00000000-0000-0000-0000-000000000002")))
    }

    @Test fun changedContentCannotBeDeleted() {
        val expected = draft()
        assertFalse(mapEditDeletionMatches(expected, expected.copy(name = "Edited draft")))
        assertFalse(mapEditDeletionMatches(expected, expected.copy(latitude = 0.1)))
        assertFalse(mapEditDeletionMatches(expected, expected.copy(observedAt = 101L)))
    }

    @Test fun changedAttemptMetadataCannotBeDeleted() {
        val expected = attempted(MapEditStatus.FAILED)
        assertFalse(mapEditDeletionMatches(expected, expected.copy(submittedAt = 201L)))
        assertFalse(mapEditDeletionMatches(expected, expected.copy(changesetId = 12L)))
        assertFalse(mapEditDeletionMatches(expected, expected.copy(error = "Later attempt")))
    }

    @Test fun sendingUnknownSentNeverDeleteEvenExactSnapshot() {
        for (status in listOf(MapEditStatus.SENDING, MapEditStatus.UNKNOWN, MapEditStatus.SENT)) {
            val current = attempted(status)
            assertFalse(status.name, mapEditDeletionMatches(current.copy(), current))
        }
    }
}