package org.iz.navigation.moto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.garage.JourneyVehicle
import org.iz.navigation.tracking.TrackingPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarageNewRideAcceptanceTest {
    @Test
    fun confirmedStartUsesExactlyOneMatchingVehicleAndNeverAssignsOldOrImportedJourneys() {
        runBlocking(Dispatchers.IO) {
            withGarageRoom(requireGarageRideQa(physical = false)) { db, diary ->
                val garage = db.garageDao()
                val old = historical("imported-old", Transport.MOTORCYCLE)
                val importedOpen = historical("imported-open", Transport.MOTORCYCLE).copy(endedAt = null)
                val car = vehicle("car", Transport.CAR)
                val bike = vehicle("bike")
                diary.restore(DiarySnapshot(journeys = listOf(old, importedOpen), garageVehicles = listOf(bike, car)))
                assertEquals(old, diary.getJourney(old.id))
                assertTrue(requireNotNull(diary.getJourney(importedOpen.id)).endedAt != null)
                assertTrue(garage.allAssignments().isEmpty())

                val bikeRide = diary.createJourney(Transport.MOTORCYCLE, temporary = false)
                assertEquals(bikeRide.id, diary.createJourney(Transport.MOTORCYCLE, temporary = false).id)
                assertEquals(listOf(JourneyVehicle(bikeRide.id, bike.id)), garage.allAssignments())
                diary.finishJourney(bikeRide.id)
                val carRide = diary.createJourney(Transport.CAR, temporary = false)
                assertEquals(car.id, garage.assignment(carRide.id).first())
                diary.finishJourney(carRide.id)

                garage.save(vehicle("second-bike"))
                val noChoice = diary.createJourney(Transport.MOTORCYCLE, temporary = false)
                assertNull(garage.assignment(noChoice.id).first())
                garage.deleteVehicle("second-bike")
                assertEquals(noChoice.id, diary.createJourney(Transport.MOTORCYCLE, temporary = false).id)
                assertNull(garage.assignment(noChoice.id).first())
                diary.finishJourney(noChoice.id)
                val walkRide = diary.createJourney(Transport.WALK, temporary = false)
                assertNull(garage.assignment(walkRide.id).first())
                diary.finishJourney(walkRide.id)
                garage.deleteVehicle(car.id)
                val noCar = diary.createJourney(Transport.CAR, temporary = false)
                assertNull(garage.assignment(noCar.id).first())
                assertNull(garage.assignment(old.id).first())
                assertNull(garage.assignment(importedOpen.id).first())
            }
        }
    }

    @Test
    fun temporaryConfirmationUsesStartChoicesAndPublishesOneAssignmentIntoDistanceAndZip() {
        runBlocking(Dispatchers.IO) {
            val context = requireGarageRideQa(physical = false)
            withGarageRoom(context) { db, diary ->
                val garage = db.garageDao()
                val car = vehicle("first-car", Transport.CAR)
                val bike = vehicle("start-bike")
                garage.save(car)
                garage.save(bike)
                val older = historical("non-linked-older", Transport.MOTORCYCLE)
                diary.saveJourney(older)
                val candidate = diary.createJourney(Transport.CAR, temporary = true,
                    now = System.currentTimeMillis() - 180_000L)
                addSyntheticPoints(diary, candidate, count = 3)
                garage.save(vehicle("late-bike"))
                assertNull(garage.assignment(candidate.id).first())
                assertEquals(mapOf("CAR" to car.id, "MOTORCYCLE" to bike.id), startChoices(db, candidate.id))
                assertEquals(0, durableAssignmentCount(db, candidate.id))
                assertTrue(garage.points(bike.id, 0).first().isEmpty())
                assertTrue(diary.snapshot().journeyVehicles.isEmpty())
                inspectBackup(context, diary) { manifest ->
                    assertEquals(13, manifest.getInt("version"))
                    assertEquals(0, manifest.getJSONArray("journeyVehicles").length())
                    assertEquals(0, manifest.getJSONArray("points").length())
                    val exported = manifest.getJSONArray("journeys")
                    assertEquals(1, exported.length())
                    assertEquals(older.id, exported.getJSONObject(0).getString("id"))
                    assertTrue(manifest.keys().asSequence().none { key -> key.contains("candidate", ignoreCase = true) })
                }

                diary.confirmJourney(candidate.id, Transport.MOTORCYCLE)
                diary.confirmJourney(candidate.id, Transport.CAR)
                assertEquals(Transport.MOTORCYCLE, diary.getJourney(candidate.id)?.transport)
                assertEquals(JourneyStatus.CONFIRMED, diary.getJourney(candidate.id)?.status)
                assertEquals(listOf(JourneyVehicle(candidate.id, bike.id)), garage.allAssignments())
                assertTrue(startChoices(db, candidate.id).isEmpty())
                assertEquals(3, garage.points(bike.id, 0).first().size)
                assertTrue(garage.points(car.id, 0).first().isEmpty())
                inspectBackup(context, diary) { manifest ->
                    val exported = manifest.getJSONArray("journeyVehicles")
                    assertEquals(1, exported.length())
                    assertEquals(candidate.id, exported.getJSONObject(0).getString("journeyId"))
                    assertEquals(bike.id, exported.getJSONObject(0).getString("vehicleId"))
                }
                diary.finishJourney(candidate.id)

                val ambiguousAtStart = diary.createJourney(Transport.MOTORCYCLE, temporary = true)
                garage.deleteVehicle("late-bike")
                diary.confirmJourney(ambiguousAtStart.id, Transport.MOTORCYCLE)
                assertNull(garage.assignment(ambiguousAtStart.id).first())
                assertTrue(startChoices(db, ambiguousAtStart.id).isEmpty())
                diary.finishJourney(ambiguousAtStart.id)

                garage.deleteVehicle(bike.id)
                val emptyAtStart = diary.createJourney(Transport.MOTORCYCLE, temporary = true)
                garage.save(vehicle("new-bike"))
                diary.confirmJourney(emptyAtStart.id, Transport.MOTORCYCLE)
                assertNull(garage.assignment(emptyAtStart.id).first())
                diary.finishJourney(emptyAtStart.id)

                val deletedAtStart = diary.createJourney(Transport.MOTORCYCLE, temporary = true)
                garage.deleteVehicle("new-bike")
                garage.save(vehicle("replacement-bike"))
                diary.confirmJourney(deletedAtStart.id, Transport.MOTORCYCLE)
                assertNull(garage.assignment(deletedAtStart.id).first())
            }
        }
    }

    @Test
    fun automaticDistanceConfirmationAndResetEachUseTheirOwnRecordingStart() {
        runBlocking(Dispatchers.IO) {
            withGarageRoom(requireGarageRideQa(physical = false)) { db, diary ->
                val garage = db.garageDao()
                val original = vehicle("auto-original", Transport.CAR)
                garage.save(original)
                val through = diary.createJourney(Transport.CAR, temporary = true,
                    now = System.currentTimeMillis() - 180_000L)
                addSyntheticPoints(diary, through, count = 7)
                assertEquals(JourneyStatus.CONFIRMED,
                    diary.resolveAutomaticCandidate(through.id, through.startedAt + 66_000L)?.status)
                assertEquals(original.id, garage.assignment(through.id).first())
                assertEquals(through.id, diary.resolveAutomaticCandidate(through.id, through.startedAt + 70_000L)?.id)
                assertEquals(listOf(JourneyVehicle(through.id, original.id)), garage.allAssignments())
                diary.finishJourney(through.id)

                val expired = diary.createJourney(Transport.CAR, temporary = true,
                    now = System.currentTimeMillis() - TrackingPolicy.AUTOMATIC_WINDOW_MS - 180_000L)
                assertNull(garage.assignment(expired.id).first())
                garage.deleteVehicle(original.id)
                val fresh = vehicle("auto-replacement", Transport.CAR)
                garage.save(fresh)
                val restartedAt = expired.startedAt + TrackingPolicy.AUTOMATIC_WINDOW_MS
                val restarted = requireNotNull(diary.resolveAutomaticCandidate(expired.id, restartedAt))
                assertNotEquals(expired.id, restarted.id)
                assertEquals(restartedAt, restarted.startedAt)
                assertNull(diary.getJourney(expired.id))
                assertTrue(startChoices(db, expired.id).isEmpty())
                assertEquals(mapOf("CAR" to fresh.id), startChoices(db, restarted.id))
                assertNull(garage.assignment(restarted.id).first())
                addSyntheticPoints(diary, restarted, count = 7)
                assertEquals(JourneyStatus.CONFIRMED,
                    diary.resolveAutomaticCandidate(restarted.id, restartedAt + 66_000L)?.status)
                assertEquals(fresh.id, garage.assignment(restarted.id).first())
                assertEquals(listOf(JourneyVehicle(restarted.id, fresh.id)), garage.allAssignments())
                assertTrue(startChoices(db, restarted.id).isEmpty())
            }
        }
    }

    @Test
    fun manualSelectionWinsAndChangingTypeOrDeletingNeverCreatesAnotherAssignment() {
        runBlocking(Dispatchers.IO) {
            val context = requireGarageRideQa(physical = false)
            val app = context.applicationContext as IzApplication
            val diary = DiaryRepository(context)
            val garage = app.garage
            val first = vehicle("manual-original")
            val second = vehicle("manual-selected")
            val car = vehicle("manual-car", Transport.CAR)
            val rides = mutableListOf<Journey>()
            try {
                assertTrue(diary.snapshot().journeys.isEmpty())
                assertTrue(garage.vehicles.first().isEmpty())
                garage.saveVehicle(first)
                garage.saveVehicle(car)
                val ride = diary.createJourney(Transport.MOTORCYCLE, temporary = false,
                    now = System.currentTimeMillis() - 60_000L).also(rides::add)
                assertEquals(first.id, garage.assignedVehicleId(ride.id).first())
                garage.saveVehicle(second)
                addSyntheticPoints(diary, ride, count = 3)
                assertEquals(1000.222, garage.stats(first.id).first().odometerKm, .003)
                assertEquals(1000.0, garage.stats(second.id).first().odometerKm, 0.0)
                garage.assignJourney(ride.id, second.id)
                assertEquals(1000.0, garage.stats(first.id).first().odometerKm, 0.0)
                assertEquals(1000.222, garage.stats(second.id).first().odometerKm, .003)
                repeat(2) { assertEquals(ride.id, diary.createJourney(Transport.MOTORCYCLE, temporary = false).id) }
                assertEquals(second.id, garage.assignedVehicleId(ride.id).first())
                diary.finishJourney(ride.id)
                assertTrue(diary.updateJourneyDetails(ride.id, "QA renamed", "", Transport.MOTORCYCLE))
                assertEquals(second.id, garage.assignedVehicleId(ride.id).first())
                assertTrue(diary.updateJourneyDetails(ride.id, "QA changed kind", "", Transport.CAR))
                assertNull(garage.assignedVehicleId(ride.id).first())
                assertTrue(diary.updateJourneyDetails(ride.id, "QA original kind", "", Transport.MOTORCYCLE))
                assertNull(garage.assignedVehicleId(ride.id).first())
                garage.assignJourney(ride.id, second.id)
                garage.deleteVehicle(second.id)
                assertNull(garage.assignedVehicleId(ride.id).first())

                val fresh = diary.createJourney(Transport.MOTORCYCLE, temporary = false).also(rides::add)
                assertEquals(first.id, garage.assignedVehicleId(fresh.id).first())
                diary.finishJourney(fresh.id)
                diary.deleteJourney(fresh.id)
                assertNull(diary.getJourney(fresh.id))
                assertTrue(diary.snapshot().journeyVehicles.isEmpty())
            } finally {
                for (ride in rides) diary.deleteJourney(ride.id)
                for (vehicle in listOf(first, second, car)) garage.deleteVehicle(vehicle.id)
            }
        }
    }

    @Test
    fun recordingWithinTheSameSessionCarriesExplicitChoiceOnlyAndPreservesManualAssignment() {
        val context = requireGarageRideQa(physical = false)
        val app = context.applicationContext as IzApplication
        val diary = DiaryRepository(context)
        val garage = app.garage
        val navigator = app.navigation
        val first = vehicle("session-start-only")
        val second = vehicle("session-later-choice")
        val owned = mutableSetOf<String>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                assertNull(navigator.state.value.sessionId)
                assertTrue(diary.snapshot().journeys.isEmpty())
                assertTrue(garage.vehicles.first().isEmpty())
                try {
                    garage.saveVehicle(first)
                    val implicit = withContext(Dispatchers.Main) {
                        navigator.startFreeDrive(Transport.MOTORCYCLE, recordJourney = false)
                    }
                    garage.bindSession(implicit, null, first.id, Transport.MOTORCYCLE, explicitChoice = false)
                    garage.saveVehicle(second)
                    assertEquals(implicit, withContext(Dispatchers.Main) {
                        navigator.startFreeDrive(Transport.MOTORCYCLE, recordJourney = true)
                    })
                    val noChoice = requireNotNull(navigator.state.value.journey).id.also(owned::add)
                    assertNull(garage.assignedVehicleId(noChoice).first())
                    garage.bindSession(implicit, null, second.id, Transport.MOTORCYCLE, explicitChoice = true)
                    assertEquals(second.id, garage.assignedVehicleId(noChoice).first())
                    garage.assignJourney(noChoice, first.id)
                    val lateResult = runCatching {
                        garage.bindSession(implicit, null, second.id, Transport.MOTORCYCLE, explicitChoice = true)
                    }
                    assertTrue(lateResult.exceptionOrNull() is IllegalStateException)
                    assertEquals(first.id, garage.assignedVehicleId(noChoice).first())
                    withContext(Dispatchers.Main) { navigator.finishSession(implicit) }

                    val explicit = withContext(Dispatchers.Main) {
                        navigator.startFreeDrive(Transport.MOTORCYCLE, recordJourney = false)
                    }
                    garage.bindSession(explicit, null, second.id, Transport.MOTORCYCLE, explicitChoice = true)
                    assertEquals(explicit, withContext(Dispatchers.Main) {
                        navigator.startFreeDrive(Transport.MOTORCYCLE, recordJourney = true)
                    })
                    val captured = requireNotNull(navigator.state.value.journey).id.also(owned::add)
                    assertNotEquals(noChoice, captured)
                    assertEquals(second.id, garage.assignedVehicleId(captured).first())
                    garage.assignJourney(captured, first.id)
                    garage.carryStartChoice(explicit, captured)
                    assertEquals(first.id, garage.assignedVehicleId(captured).first())
                } finally {
                    navigator.state.value.sessionId?.let { session ->
                        navigator.state.value.journey?.id?.let(owned::add)
                        withContext(Dispatchers.Main) { navigator.finishSession(session) }
                    }
                    for (journey in owned) diary.deleteJourney(journey)
                    for (value in listOf(first, second)) garage.deleteVehicle(value.id)
                }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class GarageNewRidePhysicalAcceptanceTest {
    @Test
    fun onlyNewSyntheticMotorcycleRecordingIsAssignedOnS25PlusWithoutLocation() {
        runBlocking(Dispatchers.IO) {
            val context = requireGarageRideQa(physical = true)
            for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
                assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
            }
            val app = context.applicationContext as IzApplication
            val diary = DiaryRepository(context)
            assertTrue(diary.snapshot().journeys.isEmpty())
            assertTrue(app.garage.vehicles.first().isEmpty())
            val prior = historical("physical-synthetic-old", Transport.MOTORCYCLE)
            diary.saveJourney(prior)
            val bike = vehicle("physical-synthetic-one-bike")
            app.garage.saveVehicle(bike)
            val fresh = diary.createJourney(Transport.MOTORCYCLE, temporary = false)
            assertNotEquals(prior.id, fresh.id)
            assertEquals(JourneyStatus.CONFIRMED, fresh.status)
            val snapshot = diary.snapshot()
            assertEquals(prior, diary.getJourney(prior.id))
            assertEquals(setOf(prior.id, fresh.id), snapshot.journeys.map { it.id }.toSet())
            assertNull(app.garage.assignedVehicleId(prior.id).first())
            assertEquals(bike.id, app.garage.assignedVehicleId(fresh.id).first())
            assertEquals(listOf(JourneyVehicle(fresh.id, bike.id)), snapshot.journeyVehicles)
            assertTrue(snapshot.points.isEmpty())
            assertEquals(bike.odometerKm, withTimeout(3_000) { app.garage.stats(bike.id).first() }.odometerKm, 0.0)
            diary.finishJourney(fresh.id)
            assertEquals(listOf(JourneyVehicle(fresh.id, bike.id)), diary.snapshot().journeyVehicles)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("qaGarageSummary", JSONObject().put("existingUnassigned", 1)
                    .put("newAssigned", 1).put("coordinates", 0).put("existingUnchanged", true).toString())
            })
        }
    }
}

