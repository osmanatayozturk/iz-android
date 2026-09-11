package org.iz.navigation.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BackupV3Test {
    private val contribution = ContributionDraft(latitude = 41.0, longitude = 29.0, observedAt = 100, kind = ContributionKind.WRONG_DETAILS,
        text = "Entrance moved", osmType = OsmType.WAY, osmId = 123)

    @Test fun currentBackupRoundTripPreservesLegacyReviewsAndOsmAttributionSeparately() {
        val legacy = Place(id = "legacy", name = "Old place", googlePlaceId = "legacy-google-id")
        val osm = Place(id = "osm", name = "New place", latitude = 41.0, longitude = 29.0, osmType = OsmType.NODE, osmId = 42, source = PlaceSource.OSM)
        val visit = Visit(id = "visit", placeId = legacy.id, note = "Private review", rating = 4)
        val review = ShareDraft(id = "review", visitId = visit.id, text = "Saved old review", rating = 4)
        val original = DiarySnapshot(places = listOf(legacy, osm), visits = listOf(visit), drafts = listOf(review), contributions = listOf(contribution))
        val json = BackupJson.encode(original)
        assertEquals(5, json.getInt("version"))
        assertEquals(original, BackupJson.decode(JSONObject(json.toString())))
    }

    @Test fun versionsOneAndTwoRemainLegacyAndNeverCreatePublicContributions() {
        for (version in 1..2) {
            val original = DiarySnapshot(places = listOf(Place(id = "old", name = "Legacy", googlePlaceId = "old-id")))
            val json = BackupJson.encode(original).put("version", version)
            json.remove("contributions")
            json.getJSONArray("places").getJSONObject(0).apply { remove("osmType"); remove("osmId"); remove("source") }
            val restored = BackupJson.decode(json)
            assertEquals(original, restored)
            assertTrue(restored.contributions.isEmpty())
        }
    }

    @Test fun interruptedSubmissionRestoresAsUnknownAndRetainsAttemptIdentity() {
        val sending = contribution.copy(status = ContributionStatus.SENDING, submittedAt = 200, submittedBy = 77)
        val decoded = BackupJson.decode(BackupJson.encode(DiarySnapshot(contributions = listOf(sending)))).contributions.single()
        assertEquals(sending.copy(status = ContributionStatus.UNKNOWN), decoded)
    }

    @Test fun malformedOsmReferenceAndDraftCannotPassDecodeValidation() {
        val snapshot = DiarySnapshot(places = listOf(Place(name = "OSM", osmType = OsmType.NODE, osmId = 42, source = PlaceSource.OSM)), contributions = listOf(contribution))
        val badReference = BackupJson.encode(snapshot)
        badReference.getJSONArray("places").getJSONObject(0).put("osmId", -1)
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(badReference) }
        val badDraft = BackupJson.encode(snapshot)
        badDraft.getJSONArray("contributions").getJSONObject(0).put("latitude", 91)
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(badDraft) }
        val duplicate = BackupJson.encode(snapshot)
        duplicate.getJSONArray("contributions").put(duplicate.getJSONArray("contributions").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode(duplicate) }
    }

    @Test fun draftsRemainIndependentOfDeletedPlacesButCannotForgeSubmissionState() {
        validateMapSnapshot(DiarySnapshot(contributions = listOf(contribution.copy(placeId = "deleted-place"))))
        assertThrows(IllegalArgumentException::class.java) { validateMapSnapshot(DiarySnapshot(contributions = listOf(contribution.copy(status = ContributionStatus.SENT)))) }
        assertThrows(IllegalArgumentException::class.java) { validateMapSnapshot(DiarySnapshot(contributions = listOf(contribution.copy(osmType = null)))) }
    }
}
