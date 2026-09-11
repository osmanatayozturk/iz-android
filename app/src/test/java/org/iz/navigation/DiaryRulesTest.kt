package org.iz.navigation

import org.iz.navigation.data.*
import org.junit.Assert.*
import org.junit.Test

class DiaryRulesTest {
    @Test fun temporaryExpiresAtBoundaryAndConfirmationClearsExpiry() {
        val journey = Journey(startedAt = 1_000, status = JourneyStatus.TEMPORARY, expiresAt = 2_000)
        assertFalse(DiaryRules.isExpired(journey, 1_999))
        assertTrue(DiaryRules.isExpired(journey, 2_000))
        val confirmed = DiaryRules.confirmed(journey, Transport.MOTORCYCLE)
        assertNull(confirmed.expiresAt)
        assertEquals(Transport.MOTORCYCLE, confirmed.transport)
        assertFalse(DiaryRules.isExpired(confirmed, Long.MAX_VALUE))
    }

    @Test fun temporaryWithoutExplicitExpiryStillExpiresAfter24Hours() {
        val journey = Journey(startedAt = 5_000, status = JourneyStatus.TEMPORARY)
        assertFalse(DiaryRules.isExpired(journey, 5_000 + DiaryRules.TEMPORARY_LIFETIME_MILLIS - 1))
        assertTrue(DiaryRules.isExpired(journey, 5_000 + DiaryRules.TEMPORARY_LIFETIME_MILLIS))
    }

    @Test fun distanceDoesNotBridgeGapsBadFixesOrDifferentJourneys() {
        val first = point(0, 0.0)
        val second = point(10_000, 0.001)
        assertEquals(111.2, DiaryRules.distanceMeters(listOf(first, second)), 0.2)
        assertEquals(0.0, DiaryRules.distanceMeters(listOf(first, second.copy(breakBefore = true))), 0.0)
        assertEquals(0.0, DiaryRules.distanceMeters(listOf(first, second.copy(recordedAt = 180_000))), 0.0)
        assertEquals(0.0, DiaryRules.distanceMeters(listOf(first, second.copy(journeyId = "another"))), 0.0)
        val bad = point(5_000, 0.0005).copy(accuracy = 150f)
        assertEquals(0.0, DiaryRules.distanceMeters(listOf(first, bad, second)), 0.0)
    }

    @Test fun impossibleSpeedAndDuplicateTimestampAreNotDistance() {
        val first = point(0, 0.0)
        assertEquals(0.0, DiaryRules.distanceMeters(listOf(first, point(1_000, 1.0))), 0.0)
        assertEquals(0.0, DiaryRules.distanceMeters(listOf(first, point(0, 0.001))), 0.0)
    }

    @Test fun stopSummaryExcludesUnknownGaps() {
        val points = listOf(point(0, 0.0), point(10_000, 0.0), point(300_000, 0.0))
        assertEquals(10_000L, DiaryRules.stoppedDurationMillis(points))
    }

    @Test fun backupRestoreClosesOpenJourneyWithoutReopeningFinishedOne() {
        val active = Journey(startedAt = 100)
        val restored = DiaryRules.restored(active, 200)
        assertEquals(200L, restored.endedAt)
        assertTrue(restored.interrupted)
        val ended = active.copy(endedAt = 150)
        assertEquals(ended, DiaryRules.restored(ended, 200))
    }

    @Test fun unsafePhotoPathsAreRejected() {
        listOf("../secret", "photos/../secret", "photos/./x", "photos//x", "photos/C:/x", "photos/a\\b", "/photos/a", "photos/").forEach {
            assertFalse(it, DiaryRules.isSafePhotoPath(it))
        }
        assertTrue(DiaryRules.isSafePhotoPath("photos/3c0c76aa.jpg"))
    }

    @Test(expected = IllegalArgumentException::class) fun backupRejectsDanglingVisit() {
        DiaryRules.validate(DiarySnapshot(visits = listOf(Visit(placeId = "missing"))))
    }

    @Test(expected = IllegalArgumentException::class) fun backupRejectsPhotoInWrongVisitDraft() {
        val place = Place(name = "Sahil")
        val first = Visit(placeId = place.id)
        val second = Visit(placeId = place.id)
        val photo = Photo(relativePath = "photos/test.jpg", visitId = first.id)
        DiaryRules.validate(DiarySnapshot(places = listOf(place), visits = listOf(first, second), photos = listOf(photo),
            drafts = listOf(ShareDraft(visitId = second.id, photoIds = listOf(photo.id)))))
    }

    @Test fun repeatedVisitsAreSeparateValidRecords() {
        val place = Place(name = "Park")
        DiaryRules.validate(DiarySnapshot(places = listOf(place), visits = listOf(Visit(placeId = place.id), Visit(placeId = place.id))))
    }

    private fun point(time: Long, longitude: Double) = TrackPoint(journeyId = "trip", latitude = 0.0, longitude = longitude, recordedAt = time, accuracy = 5f)
}
