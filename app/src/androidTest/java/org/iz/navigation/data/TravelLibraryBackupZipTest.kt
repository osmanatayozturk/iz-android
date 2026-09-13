package org.iz.navigation.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Own in-memory database and UUID fixtures; never clears the device's diary. */
@RunWith(AndroidJUnit4::class)
class TravelLibraryBackupZipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DiaryDatabase
    private lateinit var diary: DiaryRepository
    private lateinit var backup: DiaryBackup
    private val archives = mutableListOf<File>()
    private val photoBytes = byteArrayOf(1, 7, 3, 9)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        diary = DiaryRepository(context, db)
        backup = DiaryBackup(context, diary)
    }
    @After fun cleanup() = runBlocking {
        diary.restore(DiarySnapshot())
        db.close()
        archives.forEach { it.delete() }
    }

    @Test fun manualZipSevenRoundTripsLibraryAndOriginalPhotoBytesWithoutHealthByDefault() = runBlocking {
        seed()
        val before = diary.snapshot()
        val archive = archive()
        backup.exportTo(uri(archive))
        ZipFile(archive).use { zip ->
            val json = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            assertEquals(7, json.getInt("version"))
            listOf("savedPlans", "importedTracks", "collections", "memberships").forEach { assertEquals(1, json.getJSONArray(it).length()) }
            listOf("healthSamples", "watchHealthSessions", "watchHealthSamples").forEach { assertEquals(0, json.getJSONArray(it).length()) }
            assertArrayEquals(photoBytes, zip.getInputStream(zip.getEntry(before.photos.single().relativePath)).use { it.readBytes() })
        }
        backup.importFrom(uri(archive))
        val restored = diary.snapshot()
        assertNotEquals(before.photos.single().relativePath, restored.photos.single().relativePath)
        assertEquals(before, restored.copy(photos = restored.photos.map { it.copy(relativePath = before.photos.single().relativePath) }))
        assertArrayEquals(photoBytes, diary.photoFile(restored.photos.single().relativePath).readBytes())
        assertNull(diary.activeJourney())
    }

    @Test fun brokenLibraryReferenceDoesNotReplaceOldDatabaseOrPhotos() = runBlocking {
        seed()
        val before = diary.snapshot()
        val malformed = BackupJson.encode(before).apply {
            getJSONArray("memberships").getJSONObject(0).put("journeyId", "missing")
        }
        val archive = archive()
        writeZip(archive, malformed, before.photos.single().relativePath)
        assertNotNull(runCatching { backup.importFrom(uri(archive)) }.exceptionOrNull())
        assertEquals(before, diary.snapshot())
        assertArrayEquals(photoBytes, diary.photoFile(before.photos.single().relativePath).readBytes())
    }

    @Test fun everyOldZipWithoutLibraryRestoresEmptyListsAndNeverStartsARecording() = runBlocking {
        for (version in 1..6) {
            seed()
            val open = Journey(id = "open", startedAt = 10, endedAt = null)
            val json = BackupJson.encode(DiarySnapshot(journeys = listOf(open))).put("version", version)
            listOf("savedPlans", "importedTracks", "collections", "memberships").forEach { json.remove(it) }
            val archive = archive()
            writeZip(archive, json)
            backup.importFrom(uri(archive))
            val snapshot = diary.snapshot()
            assertEquals("open", snapshot.journeys.single().id)
            assertNotNull(snapshot.journeys.single().endedAt)
            assertTrue(snapshot.journeys.single().interrupted)
            assertNull(diary.activeJourney())
            assertTrue(snapshot.savedPlans.isEmpty()); assertTrue(snapshot.importedTracks.isEmpty())
            assertTrue(snapshot.collections.isEmpty()); assertTrue(snapshot.memberships.isEmpty())
        }
    }

    private suspend fun seed() {
        diary.restore(DiarySnapshot())
        val trip = Journey(id = "trip", startedAt = 1000, endedAt = 5000)
        val a = WeatherCoordinate(10.0, 20.0)
        val b = WeatherCoordinate(10.001, 20.001)
        val path = "photos/library-zip-${UUID.randomUUID()}.jpg"
        diary.photoFile(path).apply { parentFile!!.mkdirs(); writeBytes(photoBytes) }
        diary.restore(DiarySnapshot(journeys = listOf(trip), photos = listOf(Photo(id = "photo", journeyId = trip.id, relativePath = path)),
            savedPlans = listOf(SavedRoutePlan(id = "plan", name = "Plan", stops = listOf(RouteStop("A", a), RouteStop("B", b)), transport = Transport.WALK)),
            importedTracks = listOf(ImportedTrack(id = "track", name = "Trail", segments = listOf(TrackSegment("", listOf(a, b), "Track", 0)))),
            collections = listOf(JourneyCollection(id = "collection", name = "Collection")), memberships = listOf(CollectionMembership("collection", trip.id, 0))))
    }
    private fun archive(): File {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(folder, "library-${UUID.randomUUID()}.zip").also { archives += it }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun writeZip(file: File, json: JSONObject, photoPath: String? = null) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(json.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            photoPath?.let { zip.putNextEntry(ZipEntry(it)); zip.write(photoBytes); zip.closeEntry() }
        }
    }
}
