package com.atay.iz

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.atay.iz.integration.MediaStoreHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: DiaryRepository
    private val testFiles = mutableListOf<File>()

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = DiaryRepository(context)
        repository.restore(DiarySnapshot())
    }

    @After fun cleanup() = runBlocking {
        repository.restore(DiarySnapshot())
        testFiles.forEach { it.delete() }
    }

    @Test fun concurrentStartsCreateExactlyOneActiveJourney() = runBlocking {
        val journeys = coroutineScope {
            (1..20).map { async { repository.createJourney(Transport.WALK, temporary = true) } }.awaitAll()
        }
        assertEquals(1, journeys.map { it.id }.toSet().size)
        assertEquals(1, repository.snapshot().journeys.size)
        assertEquals(journeys.first().id, repository.activeJourney()?.id)
    }

    @Test fun staleConfirmationCannotChangeAnAlreadyConfirmedTransport() = runBlocking {
        val journey = repository.createJourney(Transport.CAR, temporary = true)
        repository.confirmJourney(journey.id, Transport.CAR)
        repository.confirmJourney(journey.id, Transport.BICYCLE)
        assertEquals(Transport.CAR, repository.getJourney(journey.id)?.transport)
    }

    @Test fun approvalOfFinishedTemporaryJourneyKeepsItsRoute() = runBlocking {
        val journey = repository.createJourney(Transport.UNKNOWN, temporary = true)
        repository.addPoint(point(journey))
        repository.finishJourney(journey.id)
        repository.confirmJourney(journey.id, Transport.MOTORCYCLE)
        repository.rejectJourney(journey.id) // stale reject must be harmless
        val saved = repository.getJourney(journey.id)!!
        assertEquals(JourneyStatus.CONFIRMED, saved.status)
        assertEquals(Transport.MOTORCYCLE, saved.transport)
        assertNotNull(saved.endedAt)
        assertNull(saved.expiresAt)
        assertEquals(1, repository.snapshot().points.size)
    }

    @Test fun savingVisitPromotesTemporaryJourneyAndRepeatVisitRemainsSeparate() = runBlocking {
        val journey = repository.createJourney(Transport.WALK, temporary = true)
        val place = Place(name = "Moda")
        repository.savePlace(place)
        repository.saveVisit(Visit(placeId = place.id, journeyId = journey.id))
        repository.saveVisit(Visit(placeId = place.id))
        repository.cleanupExpired(System.currentTimeMillis() + DiaryRules.TEMPORARY_LIFETIME_MILLIS * 2)
        assertEquals(JourneyStatus.CONFIRMED, repository.getJourney(journey.id)?.status)
        assertEquals(2, repository.snapshot().visits.size)
    }

    @Test fun expiryHidesAndThenDeletesRouteAtBoundary() = runBlocking {
        val journey = repository.createJourney(Transport.WALK, temporary = true)
        repository.addPoint(point(journey))
        val expired = journey.copy(expiresAt = System.currentTimeMillis() - 1)
        repository.saveJourney(expired)
        assertNull(repository.activeJourney())
        assertTrue(repository.journeys.first().isEmpty())
        assertTrue(repository.points.first().isEmpty())
        repository.cleanupExpired()
        assertTrue(repository.snapshot().journeys.isEmpty())
        assertTrue(repository.snapshot().points.isEmpty())
    }

    @Test fun deleteJourneyRetainsExplicitVisitsAndTheirPhotos() = runBlocking {
        val journey = repository.createJourney(Transport.BICYCLE, temporary = false)
        val place = Place(name = "Park")
        val visit = Visit(placeId = place.id, journeyId = journey.id)
        repository.savePlace(place)
        repository.saveVisit(visit)
        val photo = localPhoto(visitId = visit.id, journeyId = journey.id)
        repository.savePhoto(photo)
        repository.saveDraft(ShareDraft(visitId = visit.id, photoIds = listOf(photo.id)))
        repository.addPoint(point(journey))
        repository.deleteJourney(journey.id)
        val snapshot = repository.snapshot()
        assertTrue(snapshot.points.isEmpty())
        assertNull(snapshot.visits.single().journeyId)
        assertNull(snapshot.photos.single().journeyId)
        assertTrue(repository.photoFile(photo.relativePath).isFile)
        repository.deletePlace(place.id)
        assertTrue(repository.snapshot().visits.isEmpty())
        assertTrue(repository.snapshot().photos.isEmpty())
        assertTrue(repository.snapshot().drafts.isEmpty())
        assertFalse(repository.photoFile(photo.relativePath).exists())
    }

    @Test fun deletingPhotoRemovesItsReferenceFromShareDraft() = runBlocking {
        val place = Place(name = "Sahil")
        val visit = Visit(placeId = place.id)
        repository.savePlace(place)
        repository.saveVisit(visit)
        val photo = localPhoto(visitId = visit.id)
        repository.savePhoto(photo)
        repository.saveDraft(ShareDraft(visitId = visit.id, photoIds = listOf(photo.id)))
        repository.deletePhoto(photo.id)
        assertTrue(repository.snapshot().drafts.single().photoIds.isEmpty())
        assertFalse(repository.photoFile(photo.relativePath).exists())
    }

    @Test fun backupRoundTripRestoresPhotosNotesAndClosesOpenJourney() = runBlocking {
        val journey = repository.createJourney(Transport.CAR, temporary = false)
        val place = Place(name = "Türkçe: Şile", latitude = 41.17, longitude = 29.61, googlePlaceId = "example-id")
        val visit = Visit(placeId = place.id, journeyId = journey.id, note = "Özel not", rating = 4)
        repository.savePlace(place)
        repository.saveVisit(visit)
        val photo = localPhoto(visitId = visit.id, journeyId = journey.id)
        repository.savePhoto(photo)
        val draft = ShareDraft(visitId = visit.id, text = "Paylaşılabilir yorum", photoIds = listOf(photo.id))
        repository.saveDraft(draft)
        repository.addPoint(point(journey))
        val originalBytes = repository.photoFile(photo.relativePath).readBytes()
        val zip = tempFile("zip")
        val backup = DiaryBackup(context, repository)
        backup.exportTo(Uri.fromFile(zip))
        repository.restore(DiarySnapshot())
        backup.importFrom(Uri.fromFile(zip))
        val restored = repository.snapshot()
        assertEquals(listOf(place), restored.places)
        assertEquals(listOf(visit), restored.visits)
        assertEquals(listOf(draft), restored.drafts)
        assertEquals(1, restored.points.size)
        assertTrue(restored.journeys.single().interrupted)
        assertNotNull(restored.journeys.single().endedAt)
        assertNull(repository.activeJourney())
        assertArrayEquals(originalBytes, repository.photoFile(restored.photos.single().relativePath).readBytes())
    }

    @Test fun malformedZipCannotReplaceExistingDiaryOrWriteOutsidePrivateStaging() = runBlocking {
        val place = Place(name = "Korunacak yer")
        repository.savePlace(place)
        val zip = tempFile("zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../escaped.txt"))
            it.write("bad".toByteArray())
            it.closeEntry()
        }
        var rejected = false
        try { DiaryBackup(context, repository).importFrom(Uri.fromFile(zip)) }
        catch (error: Exception) { rejected = error is IllegalArgumentException || error is java.util.zip.ZipException }
        assertTrue(rejected)
        assertEquals(listOf(place), repository.snapshot().places)
        assertFalse(File(context.cacheDir, "escaped.txt").exists())
    }

    @Test fun rejectedSnapshotLeavesExistingDiaryUnchanged() = runBlocking {
        val place = Place(name = "Kalıcı")
        repository.savePlace(place)
        var rejected = false
        try { repository.restore(DiarySnapshot(visits = listOf(Visit(placeId = "missing")))) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf(place), repository.snapshot().places)
    }

    @Test fun malformedBackupDoesNotStopTheActiveJourney() = runBlocking {
        val journey = repository.createJourney(Transport.WALK, temporary = false)
        val zip = tempFile("zip")
        zip.writeText("This is not a backup")
        var rejected = false
        try {
            DiaryBackup(context, repository).importFrom(Uri.fromFile(zip)) { repository.finishJourney(journey.id) }
        } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(journey.id, repository.activeJourney()?.id)
        assertNull(repository.getJourney(journey.id)?.endedAt)
    }

    @Test fun photoImportPreservesExifCaptureTimeAndDoesNotInventMissingLocation() = runBlocking {
        val source = tempFile("jpg")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:06 14:30:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+03:00")
            saveAttributes()
        }
        val journey = repository.createJourney(Transport.WALK, temporary = false)
        val photo = MediaStoreHelper(context).importPhoto(Uri.fromFile(source), journeyId = journey.id)
        repository.savePhoto(photo)
        assertEquals(java.time.Instant.parse("2026-09-06T11:30:00Z").toEpochMilli(), photo.takenAt)
        assertNull(photo.latitude)
        assertNull(photo.longitude)
    }

    @Test fun replacementFailureKeepsOriginalJourneyActive() = runBlocking {
        val journey = repository.createJourney(Transport.CAR, temporary = false)
        val archive = tempFile("zip")
        val backup = DiaryBackup(context, repository)
        backup.exportTo(Uri.fromFile(archive))
        val sql = context.openOrCreateDatabase("iz-diary.db", Context.MODE_PRIVATE, null)
        sql.execSQL("CREATE TRIGGER test_restore_failure BEFORE DELETE ON journeys BEGIN SELECT RAISE(ABORT, 'test replacement failure'); END")
        var failed = false
        try {
            backup.importFrom(Uri.fromFile(archive)) { repository.finishJourney(journey.id) }
        } catch (_: Exception) { failed = true }
        finally { sql.execSQL("DROP TRIGGER test_restore_failure"); sql.close() }
        assertTrue(failed)
        assertEquals(journey.id, repository.activeJourney()?.id)
        assertNull(repository.getJourney(journey.id)?.endedAt)
    }

    private fun point(journey: Journey) = TrackPoint(journeyId = journey.id, latitude = 41.0, longitude = 29.0, accuracy = 5f,
        recordedAt = journey.startedAt)

    private fun localPhoto(visitId: String? = null, journeyId: String? = null): Photo {
        val photo = Photo(relativePath = "photos/${UUID.randomUUID()}.jpg", visitId = visitId, journeyId = journeyId)
        repository.photoFile(photo.relativePath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        return photo
    }

    private fun tempFile(extension: String) = File(context.cacheDir, "test-${UUID.randomUUID()}.$extension").also { testFiles += it }
}
