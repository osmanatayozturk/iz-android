package org.iz.navigation.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.JourneyVehicle
import org.iz.navigation.moto.requireGarageRideQa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarageRideMigrationAcceptanceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)

    @Test
    fun schemaTwelveKeepsDurableVehiclesAndStartsWithNoRecordingSnapshots() {
        runBlocking(Dispatchers.IO) {
            val context = requireGarageRideQa(physical = false)
            val name = "garage-start-migration-${UUID.randomUUID()}.db"
            val now = System.currentTimeMillis()
            val manualStart = now - 180_000L
            val fill = FuelFill("original-fill", "original-bike", manualStart + 2_000L, 1_240.0, 8.0, 50.0)
            try {
                helper.createDatabase(name, 12).use { db ->
                    for ((index, id) in listOf("original-assigned", "original-unassigned", "original-temporary").withIndex()) {
                        val start = manualStart + index * 60_000L
                        val temporary = id == "original-temporary"
                        db.execSQL(
                            "INSERT INTO journeys(id,title,transport,status,startedAt,endedAt,expiresAt,note,interrupted,stepCount) " +
                                "VALUES(?,?,?,?,?,?,?,?,0,NULL)",
                            arrayOf(id, "QA v12 $id", "MOTORCYCLE", if (temporary) "TEMPORARY" else "CONFIRMED",
                                start, if (temporary) null else start + 5_000L, if (temporary) now + 3_600_000L else null, ""),
                        )
                        for (offset in listOf(1_000L, 3_000L)) {
                            db.execSQL("""INSERT INTO track_points
                                (id,journeyId,latitude,longitude,recordedAt,accuracy,speed,breakBefore)
                                VALUES (?,?,?,-140,?,4,12,?)""",
                                arrayOf("$id-$offset", id, if (offset == 1_000L) 0.27 else 0.2702,
                                    start + offset, if (offset == 1_000L) 1 else 0))
                        }
                    }
                    db.execSQL(
                        "INSERT INTO garage_vehicles(id,name,transport,capacityLitres,typicalThresholdKm,rangeThresholdKm,odometerKm,odometerAt) " +
                            "VALUES('original-bike','QA v12 motorcycle','MOTORCYCLE',12,20,40,1234,1500)",
                    )
                    db.execSQL("""INSERT INTO fuel_fills
                        (id,vehicleId,at,odometerKm,litres,pricePerLitre,full,missedBefore) VALUES (?,?,?,?,?,?,1,0)""",
                        arrayOf(fill.id, fill.vehicleId, fill.at, fill.odometerKm, fill.litres, fill.pricePerLitre))
                    db.execSQL("INSERT INTO journey_vehicles(journeyId,vehicleId) VALUES('original-assigned','original-bike')")
                }
                helper.runMigrationsAndValidate(name, 13, true, DiaryDatabase.MIGRATION_12_13).use { migrated ->
                    migrated.query("SELECT COUNT(*) FROM journey_start_vehicles").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
                val db: DiaryDatabase = Room.databaseBuilder(context, DiaryDatabase::class.java, name).addMigrations(DiaryDatabase.MIGRATION_13_14).build()
                try {
                    val diary = DiaryRepository(context, db)
                    val garage = db.garageDao()
                    val old = diary.snapshot()
                    assertEquals(3, old.journeys.size)
                    assertEquals(1234.0, old.garageVehicles.single().odometerKm, 0.0)
                    assertEquals(listOf(fill), old.fuelFills)
                    assertEquals(listOf(JourneyVehicle("original-assigned", "original-bike")), old.journeyVehicles)
                    assertEquals("original-temporary", diary.activeJourney()?.id)
                    diary.confirmJourney("original-temporary", Transport.MOTORCYCLE)
                    val confirmed = requireNotNull(diary.getJourney("original-temporary"))
                    assertEquals(JourneyStatus.CONFIRMED, confirmed.status)
                    assertNull(confirmed.expiresAt)
                    assertNull(garage.assignment("original-temporary").first())
                    assertNull(garage.assignment("original-unassigned").first())
                    assertEquals(listOf("original-temporary-1000", "original-temporary-3000"),
                        diary.journeyPoints(confirmed.id).map { it.id })
                    assertEquals("The manual journey still supplies both sides of the fuel cutoff",
                        listOf("original-assigned-1000", "original-assigned-3000"),
                        garage.points(fill.vehicleId, fill.at).first().map { it.id })
                    // Release this fixture's active row before checking a post-upgrade start.
                    db.diaryDao().save(confirmed.copy(endedAt = now))
                    val fresh = diary.createJourney(Transport.MOTORCYCLE, temporary = false)
                    assertTrue(fresh.id != confirmed.id)
                    assertEquals("original-bike", garage.assignment(fresh.id).first())
                    assertEquals(setOf(
                        JourneyVehicle("original-assigned", "original-bike"),
                        JourneyVehicle(fresh.id, "original-bike"),
                    ), garage.allAssignments().toSet())
                } finally {
                    db.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }
}
