package org.iz.navigation.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkingStepsPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)

    @Before fun setup() = runBlocking { repository.restore(DiarySnapshot()) }
    @After fun cleanup() = runBlocking { repository.restore(DiarySnapshot()) }

    @Test fun onlyMatchingOpenWalkingJourneyAcceptsMonotonicStepUpdates() = runBlocking {
        val walk = repository.createJourney(Transport.WALK, temporary = false)
        assertNull(repository.getJourney(walk.id)?.stepCount)
        assertTrue(repository.recordWalkingSteps(walk.id, 17))
        repository.recordWalkingSteps(walk.id, 8)
        assertEquals(17L, repository.getJourney(walk.id)?.stepCount)
        repository.finishJourney(walk.id)
        val passenger = repository.createJourney(Transport.PASSENGER, temporary = false)
        assertFalse(repository.recordWalkingSteps(walk.id, 35))
        assertFalse(repository.recordWalkingSteps(passenger.id, 80))
        assertFalse(repository.recordWalkingSteps("missing", 1))
        assertEquals(17L, repository.getJourney(walk.id)?.stepCount)
        assertNull(repository.getJourney(passenger.id)?.stepCount)
    }

    @Test fun savingAnOlderEditorDraftCannotEraseStepsRecordedWhileItWasOpen() = runBlocking {
        val draft = repository.createJourney(Transport.WALK, temporary = false)
        repository.recordWalkingSteps(draft.id, 45)
        repository.saveJourney(draft.copy(title = "Morning walk"))
        assertEquals("Morning walk", repository.getJourney(draft.id)?.title)
        assertEquals(45L, repository.getJourney(draft.id)?.stepCount)
        repository.saveJourney(repository.getJourney(draft.id)!!.copy(transport = Transport.PASSENGER))
        assertNull(repository.getJourney(draft.id)?.stepCount)
        assertFalse(repository.recordWalkingSteps(draft.id, 60))
    }

    @Test fun expiredAndDeletedWalkingJourneysCannotReceiveSteps() = runBlocking {
        val expired = repository.createJourney(Transport.WALK, temporary = true,
            now = System.currentTimeMillis() - DiaryRules.TEMPORARY_LIFETIME_MILLIS - 1)
        assertFalse(repository.recordWalkingSteps(expired.id, 10))
        repository.deleteJourney(expired.id)
        assertFalse(repository.recordWalkingSteps(expired.id, 20))
        assertTrue(repository.snapshot().journeys.isEmpty())
    }

    @Test fun editingAfterFinishPreservesTheLatestStepsAndCannotReopenTheJourney() = runBlocking {
        val editorSnapshot = repository.createJourney(Transport.WALK, temporary = false)
        repository.recordWalkingSteps(editorSnapshot.id, 80)
        val finishedAt = editorSnapshot.startedAt + 10_000
        repository.finishJourney(editorSnapshot.id, finishedAt)
        assertTrue(repository.updateJourneyDetails(editorSnapshot.id, "Renamed walk", "Updated note", Transport.WALK))
        val updated = repository.getJourney(editorSnapshot.id)!!
        assertEquals("Renamed walk", updated.title)
        assertEquals("Updated note", updated.note)
        assertEquals(80L, updated.stepCount)
        assertEquals(finishedAt, updated.endedAt)
        assertNull(repository.activeJourney())
    }

    @Test fun editingAfterDeleteCannotRecreateTheJourney() = runBlocking {
        val editorSnapshot = repository.createJourney(Transport.CAR, temporary = false)
        repository.deleteJourney(editorSnapshot.id)
        assertFalse(repository.updateJourneyDetails(editorSnapshot.id, "Old draft", "Note", Transport.PASSENGER))
        assertNull(repository.getJourney(editorSnapshot.id))
        assertNull(repository.activeJourney())
    }

    @Test fun changingDetailsPreservesAutomaticConfirmationAndRouteWhileClearingNonwalkingSteps() = runBlocking {
        val pending = repository.createJourney(Transport.WALK, temporary = true)
        repository.recordWalkingSteps(pending.id, 18)
        val point = TrackPoint(journeyId = pending.id, latitude = 41.0, longitude = 29.0,
            accuracy = 5f, recordedAt = pending.startedAt)
        repository.addPoint(point)
        repository.confirmJourney(pending.id, Transport.WALK)
        repository.updateJourneyDetails(pending.id, "Ride", "Passenger note", Transport.PASSENGER)
        val updated = repository.getJourney(pending.id)!!
        assertEquals(Transport.PASSENGER, updated.transport)
        assertEquals(JourneyStatus.CONFIRMED, updated.status)
        assertEquals(pending.startedAt, updated.startedAt)
        assertNull(updated.expiresAt)
        assertNull(updated.endedAt)
        assertNull(updated.stepCount)
        assertEquals(listOf(point), repository.journeyPoints(pending.id))
    }

    @Test fun backupZipRoundTripPreservesStepsAndPassengerClassification() = runBlocking {
        val walk = repository.createJourney(Transport.WALK, temporary = false)
        repository.recordWalkingSteps(walk.id, 1_234)
        repository.finishJourney(walk.id)
        val passenger = repository.createJourney(Transport.PASSENGER, temporary = false)
        repository.finishJourney(passenger.id)
        val archive = File(context.cacheDir, "steps-${UUID.randomUUID()}.zip")
        try {
            val backup = DiaryBackup(context, repository)
            backup.exportTo(Uri.fromFile(archive))
            repository.restore(DiarySnapshot())
            backup.importFrom(Uri.fromFile(archive))
            assertEquals(1_234L, repository.getJourney(walk.id)?.stepCount)
            assertEquals(Transport.PASSENGER, repository.getJourney(passenger.id)?.transport)
            assertNull(repository.getJourney(passenger.id)?.stepCount)
        } finally { archive.delete() }
    }

    @Test fun versionOneBackupWithoutStepFieldRemainsReadable() {
        val original = Journey(transport = Transport.WALK, endedAt = System.currentTimeMillis())
        val json = BackupJson.encode(DiarySnapshot(journeys = listOf(original)))
        json.put("version", 1)
        json.getJSONArray("journeys").getJSONObject(0).remove("stepCount")
        val restored = BackupJson.decode(json).journeys.single()
        assertEquals(original, restored)
        assertNull(restored.stepCount)
    }

    @Test fun invalidBackupStepsAreRejectedBeforeReplacingExistingData() = runBlocking {
        val kept = repository.createJourney(Transport.PASSENGER, temporary = false)
        var rejected = false
        try {
            repository.restore(DiarySnapshot(journeys = listOf(Journey(transport = Transport.WALK, stepCount = -1))))
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(kept.id, repository.activeJourney()?.id)
    }
}
