package org.iz.navigation.data

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.iz.navigation.moto.requireMotorcycleQaTarget
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.garage.JourneyVehicle
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GaragePersistenceAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val databaseName = "garage-migration-${UUID.randomUUID()}.db"

    @get:Rule val migration = MigrationTestHelper(instrumentation, DiaryDatabase::class.java)

    @Before fun requireIsolatedQa() { requireMotorcycleQaTarget() }

    @Test fun roomElevenMigratesTheDiaryWithoutInventingVehiclesOrAssignments() = runBlocking<Unit>(Dispatchers.IO) {
        try {
            migration.createDatabase(databaseName, 11).use { old ->
                old.execSQL(
                    "INSERT INTO journeys (id,title,transport,status,startedAt,endedAt,note,interrupted) " +
                        "VALUES ('qa-old-moto','Eski motosiklet','MOTORCYCLE','CONFIRMED',1000,5000,'Sentetik not',0)"
                )
                old.execSQL(
                    "INSERT INTO track_points (id,journeyId,latitude,longitude,recordedAt,accuracy,speed,breakBefore) " +
                        "VALUES ('qa-old-fix','qa-old-moto',0.27,-140,2000,4.0,12.0,1)"
                )
            }
            migration.runMigrationsAndValidate(databaseName, 12, true, DiaryDatabase.MIGRATION_11_12).close()
            val migrated: DiaryDatabase = Room.databaseBuilder(context, DiaryDatabase::class.java, databaseName).addMigrations(DiaryDatabase.MIGRATION_12_13, DiaryDatabase.MIGRATION_13_14).build()
            try {
                assertOldDiaryAndEmptyGarage(DiaryRepository(context, migrated).snapshot())
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun existingVersionTwelveZipRestoresAnUnassignedMotorcycleWithoutAnyGarages() = runBlocking<Unit>(Dispatchers.IO) {
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        val archive = File.createTempFile("garage-v12-", ".zip", directory)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(oldManifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val db: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
            try {
                val repository = DiaryRepository(context, db)
                val input = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
                DiaryBackup(context, repository).importFrom(input)
                assertOldDiaryAndEmptyGarage(repository.snapshot())
            } finally {
                db.close()
            }
        } finally {
            archive.delete()
        }
    }

    @Test fun versionThirteenRoundTripsTwoVehiclesPricesThresholdsCounterAndAssignment() = runBlocking<Unit>(Dispatchers.IO) {
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        val archive = File.createTempFile("garage-v13-", ".zip", directory)
        val moto = GarageVehicle("qa-moto", "QA Moto", Transport.MOTORCYCLE, 12.0, 27.0, 43.0, 1800.0, 1500L)
        val car = GarageVehicle("qa-car", "QA Auto", Transport.CAR, 55.0, 31.0, 65.0, 7000.0, 2200L)
        val ride = Journey(id = "qa-ride", title = "QA archived", transport = Transport.MOTORCYCLE, startedAt = 4_000L, endedAt = 6_000L)
        val fills = listOf(
            FuelFill("qa-a", moto.id, 2000L, 1810.0, 5.0, 50.0, full = false),
            FuelFill("qa-b", car.id, 3000L, 7010.0, 40.0, 51.5),
            FuelFill("qa-c", moto.id, 7000L, 1830.0, 7.0, 52.5, missedBefore = true),
        )
        val expected = DiarySnapshot(journeys = listOf(ride), garageVehicles = listOf(car, moto),
            fuelFills = fills, journeyVehicles = listOf(JourneyVehicle(ride.id, moto.id)))
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
            val source: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
            try {
                val repository = DiaryRepository(context, source)
                repository.restore(expected)
                DiaryBackup(context, repository).exportTo(uri)
            } finally {
                source.close()
            }
            ZipFile(archive).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertEquals(13, manifest.getInt("version"))
                assertEquals(2, manifest.getJSONArray("garageVehicles").length())
                assertEquals(3, manifest.getJSONArray("fuelFills").length())
                assertEquals(1, manifest.getJSONArray("journeyVehicles").length())
            }
            val target: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
            try {
                val repository = DiaryRepository(context, target)
                DiaryBackup(context, repository).importFrom(uri)
                assertEquals(expected, repository.snapshot())
            } finally {
                target.close()
            }
        } finally {
            archive.delete()
        }
    }

    private fun assertOldDiaryAndEmptyGarage(snapshot: DiarySnapshot) {
        assertEquals(
            Journey(
                id = "qa-old-moto", title = "Eski motosiklet", transport = Transport.MOTORCYCLE,
                startedAt = 1_000L, endedAt = 5_000L, note = "Sentetik not",
            ),
            snapshot.journeys.single(),
        )
        assertEquals(
            TrackPoint("qa-old-fix", "qa-old-moto", 0.27, -140.0, 2_000L, 4f, 12f, breakBefore = true),
            snapshot.points.single(),
        )
        assertTrue(snapshot.leanSessions.isEmpty())
        assertTrue(snapshot.garageVehicles.isEmpty())
        assertTrue(snapshot.fuelFills.isEmpty())
        assertTrue(snapshot.journeyVehicles.isEmpty())
    }

    companion object {
        private val oldManifest = """
            {
              "format":"org.iz.navigation.backup","version":12,"exportedAt":6000,
              "journeys":[{"id":"qa-old-moto","title":"Eski motosiklet","transport":"MOTORCYCLE",
                "status":"CONFIRMED","startedAt":1000,"endedAt":5000,"expiresAt":null,"stepCount":null,"note":"Sentetik not","interrupted":false}],
              "points":[{"id":"qa-old-fix","journeyId":"qa-old-moto","latitude":0.27,"longitude":-140.0,
                "recordedAt":2000,"accuracy":4,"speed":12,"altitude":null,"breakBefore":true}],
              "places":[],"visits":[],"photos":[],"drafts":[],"contributions":[],"mapEdits":[],
              "healthSamples":[],"watchHealthSessions":[],"watchHealthSamples":[],"savedPlans":[],
              "importedTracks":[],"collections":[],"memberships":[],"destinationShortcuts":[],
              "leanSessions":[],"osmPresetFavorites":[]
            }
        """.trimIndent()
    }
}
