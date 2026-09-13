package org.iz.navigation.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelLibraryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DiaryDatabase
    private lateinit var repository: TravelLibraryRepository
    private lateinit var diary: DiaryRepository
    private val a = WeatherCoordinate(10.0, 20.0)
    private val b = WeatherCoordinate(10.001, 20.001)
    private val plan = SavedRoutePlan(id = "plan", name = "Walk", stops = listOf(RouteStop("A", a), RouteStop("B", b)), transport = Transport.WALK, createdAt = 10)
    private val track = ImportedTrack(id = "track", name = "Trail", createdAt = 10, segments = listOf(TrackSegment("Section", listOf(a, b), "Group", 0)))
    private val trip = Journey(id = "trip", startedAt = 1000, endedAt = 5000)
    private val collection = JourneyCollection(id = "collection", name = "Summer", createdAt = 10)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        repository = TravelLibraryRepository(db)
        diary = DiaryRepository(context, db)
    }
    @After fun close() { db.close() }

    @Test fun plansAndTracksPersistWithoutCreatingDiaryJourneysAndRenameKeepsIdentity() = runBlocking {
        repository.savePlan(plan)
        repository.saveTrack(track)
        val savedPlan = repository.savedPlans.first().single()
        assertEquals(5.1, savedPlan.travelSpeedKmh!!, 0.0)
        repository.savePlan(savedPlan.copy(name = "Renamed plan", updatedAt = 20))
        repository.saveTrack(track.copy(name = "Renamed track"))
        assertEquals("plan", repository.savedPlans.first().single().id)
        assertEquals("Renamed plan", repository.savedPlans.first().single().name)
        assertEquals(track.copy(name = "Renamed track"), repository.importedTracks.first().single())
        assertTrue(diary.snapshot().journeys.isEmpty())
        repository.deletePlan(plan.id); repository.deleteTrack(track.id)
        assertTrue(repository.savedPlans.first().isEmpty()); assertTrue(repository.importedTracks.first().isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM imported_track_points").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun largestAllowedSingleSegmentLoadsWithoutACursorWindowSizedRow() = runBlocking {
        val points = List(100_000) { WeatherCoordinate(10.0 + it * 0.000001, 20.0 + it * 0.000001) }
        val large = track.copy(segments = listOf(track.segments.single().copy(points = points)))
        repository.saveTrack(large)
        val loaded = repository.importedTracks.first().single()
        assertEquals(100_000, loaded.segments.single().points.size)
        assertEquals(points.first(), loaded.segments.single().points.first())
        assertEquals(points.last(), loaded.segments.single().points.last())
        assertEquals(large, loaded)
    }

    @Test fun orderedMembershipsAreAtomicManyToManyAndDeleteCascadesOnlyLinks() = runBlocking {
        val other = trip.copy(id = "other")
        val secondCollection = collection.copy(id = "second")
        diary.saveJourney(trip); diary.saveJourney(other)
        repository.saveCollection(collection); repository.saveCollection(secondCollection)
        repository.setCollectionJourneys(collection.id, listOf(other.id, trip.id))
        repository.setCollectionJourneys(secondCollection.id, listOf(trip.id))
        assertEquals(listOf(other.id, trip.id), repository.memberships.first().filter { it.collectionId == collection.id }.map { it.journeyId })
        val before = repository.memberships.first()
        for (bad in listOf(listOf(trip.id, trip.id), listOf(trip.id, "missing"))) {
            assertNotNull(runCatching { repository.setCollectionJourneys(collection.id, bad) }.exceptionOrNull())
            assertEquals(before, repository.memberships.first())
        }
        repository.deleteCollection(collection.id)
        assertEquals(setOf(trip.id, other.id), diary.snapshot().journeys.map { it.id }.toSet())
        assertEquals(secondCollection.id, repository.memberships.first().single().collectionId)
        diary.deleteJourney(trip.id)
        assertTrue(repository.memberships.first().isEmpty())
        assertEquals(secondCollection.id, repository.collections.first().single().id)
    }

    @Test fun ineligibleJourneysAreRejectedAndReopeningRemovesMembership() = runBlocking {
        repository.saveCollection(collection)
        val candidates = listOf(trip.copy(id = "active", endedAt = null), trip.copy(id = "temporary", status = JourneyStatus.TEMPORARY), trip.copy(id = "expires", expiresAt = 9999))
        for (candidate in candidates) {
            diary.saveJourney(candidate)
            assertNotNull(runCatching { repository.setCollectionJourneys(collection.id, listOf(candidate.id)) }.exceptionOrNull())
        }
        diary.finishJourney("active")
        diary.saveJourney(trip)
        repository.setCollectionJourneys(collection.id, listOf(trip.id))
        diary.saveJourney(trip.copy(endedAt = null))
        assertTrue(repository.memberships.first().isEmpty())
    }

    @Test fun snapshotRoundTripRestoresAllNewListsAndEmptyRestoreClearsThem() = runBlocking {
        diary.saveJourney(trip)
        repository.savePlan(plan); repository.saveTrack(track); repository.saveCollection(collection)
        repository.setCollectionJourneys(collection.id, listOf(trip.id))
        val before = diary.snapshot()
        diary.restore(DiarySnapshot())
        assertTrue(repository.savedPlans.first().isEmpty()); assertTrue(repository.importedTracks.first().isEmpty())
        assertTrue(repository.collections.first().isEmpty()); assertTrue(repository.memberships.first().isEmpty())
        diary.restore(before)
        assertEquals(before, diary.snapshot())
    }

    @Test fun failedRestoreRollsBackOldLibraryAndDiaryInTheSameTransaction() = runBlocking {
        diary.saveJourney(trip); repository.saveTrack(track); repository.saveCollection(collection)
        repository.setCollectionJourneys(collection.id, listOf(trip.id))
        val before = diary.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_imported_track BEFORE INSERT ON imported_tracks BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertNotNull(runCatching { diary.restore(before.copy(importedTracks = listOf(track.copy(id = "new")))) }.exceptionOrNull())
        assertEquals(before, diary.snapshot())
        assertNotNull(runCatching { diary.restore(before.copy(memberships = listOf(CollectionMembership(collection.id, "unknown", 0)))) }.exceptionOrNull())
        assertEquals(before, diary.snapshot())
    }
}
