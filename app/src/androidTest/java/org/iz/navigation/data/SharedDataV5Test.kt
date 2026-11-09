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

/** Runs only on the disposable QA emulator: each test replaces its local diary. No OSM calls. */
@RunWith(AndroidJUnit4::class)
class SharedDataV5Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val watch = WatchHealthRepository(context)
    private fun draft(placeId: String? = null) = MapEditDraft(placeId = placeId, latitude = 41.0, longitude = 29.0,
        preset = MapPlacePreset.DRINKING_WATER, observedAt = 100, surveyConfirmed = true)

    @Before fun setup() = runBlocking { repository.restore(DiarySnapshot()) }
    @After fun cleanup() = runBlocking { repository.restore(DiarySnapshot()) }

    @Test fun concurrentClaimsAndStaleAttemptsCannotPublishTwice() = runBlocking {
        val draft = draft(); repository.saveMapEdit(draft)
        val claims = coroutineScope { (1..16).map { async { repository.beginMapEditSend(draft.id, 77, 200) } }.awaitAll() }
        assertEquals(1, claims.count { it != null })
        val first = claims.filterNotNull().single()
        assertTrue(repository.compareAndSetMapEdit(first, first.copy(status = MapEditStatus.FAILED)))
        val second = repository.beginMapEditSend(draft.id, 77, 200)!!
        assertEquals(201L, second.submittedAt)
        assertFalse(repository.compareAndSetMapEdit(first, first.copy(changesetId = 7, stage = MapEditStage.UPLOAD_NODE)))
        assertTrue(repository.snapshot().places.isEmpty())
    }

    @Test fun interruptedRestoreBlocksLatePublishAndPreservesRemoteAttempt() = runBlocking {
        val draft = draft(); repository.saveMapEdit(draft)
        val first = repository.beginMapEditSend(draft.id, 77, 200)!!
        val uploading = first.copy(changesetId = 7, stage = MapEditStage.UPLOAD_NODE)
        assertTrue(repository.compareAndSetMapEdit(first, uploading))
        repository.restore(repository.snapshot())
        val restored = repository.getMapEdit(draft.id)!!
        assertEquals(MapEditStatus.UNKNOWN, restored.status)
        assertEquals(7L, restored.changesetId)
        assertEquals(77L, restored.submittedBy)
        assertNull(repository.beginMapEditSend(draft.id, 77, 300))
        assertFalse(repository.compareAndSetMapEdit(uploading, uploaded(uploading)))
        assertTrue(repository.snapshot().places.isEmpty())
    }

    @Test fun correctionClaimCannotUseLegacyCreateAndDoesNotCreateAPrivatePlace() = runBlocking {
        val draft = draft().copy(name = "Düzeltilen ad", targetType = OsmType.WAY, targetId = 42L)
        repository.saveMapEdit(draft)
        assertNull(repository.beginMapEditSend(draft.id, 77L, 200L))
        val claimed = draft.copy(status = MapEditStatus.SENDING, stage = MapEditStage.CREATE_CHANGESET,
            submittedAt = 200L, submittedBy = 77L, reviewedVersion = 5L, reviewedFingerprint = "a".repeat(64),
            localAttemptId = "fixture-correction-attempt")
        assertTrue(repository.compareAndSetMapEdit(draft, claimed))
        assertFalse(repository.compareAndSetMapEdit(draft, claimed))
        val uploading = requireNotNull(repository.recordLocalMapEditChangeset(claimed, 17L))
        assertTrue(uploading.localChangesetCreated)
        repository.restore(repository.snapshot())
        val unknown = requireNotNull(repository.getMapEdit(draft.id))
        assertEquals(uploading.copy(status = MapEditStatus.UNKNOWN, localAttemptId = null, localChangesetCreated = false), unknown)
        val sent = unknown.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET, appliedVersion = 6L)
        assertTrue(repository.compareAndSetMapEdit(unknown, sent))
        assertTrue(repository.snapshot().places.isEmpty())
    }

    @Test fun anAccountChangeDuringEditRollsBackTheLocalAttempt() = runBlocking {
        val draft = draft().copy(name = "Düzeltilen ad", targetType = OsmType.NODE, targetId = 42L)
        repository.saveMapEdit(draft)
        val claimed = draft.copy(status = MapEditStatus.SENDING, stage = MapEditStage.CREATE_CHANGESET,
            submittedAt = 200L, submittedBy = 77L, reviewedVersion = 5L, reviewedFingerprint = "b".repeat(64),
            localAttemptId = "fixture-account-attempt")
        assertTrue(repository.compareAndSetMapEdit(draft, claimed))
        val failed = claimed.copy(status = MapEditStatus.FAILED)
        assertTrue(repository.compareAndSetMapEdit(claimed, failed))
        var checks = 0
        val save = runCatching {
            repository.saveMapEdit(failed.copy(name = "Başka ad")) { current ->
                assertEquals(failed, current)
                ++checks == 1
            }
        }
        assertEquals(2, checks)
        assertTrue(save.exceptionOrNull() is IllegalStateException)
        assertEquals(failed, repository.getMapEdit(failed.id))
        checks = 0
        val delete = runCatching { repository.deleteMapEditIfUnchanged(failed) { ++checks == 1 } }
        assertEquals(2, checks)
        assertTrue(delete.exceptionOrNull() is IllegalStateException)
        assertEquals(failed, repository.getMapEdit(failed.id))
    }

    @Test fun restoreSameIdClearsLocalReceiptAndRejectsLateCreateResponse() = runBlocking {
        val draft = draft()
        repository.saveMapEdit(draft)
        val claim = requireNotNull(repository.beginMapEditSend(draft.id, 77, 200))
        val local = requireNotNull(repository.recordLocalMapEditChangeset(claim, 7))
        assertTrue(local.localChangesetCreated)
        val published = uploaded(local)
        assertTrue(repository.compareAndSetMapEdit(local, published))
        repository.restore(repository.snapshot())
        val restored = requireNotNull(repository.getMapEdit(draft.id))
        assertEquals(published.copy(localAttemptId = null, localChangesetCreated = false), restored)
        assertFalse(repository.isMapEditCurrentForDispatch(published))
        assertNull(repository.recordLocalMapEditChangeset(claim, 7))
    }

    @Test fun sameIdAndTimestampAfterRestoreCannotClaimThePriorCreateResponse() = runBlocking {
        val draft = draft()
        repository.saveMapEdit(draft)
        val first = requireNotNull(repository.beginMapEditSend(draft.id, 77, 200))
        val portable = first.copy(status = MapEditStatus.FAILED, submittedAt = 199L, localAttemptId = null)
        repository.restore(DiarySnapshot(mapEdits = listOf(portable)))
        val second = requireNotNull(repository.beginMapEditSend(draft.id, 77, 200))
        assertNotEquals(first.localAttemptId, second.localAttemptId)
        assertEquals(first.copy(localAttemptId = second.localAttemptId), second)
        assertNull(repository.recordLocalMapEditChangeset(first, 7))
        assertTrue(requireNotNull(repository.recordLocalMapEditChangeset(second, 8)).localChangesetCreated)
    }

    @Test fun aPublishedNodeCreatesOneLocalPlaceAndClosingDoesNotDuplicateIt() = runBlocking {
        val draft = draft(); repository.saveMapEdit(draft)
        val first = repository.beginMapEditSend(draft.id, 77, 200)!!
        val uploading = first.copy(changesetId = 7, stage = MapEditStage.UPLOAD_NODE)
        assertTrue(repository.compareAndSetMapEdit(first, uploading))
        val sent = uploaded(uploading)
        assertTrue(repository.compareAndSetMapEdit(uploading, sent))
        assertFalse(repository.compareAndSetMapEdit(uploading, sent))
        assertTrue(repository.compareAndSetMapEdit(sent, sent.copy(changesetClosed = true)))
        val place = repository.snapshot().places.single()
        assertEquals(MapPlacePreset.DRINKING_WATER.label, place.name)
        assertEquals(OsmType.NODE, place.osmType)
        assertEquals(123L, place.osmId)
        assertEquals(PlaceSource.OSM, place.source)
        assertFalse(sent.publicTags().containsKey("name"))
    }

    @Test fun bindingExistingPlacePreservesPrivateVisitAndDeletedPlaceIsNotResurrected() = runBlocking {
        for (deleteBeforeResult in listOf(false, true)) {
            repository.restore(DiarySnapshot())
            val place = Place(name = "My private label", latitude = 41.0, longitude = 29.0)
            val visit = Visit(placeId = place.id, note = "PRIVATE observation", rating = 4)
            repository.savePlace(place); repository.saveVisit(visit)
            val draft = draft(place.id); repository.saveMapEdit(draft)
            val first = repository.beginMapEditSend(draft.id, 77, 200)!!
            val uploading = first.copy(changesetId = 7, stage = MapEditStage.UPLOAD_NODE)
            assertTrue(repository.compareAndSetMapEdit(first, uploading))
            if (deleteBeforeResult) repository.deletePlace(place.id)
            assertTrue(repository.compareAndSetMapEdit(uploading, uploaded(uploading)))
            val snapshot = repository.snapshot()
            if (deleteBeforeResult) assertTrue(snapshot.places.isEmpty())
            else {
                assertEquals(place.copy(osmType = OsmType.NODE, osmId = 123, source = PlaceSource.OSM), snapshot.places.single())
                assertEquals(visit, snapshot.visits.single())
            }
            assertFalse(snapshot.mapEdits.single().publicTags().values.any { it.contains("PRIVATE") })
        }
    }

    @Test fun watchBackupIsOptInAndRestoreRejectsEveryLateUpload() = runBlocking {
        val trip = Journey(id = "watch-trip", transport = Transport.WALK, startedAt = 100)
        repository.saveJourney(trip)
        val session = WatchHealthSession("capture", trip.id, "watch", "Watch8 Classic", 200)
        val sample = WatchHealthSample(session.id, 1, trip.id, HealthMetric.HEART_RATE_BPM, 300, 300, 84.0)
        assertTrue(watch.register(session))
        assertTrue(watch.import("watch", session.id, listOf(sample), 1000))
        assertTrue(watch.import("watch", session.id, listOf(sample), 1000))
        assertFalse(watch.import("other-watch", session.id, listOf(sample), 1000))
        val snapshot = repository.snapshot()
        assertEquals(listOf(sample), snapshot.watchHealthSamples)
        val privateDefault = BackupJson.decode(BackupJson.encode(snapshot))
        assertTrue(privateDefault.watchHealthSamples.isEmpty())
        assertTrue(privateDefault.watchHealthSessions.isEmpty())
        val included = BackupJson.decode(BackupJson.encode(snapshot, includeHealth = true))
        repository.restore(included)
        assertFalse(watch.session(session.id)!!.acceptsUploads)
        assertFalse(watch.import("watch", session.id, listOf(sample.copy(sequence = 2, startAt = 400, endAt = 400)), 1000))
        assertFalse(watch.register(session))
        assertEquals(listOf(sample), repository.snapshot().watchHealthSamples)
    }

    @Test fun healthClearDoesNotResurrectDataFromDelayedWatchBatch() = runBlocking {
        val trip = Journey(id = "watch-trip", transport = Transport.RUN, startedAt = 100)
        repository.saveJourney(trip)
        val session = WatchHealthSession("capture", trip.id, "watch", "Watch8 Classic", 200)
        val sample = WatchHealthSample(session.id, 1, trip.id, HealthMetric.HEART_RATE_BPM, 300, 300, 84.0)
        assertTrue(watch.register(session))
        assertTrue(watch.import("watch", session.id, listOf(sample), 1000))
        repository.clearHealthData()
        assertFalse(watch.import("watch", session.id, listOf(sample), 1000))
        assertTrue(repository.snapshot().watchHealthSamples.isEmpty())
        assertTrue(repository.snapshot().watchHealthSessions.isEmpty())
    }

    @Test fun conflictingSequencesRejectWholeBatchAndPreservePreviouslyStoredReading() = runBlocking {
        val trip = Journey(id = "watch-conflict", transport = Transport.WALK, startedAt = 100)
        repository.saveJourney(trip)
        val session = WatchHealthSession("conflict-capture", trip.id, "watch", "Watch8 Classic", 200)
        val sample = WatchHealthSample(session.id, 1, trip.id, HealthMetric.HEART_RATE_BPM, 300, 300, 84.0)
        assertTrue(watch.register(session))
        assertFalse(watch.import("watch", session.id, listOf(sample, sample.copy(value = 120.0)), 1000))
        assertTrue(repository.snapshot().watchHealthSamples.isEmpty())
        assertTrue(watch.import("watch", session.id, listOf(sample), 1000))
        val newReading = sample.copy(sequence = 2, startAt = 400, endAt = 400)
        assertFalse(watch.import("watch", session.id, listOf(sample.copy(value = 120.0), newReading), 1000))
        assertEquals(listOf(sample), repository.snapshot().watchHealthSamples)
    }

    @Test fun plausibleFutureBatchIsDeferredWithoutPartialWriteThenAcceptedWhenClockCatchesUp() = runBlocking {
        val trip = Journey(id = "watch-future", transport = Transport.WALK, startedAt = 100)
        repository.saveJourney(trip)
        val session = WatchHealthSession("future-capture", trip.id, "watch", "Watch8 Classic", 200)
        val past = WatchHealthSample(session.id, 1, trip.id, HealthMetric.HEART_RATE_BPM, 300, 300, 84.0)
        val future = past.copy(sequence = 2, startAt = 1500, endAt = 1500, value = 90.0)
        val batch = listOf(past, future)
        assertTrue(watch.register(session))
        var pending = false
        try { watch.import("watch", session.id, batch, 1000) }
        catch (_: WatchHealthTimePending) { pending = true }
        assertTrue("A plausible watch clock lead must preserve the batch for later delivery", pending)
        assertTrue("A pending batch must not partially insert its earlier rows", repository.snapshot().watchHealthSamples.isEmpty())
        assertTrue(watch.import("watch", session.id, batch, 2000))
        assertEquals(batch, repository.snapshot().watchHealthSamples)
    }

    @Test fun implausibleFutureBatchIsTerminallyRejectedWithoutAnyWrite() = runBlocking {
        val trip = Journey(id = "watch-gross-future", transport = Transport.RUN, startedAt = 100)
        repository.saveJourney(trip)
        val session = WatchHealthSession("gross-future-capture", trip.id, "watch", "Watch8 Classic", 200)
        val past = WatchHealthSample(session.id, 1, trip.id, HealthMetric.HEART_RATE_BPM, 300, 300, 84.0)
        val futureTime = 1000 + WatchHealthRules.MAX_FUTURE_RETRY_MILLIS + 1
        val future = past.copy(sequence = 2, startAt = futureTime, endAt = futureTime)
        assertTrue(watch.register(session))
        assertFalse(watch.import("watch", session.id, listOf(past, future), 1000))
        assertTrue(repository.snapshot().watchHealthSamples.isEmpty())
    }

    @Test fun uploadedNewPlaceAppendsAfterManualOrderAndRenameKeepsItLast() = runBlocking {
        verifyUploadedPlaceAppends(compactOverflow = false)
    }

    @Test fun uploadedNewPlaceCompactsRankOverflowWithoutChangingRelativeOrder() = runBlocking {
        verifyUploadedPlaceAppends(compactOverflow = true)
    }

    private suspend fun verifyUploadedPlaceAppends(compactOverflow: Boolean) {
        val first = Place(id = "first-order", name = "Zulu")
        val second = Place(id = "second-order", name = "Yankee")
        repository.savePlace(first); repository.savePlace(second)
        repository.reorderPlaces(listOf(first.id, second.id), listOf(second.id, first.id))
        if (compactOverflow) {
            val snapshot = repository.snapshot()
            repository.restore(snapshot.copy(places = snapshot.places.map {
                if (it.id == first.id) it.copy(sortOrder = PlaceOrderRules.MAX_ORDER) else it
            }))
        }
        val before = repository.snapshot().places.map { it.id }
        val pending = draft().copy(name = "A new uploaded place")
        repository.saveMapEdit(pending)
        val sending = requireNotNull(repository.beginMapEditSend(pending.id, 77, 200))
        val uploading = sending.copy(changesetId = 7, stage = MapEditStage.UPLOAD_NODE)
        assertTrue(repository.compareAndSetMapEdit(sending, uploading))
        assertTrue(repository.compareAndSetMapEdit(uploading, uploaded(uploading)))
        val places = repository.snapshot().places
        val created = places.single { it.id !in before }
        assertEquals(before + created.id, places.map { it.id })
        assertTrue(created.sortOrder > places.dropLast(1).maxOf { it.sortOrder })
        assertEquals(OsmType.NODE, created.osmType)
        assertEquals(123L, created.osmId)
        repository.savePlace(created.copy(name = "AAA renamed"))
        assertEquals(before + created.id, repository.snapshot().places.map { it.id })
        assertFalse(repository.compareAndSetMapEdit(uploading, uploaded(uploading)))
        assertEquals(3, repository.snapshot().places.size)
    }
    private fun uploaded(value: MapEditDraft) = value.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
        remoteNodeId = 123, remoteNodeVersion = 1)
}
