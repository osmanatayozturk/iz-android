package org.iz.navigation.moto

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.GarageVehicle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarageNumbersAcceptanceTest {
    @Test fun firstFiveFullFillsLearnWeightedConsumptionAndCountCostsAndMissingFillsCorrectly() = runBlocking<Unit>(Dispatchers.IO) {
        val app = requireMotorcycleQaTarget().applicationContext as IzApplication
        val garage = app.garage
        val bike = GarageVehicle(name = "QA 12L", transport = Transport.MOTORCYCLE, capacityLitres = 12.0, odometerAt = 0L)
        val car = GarageVehicle(name = "QA 50L", transport = Transport.CAR, capacityLitres = 50.0, odometerAt = 0L)
        fun fill(day: Long, km: Double, litres: Double, price: Double, full: Boolean = true, missed: Boolean = false) =
            FuelFill(vehicleId = bike.id, at = 1_736_899_200_000L + day * 86_400_000L, odometerKm = km,
                litres = litres, pricePerLitre = price, full = full, missedBefore = missed)
        try {
            garage.saveVehicle(bike)
            garage.saveVehicle(car)
            assertTrue(runCatching { garage.saveVehicle(bike.copy(capacityLitres = 0.0)) }.isFailure)
            val firstFive = listOf(fill(0, 1000.0, 9.8, 47.0), fill(1, 1200.0, 8.0, 50.0),
                fill(2, 1420.0, 9.0, 52.0), fill(3, 1600.0, 7.2, 53.0), fill(4, 1800.0, 8.4, 54.0))
            firstFive.take(3).forEach { garage.saveFill(it) }
            val learning = withTimeout(4_000) { garage.stats(bike.id).first() }
            assertEquals(3, learning.learningFullCount)
            assertNull(learning.litresPer100Km)
            assertNull(learning.fullRangeKm)
            firstFive.drop(3).forEach { garage.saveFill(it) }

            val learned = withTimeout(4_000) { garage.stats(bike.id).first() }
            assertEquals(5, learned.learningFullCount)
            assertEquals(4.075, requireNotNull(learned.litresPer100Km), .00001)
            assertEquals(294.47853, requireNotNull(learned.fullRangeKm), .0001)
            assertEquals(200.0, requireNotNull(learned.typicalIntervalKm), .0001)
            assertEquals(2.129, requireNotNull(learned.tlPerKm), .00001)
            assertEquals(2163.80, garage.fills(bike.id).first().sumOf { it.totalTl }, .001)
            assertTrue(garage.fills(car.id).first().isEmpty())
            assertNull(withTimeout(4_000) { garage.stats(car.id).first() }.litresPer100Km)

            val partial = fill(5, 1850.0, 2.0, 55.0, full = false)
            val close = fill(6, 2000.0, 6.0, 56.0)
            garage.saveFill(partial)
            garage.saveFill(close)
            val afterPartial = withTimeout(4_000) { garage.stats(bike.id).first() }
            assertEquals(4.06, requireNotNull(afterPartial.litresPer100Km), .00001)
            assertEquals(200.0, requireNotNull(afterPartial.typicalIntervalKm), .001)
            assertEquals(2.1492, requireNotNull(afterPartial.tlPerKm), .00001)

            val afterGap = fill(7, 2200.0, 7.0, 57.0, missed = true)
            garage.saveFill(afterGap)
            assertEquals(4.06, requireNotNull(withTimeout(4_000) { garage.stats(bike.id).first() }.litresPer100Km), .00001)
            val last = fill(8, 2400.0, 8.0, 58.0)
            garage.saveFill(last)
            assertEquals(2.2132, requireNotNull(withTimeout(4_000) { garage.stats(bike.id).first() }.tlPerKm), .00001)

            garage.saveFill(last.copy(litres = 9.0))
            assertEquals(4.16, requireNotNull(withTimeout(4_000) { garage.stats(bike.id).first() }.litresPer100Km), .00001)
            assertEquals(9, garage.fills(bike.id).first().size)
            garage.deleteFill(last.id)
            assertEquals(4.06, requireNotNull(withTimeout(4_000) { garage.stats(bike.id).first() }.litresPer100Km), .00001)
            assertEquals(8, garage.fills(bike.id).first().size)
        } finally {
            garage.deleteVehicle(bike.id)
            garage.deleteVehicle(car.id)
        }
    }

    @Test fun unassignedRideAddsNothingAndRepeatedAssignmentAddsOnlyAfterCounterBaseline() = runBlocking<Unit>(Dispatchers.IO) {
        val app = requireMotorcycleQaTarget().applicationContext as IzApplication
        val garage = app.garage
        val dao = DiaryDatabase.get(app).diaryDao()
        val bike = GarageVehicle(name = "QA counter", transport = Transport.MOTORCYCLE, capacityLitres = 12.0,
            odometerKm = 500.0, odometerAt = 2_000L)
        val trip = Journey(id = UUID.randomUUID().toString(), transport = Transport.MOTORCYCLE, startedAt = 500L, endedAt = 4_000L)
        suspend fun odometer() = withTimeout(4_000) { garage.stats(bike.id).first().odometerKm }
        try {
            garage.saveVehicle(bike)
            dao.save(trip)
            for (index in 0..2) {
                dao.save(TrackPoint(journeyId = trip.id, latitude = 0.27, longitude = -140.0 + index * .0001,
                    recordedAt = 1_000L + index * 1_000L, accuracy = 2f, speed = 11f))
            }
            assertNull(garage.assignedVehicleId(trip.id).first())
            assertEquals(500.0, odometer(), .000001)
            coroutineScope {
                val first = async { garage.assignJourney(trip.id, bike.id) }
                val duplicate = async { garage.assignJourney(trip.id, bike.id) }
                first.await()
                duplicate.await()
            }
            assertEquals(bike.id, garage.assignedVehicleId(trip.id).first())
            assertEquals(500.01112, odometer(), .00015)
            garage.saveVehicle(bike.copy(odometerKm = 650.0, odometerAt = 3_100L))
            assertEquals(650.0, odometer(), .000001)
        } finally {
            dao.deleteJourney(trip.id)
            garage.deleteVehicle(bike.id)
        }
    }
}