internal const val GARAGE_RIDE_QA_PACKAGE = "org.iz.navigation.qagarajsurus20261127"

internal fun requireGarageRideQa(physical: Boolean): Context {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(GARAGE_RIDE_QA_PACKAGE, context.packageName)
    assertEquals(GARAGE_RIDE_QA_PACKAGE, InstrumentationRegistry.getArguments().getString("qaTargetPackage"))
    @Suppress("DEPRECATION")
    val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertTrue(Manifest.permission.INTERNET !in permissions)
    if (physical) {
        assertEquals("SM-S936B", Build.MODEL)
        assertTrue(Build.HARDWARE !in setOf("ranchu", "goldfish"))
    } else {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON))
    }
    return context
}

private fun startChoices(db: DiaryDatabase, journey: String): Map<String, String> =
    db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
        "SELECT transport, vehicleId FROM journey_start_vehicles WHERE journeyId = ?", arrayOf(journey),
    )).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) } }

private fun durableAssignmentCount(db: DiaryDatabase, journey: String): Int =
    db.openHelper.readableDatabase.query(SimpleSQLiteQuery(
        "SELECT COUNT(*) FROM journey_vehicles WHERE journeyId = ?", arrayOf(journey),
    )).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

private suspend fun withGarageRoom(context: Context, block: suspend (DiaryDatabase, DiaryRepository) -> Unit) {
    val database: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
    try {
        block(database, DiaryRepository(context, database))
    } finally {
        database.close()
    }
}

