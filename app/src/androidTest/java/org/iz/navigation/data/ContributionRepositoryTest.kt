package org.iz.navigation.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContributionRepositoryTest {
    private lateinit var repository: DiaryRepository
    private fun draft(placeId: String? = null) = ContributionDraft(placeId = placeId, latitude = 41.0, longitude = 29.0,
        observedAt = 100, kind = ContributionKind.MISSING_PLACE, text = "A public drinking fountain is here.")

    @Before fun setup() = runBlocking {
        repository = DiaryRepository(ApplicationProvider.getApplicationContext<Context>())
        repository.restore(DiarySnapshot())
    }
    @After fun cleanup() = runBlocking { repository.restore(DiarySnapshot()) }

    @Test fun concurrentSendClaimsPermitOnlyOnePublicRequestAndCannotResendUnknownOrSent() = runBlocking {
        val draft = draft()
        repository.saveContribution(draft)
        val claims = coroutineScope { (1..20).map { async { repository.beginContributionSend(draft.id, 77, 200) } }.awaitAll() }
        assertEquals(1, claims.count { it != null })
        assertTrue(repository.updateContributionResult(draft.id, 200, ContributionStatus.UNKNOWN, error = "Connection lost"))
        assertNull(repository.beginContributionSend(draft.id, 77, 300))
        assertFalse(repository.updateContributionResult(draft.id, 200, ContributionStatus.FAILED))
        assertTrue(repository.reconcileContributionResult(draft.id, 200, ContributionStatus.UNKNOWN, 123, "open"))
        assertNull(repository.beginContributionSend(draft.id, 77, 300))
        assertEquals(123L, repository.getContribution(draft.id)?.remoteNoteId)
    }

    @Test fun staleResultsCannotOverwriteNewAttemptsAndRefreshingChecksRemoteIdentity() = runBlocking {
        val draft = draft()
        repository.saveContribution(draft)
        repository.beginContributionSend(draft.id, 77, 200)
        assertTrue(repository.updateContributionResult(draft.id, 200, ContributionStatus.FAILED, error = "Rejected"))
        repository.beginContributionSend(draft.id, 77, 300)
        assertFalse(repository.updateContributionResult(draft.id, 200, ContributionStatus.SENT, 111, "open"))
        assertTrue(repository.updateContributionResult(draft.id, 300, ContributionStatus.SENT, 222, "open"))
        assertFalse(repository.refreshContributionRemoteStatus(draft.id, 111, "closed"))
        assertTrue(repository.refreshContributionRemoteStatus(draft.id, 222, "closed"))
        assertEquals("closed", repository.getContribution(draft.id)?.remoteStatus)
    }

    @Test fun deletingPlaceLeavesContributionAndDoesNotCopyPrivateVisitReview() = runBlocking {
        val place = Place(name = "Private place", googlePlaceId = "old-id")
        repository.savePlace(place)
        val visit = Visit(placeId = place.id, note = "PRIVATE")
        repository.saveVisit(visit)
        repository.saveDraft(ShareDraft(visitId = visit.id, text = "PRIVATE REVIEW"))
        assertTrue(repository.snapshot().contributions.isEmpty())
        val draft = draft(place.id)
        repository.saveContribution(draft)
        repository.deletePlace(place.id)
        assertEquals(draft, repository.getContribution(draft.id))
        assertTrue(repository.snapshot().drafts.isEmpty())
    }

    @Test fun recoveryAndRestoreTurnInterruptedSendsIntoUnknownWithoutRetrying() = runBlocking {
        val draft = draft()
        repository.saveContribution(draft)
        repository.beginContributionSend(draft.id, 77, 200)
        repository.recoverInterruptedContributions()
        assertEquals(ContributionStatus.UNKNOWN, repository.getContribution(draft.id)?.status)
        assertNull(repository.beginContributionSend(draft.id, 77, 300))
        repository.restore(DiarySnapshot(contributions = listOf(draft.copy(status = ContributionStatus.SENDING, submittedAt = 200, submittedBy = 77))))
        assertEquals(ContributionStatus.UNKNOWN, repository.getContribution(draft.id)?.status)
    }

    @Test fun malformedRestoreDoesNotDeleteExistingDiary() = runBlocking {
        val place = Place(name = "Keep me")
        repository.savePlace(place)
        var rejected = false
        try { repository.restore(DiarySnapshot(contributions = listOf(draft().copy(osmId = 42)))) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf(place), repository.snapshot().places)
    }

    @Test fun staleEditorCannotMakeAnUncertainSubmissionRetryable() = runBlocking {
        val draft = draft()
        repository.saveContribution(draft)
        repository.beginContributionSend(draft.id, 77, 200)
        repository.updateContributionResult(draft.id, 200, ContributionStatus.UNKNOWN)
        var rejected = false
        try { repository.saveContribution(draft.copy(text = "Changed in an old editor")) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(ContributionStatus.UNKNOWN, repository.getContribution(draft.id)?.status)
        assertEquals(draft.text, repository.getContribution(draft.id)?.text)
    }

    @Test fun emptyDraftCanBeSavedButNeverClaimedForPublicSubmission() = runBlocking {
        val blank = draft().copy(text = "")
        repository.saveContribution(blank)
        var rejected = false
        try { repository.beginContributionSend(blank.id, 77, 200) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(ContributionStatus.DRAFT, repository.getContribution(blank.id)?.status)
    }

    @Test fun retryInTheSameClockTickStillHasADifferentAttemptIdentity() = runBlocking {
        val draft = draft()
        repository.saveContribution(draft)
        repository.beginContributionSend(draft.id, 77, 200)
        repository.updateContributionResult(draft.id, 200, ContributionStatus.FAILED)
        val retry = repository.beginContributionSend(draft.id, 77, 200)!!
        assertEquals(201L, retry.submittedAt)
        assertFalse(repository.updateContributionResult(draft.id, 200, ContributionStatus.SENT, 111, "open"))
        assertTrue(repository.updateContributionResult(draft.id, 201, ContributionStatus.SENT, 222, "open"))
    }
}
