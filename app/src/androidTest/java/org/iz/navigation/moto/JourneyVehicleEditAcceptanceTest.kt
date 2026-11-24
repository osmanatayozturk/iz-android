package org.iz.navigation.moto

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.garage.JourneyVehicle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyVehicleEditAcceptanceTest {
    @Test fun journeyEditorAndDirectSaveClearOnlyTheChangedVehicleAssignment() = runBlocking<Unit>(Dispatchers.IO) {
        val app = requireMotorcycleQaTarget().applicationContext as IzApplication
        val db = DiaryDatabase.get(app)
        val diary = DiaryRepository(app)
        val garage = app.garage
        val seed = GarageTrips()
        val edited = seed.edited
        val dao = db.diaryDao()
        val now = System.currentTimeMillis()
        val temporary = Journey(title = "Pending ride", transport = Transport.MOTORCYCLE,
            status = JourneyStatus.TEMPORARY, startedAt = now - 2_000L, endedAt = now - 1_000L,
            expiresAt = now + 60_000L)
        suspend fun odometer(id: String) = withTimeout(4_000) { garage.stats(id).first().odometerKm }
        suspend fun assertDetached() {
            assertEquals(0, rawLinkCount(db, edited.id))
            assertNull(garage.assignedVehicleId(edited.id).first())
            assertEquals(500.01112, odometer(seed.bike.id), .00015)
            assertEquals(700.0, odometer(seed.car.id), .000001)
        }

        try {
            seed.insert(db)
            garage.assignJourney(edited.id, seed.bike.id)
            assertEquals(1, rawLinkCount(db, edited.id))
            // Two synthetic routes: about 11.12 m each, with half of the edited one before the counter baseline.
            assertEquals(500.01668, odometer(seed.bike.id), .00015)

            assertTrue(diary.updateJourneyDetails(edited.id, "Renamed ride", "Edited note", edited.transport))
            val renamed = edited.copy(title = "Renamed ride", note = "Edited note")
            assertEquals(renamed, dao.journey(edited.id))
            assertEquals(seed.bike.id, garage.assignedVehicleId(edited.id).first())
            assertEquals(500.01668, odometer(seed.bike.id), .00015)

            assertTrue(diary.updateJourneyDetails(edited.id, renamed.title, renamed.note, Transport.CAR))
            val changed = renamed.copy(transport = Transport.CAR)
            assertEquals(changed, dao.journey(edited.id))
            assertDetached()

            diary.saveJourney(renamed)
            assertDetached()
            garage.assignJourney(edited.id, seed.bike.id)
            diary.saveJourney(renamed.copy(title = "Direct save"))
            assertEquals(seed.bike.id, garage.assignedVehicleId(edited.id).first())
            assertEquals(500.01668, odometer(seed.bike.id), .00015)
            diary.saveJourney(changed.copy(title = "Direct save"))
            assertEquals(changed.copy(title = "Direct save"), dao.journey(edited.id))
            assertDetached()

            // The previous release could leave this incompatible row in the same SQLite table.
            db.garageDao().save(JourneyVehicle(edited.id, seed.bike.id))
            assertEquals(1, rawLinkCount(db, edited.id))
            assertNull(garage.assignedVehicleId(edited.id).first())
            assertEquals(500.01112, odometer(seed.bike.id), .00015)

            dao.save(temporary)
            db.garageDao().save(JourneyVehicle(temporary.id, seed.bike.id))
            assertEquals(1, rawLinkCount(db, temporary.id))
            assertNull(garage.assignedVehicleId(temporary.id).first())
            diary.confirmJourney(temporary.id, temporary.transport)
            assertEquals(JourneyStatus.CONFIRMED, dao.journey(temporary.id)?.status)
            assertEquals(0, rawLinkCount(db, temporary.id))
            assertNull(garage.assignedVehicleId(temporary.id).first())

            assertEquals(seed.retained, dao.journey(seed.retained.id))
            assertEquals(seed.bike.id, garage.assignedVehicleId(seed.retained.id).first())
            for (journey in listOf(edited, seed.retained)) {
                assertEquals(seed.points.filter { it.journeyId == journey.id }, diary.journeyPoints(journey.id))
            }
            assertEquals(listOf(seed.fill), garage.fills(seed.bike.id).first())
        } finally {
            dao.deleteJourney(temporary.id)
            dao.deleteJourney(edited.id)
            dao.deleteJourney(seed.retained.id)
            garage.deleteVehicle(seed.bike.id)
            garage.deleteVehicle(seed.car.id)
        }
    }

    @Test fun legacyIncompatibleAssignmentIsOmittedFromExportAndBadImportIsAtomic() = runBlocking<Unit>(Dispatchers.IO) {
        val context = requireMotorcycleQaTarget()
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        val archive = File.createTempFile("garage-legacy-link-", ".zip", directory)
        val source: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        val target: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val seed = GarageTrips()
            seed.insert(source)
            val oldLink = JourneyVehicle(seed.edited.id, seed.bike.id)
            source.garageDao().save(oldLink)
            assertEquals(1, rawLinkCount(source, seed.edited.id))

            val sourceDiary = DiaryRepository(context, source)
            val edited = seed.edited.copy(title = "Edited before export", transport = Transport.CAR)
            assertTrue(sourceDiary.updateJourneyDetails(edited.id, edited.title, edited.note, edited.transport))
            assertEquals(0, rawLinkCount(source, edited.id))
            source.garageDao().save(oldLink)
            assertEquals(1, rawLinkCount(source, edited.id))

            val expected = sourceDiary.snapshot()
            assertEquals(setOf(edited, seed.retained), expected.journeys.toSet())
            assertEquals(seed.points.toSet(), expected.points.toSet())
            assertEquals(setOf(seed.bike, seed.car), expected.garageVehicles.toSet())
            assertEquals(listOf(seed.fill), expected.fuelFills)
            assertEquals(listOf(seed.validLink), expected.journeyVehicles)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
            DiaryBackup(context, sourceDiary).exportTo(uri)

            val manifest = ZipFile(archive).use { zip ->
                JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            }
            assertEquals(13, manifest.getInt("version"))
            val links = manifest.getJSONArray("journeyVehicles")
            assertEquals(1, links.length())
            assertEquals(seed.retained.id, links.getJSONObject(0).getString("journeyId"))
            assertEquals(seed.bike.id, links.getJSONObject(0).getString("vehicleId"))

            val targetDiary = DiaryRepository(context, target)
            val backup = DiaryBackup(context, targetDiary)
            backup.importFrom(uri)
            assertEquals(expected, targetDiary.snapshot())
            assertEquals(0, rawLinkCount(target, seed.edited.id))
            assertEquals(1, rawLinkCount(target, seed.retained.id))

            links.put(JSONObject().put("journeyId", seed.edited.id).put("vehicleId", seed.bike.id))
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            var replaced = false
            try {
                backup.importFrom(uri) { replaced = true }
                throw AssertionError("An external ZIP 13 with incompatible vehicle transport was accepted")
            } catch (_: IllegalArgumentException) {
                assertEquals(false, replaced)
                assertEquals(expected, targetDiary.snapshot())
                assertEquals(0, rawLinkCount(target, seed.edited.id))
            }
        } finally {
            target.close()
            source.close()
            archive.delete()
        }
    }

    private fun rawLinkCount(db: DiaryDatabase, journeyId: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM journey_vehicles WHERE journeyId = ?",
            arrayOf(journeyId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private class GarageTrips {
        val bike = GarageVehicle(name = "QA bike", transport = Transport.MOTORCYCLE,
            capacityLitres = 12.0, odometerKm = 500.0, odometerAt = 2_000L)
        val car = GarageVehicle(name = "QA car", transport = Transport.CAR,
            capacityLitres = 55.0, odometerKm = 700.0, odometerAt = 2_000L)
        val fill = FuelFill(vehicleId = bike.id, at = 250L, odometerKm = 495.0, litres = 8.5, pricePerLitre = 50.2)
        val edited = Journey(title = "Edited ride", transport = Transport.MOTORCYCLE, startedAt = 500L, endedAt = 4_000L)
        val retained = Journey(title = "Retained ride", transport = Transport.MOTORCYCLE, startedAt = 5_000L, endedAt = 8_000L)
        val validLink = JourneyVehicle(retained.id, bike.id)
        val points = listOf(edited, retained).flatMap { trip ->
            (1..2).map { index -> TrackPoint(journeyId = trip.id, latitude = 0.27,
                longitude = -140.0 + index * .0001, recordedAt = trip.startedAt + index * 1_000L,
                accuracy = 2f, speed = 11f) }
        }

        suspend fun insert(db: DiaryDatabase) {
            val garage = db.garageDao()
            val diary = db.diaryDao()
            garage.save(bike)
            garage.save(car)
            garage.save(fill)
            diary.save(edited)
            diary.save(retained)
            for (point in points) diary.save(point)
            garage.save(validLink)
        }
    }
}
