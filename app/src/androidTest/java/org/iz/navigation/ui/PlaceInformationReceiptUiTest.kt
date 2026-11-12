package org.iz.navigation.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.placeinfo.PlaceInfoKey
import org.iz.navigation.placeinfo.PlaceInfoServices
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceInformationReceiptUiTest {
    @Test fun onlySuccessfulMatchingObjectReceiptChangesContributionRevision() {
        val key = PlaceInfoKey(PlaceInfoServices.DEFAULT_ENDPOINT, OsmRef(OsmType.WAY, 12))
        val pending = MapEditDraft(latitude = 41.0, longitude = 29.0, targetType = OsmType.WAY,
            targetId = 12, status = MapEditStatus.DRAFT)
        assertEquals("", placeInformationContributionRevision(key, listOf(pending)))
        assertEquals("", placeInformationContributionRevision(key, listOf(pending.copy(status = MapEditStatus.FAILED))))
        val applied = pending.copy(status = MapEditStatus.SENT, appliedVersion = 8, submittedAt = 1000)
        assertEquals("", placeInformationContributionRevision(key, listOf(applied.copy(targetId = 13))))
        assertEquals("", placeInformationContributionRevision(key, listOf(applied.copy(targetType = OsmType.NODE))))
        assertEquals("", placeInformationContributionRevision(key, listOf(applied.copy(appliedVersion = null))))
        val revision = placeInformationContributionRevision(key, listOf(applied))
        assertEquals("", placeInformationContributionRevision(key.copy(endpoint = "https://example.invalid/api/0.6"), listOf(applied)))
        assertTrue(revision.isNotBlank())
        assertEquals(revision, placeInformationContributionRevision(key, listOf(applied, pending.copy(targetId = 14))))
        assertNotEquals(revision, placeInformationContributionRevision(key, listOf(applied.copy(appliedVersion = 9))))
        assertNull(placeInformationContributionVersion(key, listOf(pending)))
        assertNull(placeInformationContributionVersion(key.copy(endpoint = "https://example.invalid/api/0.6"), listOf(applied)))
        assertEquals(9L, placeInformationContributionVersion(key, listOf(applied, applied.copy(appliedVersion = 9))))
        assertEquals(8L, placeInformationContributionVersion(key, listOf(applied, pending.copy(status = MapEditStatus.FAILED, appliedVersion = 10))))
    }
}
