package org.iz.navigation.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.tracking.TrackingPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticCandidateResetTest {
    private val repository = DiaryRepository(ApplicationProvider.getApplicationContext<Context>())
    @Before fun setup() = runBlocking { repository.restore(DiarySnapshot()) }
    @After fun cleanup() = runBlocking { repository.restore(DiarySnapshot()) }

    @Test fun timeoutReplacesCandidateWithEmptyRouteAndFreshWindowWithoutNeedingActivityEnter() = runBlocking {
        val old = repository.createJourney(Transport.WALK, temporary = true)
        repository.addPoint(point(old, 0.0, old.startedAt))
        repository.addPoint(point(old, 100.0, old.startedAt + 10_000))
        repository.recordWalkingSteps(old.id, 40)
        val now = old.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS
        val next = repository.resolveAutomaticCandidate(old.id, now)!!
        assertNotEquals(old.id, next.id)
        assertNull(repository.getJourney(old.id))
        assertEquals(now, next.startedAt)
        assertEquals(now + DiaryRules.TEMPORARY_LIFETIME_MILLIS, next.expiresAt)
        assertEquals(Transport.WALK, next.transport)
        assertEquals(JourneyStatus.TEMPORARY, next.status)
        assertNull(next.stepCount)
        assertTrue(repository.snapshot().points.isEmpty())
        assertEquals(next.id, repository.activeJourney()?.id)
        assertNull(repository.resolveAutomaticCandidate(old.id, now + 1))
        assertEquals(listOf(next), repository.snapshot().journeys)
        assertFalse(repository.updateJourneyDetails(old.id, "Stale editor", "", Transport.CAR))
    }

    @Test fun reachingThresholdBeforeDeadlinePreservesTheOriginalJourneyAndRoute() = runBlocking {
        val old = repository.createJourney(Transport.CAR, temporary = true)
        val points = (0..6).map { point(old, it * 85.0, old.startedAt + it * 60_000L) }
        points.forEach { repository.addPoint(it) }
        val resolved = repository.resolveAutomaticCandidate(old.id, old.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS)!!
        assertEquals(old.id, resolved.id)
        assertEquals(old.startedAt, resolved.startedAt)
        assertEquals(JourneyStatus.CONFIRMED, resolved.status)
        assertNull(resolved.expiresAt)
        assertEquals(points, repository.journeyPoints(old.id))
    }

    @Test fun staleTimeoutDoesNotEraseAJourneyPromotedByAVisit() = runBlocking {
        val old = repository.createJourney(Transport.WALK, temporary = true)
        val place = Place(name = "Saved stop")
        repository.savePlace(place)
        val visit = Visit(placeId = place.id, journeyId = old.id)
        repository.saveVisit(visit)
        val resolved = repository.resolveAutomaticCandidate(old.id, old.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS)!!
        assertEquals(old.id, resolved.id)
        assertEquals(JourneyStatus.CONFIRMED, resolved.status)
        assertEquals(listOf(visit), repository.snapshot().visits)
    }

    @Test fun finishedTemporaryAndManualJourneysAreNeverRolledIntoANewWindow() = runBlocking {
        val old = repository.createJourney(Transport.WALK, temporary = true)
        repository.finishJourney(old.id, old.startedAt + 1000)
        assertEquals(old.id, repository.resolveAutomaticCandidate(old.id, old.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS)?.id)
        assertEquals(JourneyStatus.TEMPORARY, repository.getJourney(old.id)?.status)
        assertEquals(old.expiresAt, repository.getJourney(old.id)?.expiresAt)
        val manual = repository.createJourney(Transport.CAR, temporary = false)
        assertEquals(manual, repository.resolveAutomaticCandidate(manual.id, manual.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS))
        assertEquals(2, repository.snapshot().journeys.size)
    }

    @Test fun exactDeadlinePointCannotRescueTheExpiredCandidateOrLeakIntoTheReplacement() = runBlocking {
        val old = repository.createJourney(Transport.WALK, temporary = true)
        for (index in 0..14) repository.addPoint(point(old, index * 35.0, old.startedAt + index * 60_000L))
        val deadline = old.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS
        repository.addPoint(point(old, 510.0, deadline))
        val next = repository.resolveAutomaticCandidate(old.id, deadline)!!
        assertNotEquals(old.id, next.id)
        repository.addPoint(point(old, 600.0, deadline + 1))
        repository.addPoint(point(next, 510.0, deadline - 1))
        assertTrue(repository.snapshot().points.isEmpty())
        assertFalse(repository.recordWalkingSteps(old.id, 99))
        assertNull(repository.getJourney(next.id)?.stepCount)
    }

    @Test fun disablingDetectionAllowsTimeoutCleanupWithoutStartingAnotherCandidate() = runBlocking {
        val old = repository.createJourney(Transport.WALK, temporary = true)
        assertNull(repository.resolveAutomaticCandidate(old.id, old.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS, restart = false))
        assertTrue(repository.snapshot().journeys.isEmpty())
        assertNull(repository.activeJourney())
    }

    private fun point(journey: Journey, meters: Double, at: Long) = TrackPoint(journeyId = journey.id,
        latitude = Math.toDegrees(meters / 6_371_000.0), longitude = 0.0, recordedAt = at, accuracy = 5f)
}