internal fun vehicle(id: String, transport: Transport = Transport.MOTORCYCLE) = GarageVehicle(
    id = id, name = "QA $id", transport = transport, capacityLitres = 12.0, odometerKm = 1_000.0, odometerAt = 1_000L,
)

internal fun historical(id: String, transport: Transport) =
    Journey(id = id, transport = transport, title = "QA old", startedAt = 2_000L, endedAt = 3_000L)

private suspend fun addSyntheticPoints(diary: DiaryRepository, journey: Journey, count: Int) {
    for (step in 0 until count) {
        diary.addPoint(TrackPoint(journeyId = journey.id, latitude = .001 * step, longitude = 0.0,
            recordedAt = journey.startedAt + step * 10_000L, accuracy = 2f, speed = 11f))
    }
}

private suspend fun inspectBackup(context: Context, diary: DiaryRepository, inspect: (JSONObject) -> Unit) {
    val folder = File(context.cacheDir, "camera")
    check(folder.mkdirs() || folder.isDirectory)
    val archive = File.createTempFile("qa-garage-new-ride-", ".zip", folder)
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        DiaryBackup(context, diary).exportTo(uri)
        ZipFile(archive).use { zip ->
            val manifest = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader().use { it.readText() }
            inspect(JSONObject(manifest))
        }
    } finally {
        archive.delete()
    }
}
