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
import org.iz.navigation.garage.needsFuel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarageNumbersAcceptanceTest {
    @Test fun firstFiveFullFillsLearnWeightedConsumptionAndCountCostsAndMissingFillsCorrectly() = runBlocking<Unit>(Dispatchers.IO) {
        val app = requireGarageQaTarget().applicationContext as IzApplication
        val garage = app.garage
        val bike = GarageVehicle(name = "QA 12L", transport = Transport.MOTORCYCLE, capacityLitres = 12.0, odometerAt = 0L)
        val car = GarageVehicle(name = "QA 50L", transport = Transport.CAR, capacityLitres = 50.0, odometerAt = 0L)
        fun fill(day: Long, km: Double, litres: Double, price: Double, full: Boolean = true, missed: Boolean = false) =
            FuelFill(vehicleId = bike.id, at = 1_736_899_200_000L + day * 86_400_000L, odometerKm = km,
                litres = litres, pricePerLitre = price, full = full, missedBefore = missed)
        suspend fun stats() = withTimeout(4_000) { garage.stats(bike.id).first() }
        try {
            garage.saveVehicle(bike)
            garage.saveVehicle(car)
            assertTrue(runCatching { garage.saveVehicle(bike.copy(capacityLitres = 0.0)) }.isFailure)
            val firstFive = listOf(fill(0, 1000.0, 9.8, 47.0), fill(1, 1200.0, 8.0, 50.0),
                fill(2, 1420.0, 9.0, 52.0), fill(3, 1600.0, 7.2, 53.0), fill(4, 1800.0, 8.4, 54.0))
            firstFive.take(4).forEach { garage.saveFill(it) }
            val learning = stats()
            assertEquals(4, learning.learningFullCount)
            assertNull(learning.litresPer100Km)
            assertNull(learning.fullRangeKm)
            assertNull(learning.typicalIntervalKm)
            assertNull(learning.typicalRefuelRangeKm)
            assertFalse(learning.needsFuel(1000.0))
            assertNull(withTimeout(4_000) { garage.offerStats(bike).first() })

            garage.saveFill(firstFive.last())
            val learned = stats()
            assertEquals(5, learned.learningFullCount)
            assertEquals(4.075, requireNotNull(learned.litresPer100Km), .00001)
            assertEquals(294.47853, requireNotNull(learned.fullRangeKm), .0001)
            assertEquals(200.0, requireNotNull(learned.typicalIntervalKm), .0001)
            // The first fill supplies one reserve observation despite having no preceding odometer interval.
            assertEquals(86.38037, requireNotNull(learned.typicalRefuelRangeKm), .0001)
            assertEquals(2.129, requireNotNull(learned.tlPerKm), .00001)
            assertFalse(learned.needsFuel(199.99))
            assertTrue(learned.needsFuel(200.0))
            assertEquals(2163.80, garage.fills(bike.id).first().sumOf { it.totalTl }, .001)
            assertTrue(garage.fills(car.id).first().isEmpty())
            assertNull(withTimeout(4_000) { garage.stats(car.id).first() }.litresPer100Km)

            garage.saveFill(fill(5, 2050.0, 10.0, 55.0))
            val sixth = stats()
            assertEquals(5, sixth.learningFullCount)
            assertEquals(4.057142857, requireNotNull(sixth.litresPer100Km), .00001)
            assertEquals(210.0, requireNotNull(sixth.typicalIntervalKm), .001)
            assertEquals(85.77465, requireNotNull(sixth.typicalRefuelRangeKm), .0001)

            // The seventh full fill shifts the five-interval window beyond the first 200 km interval.
            garage.saveFill(fill(6, 2200.0, 6.0, 56.0))
            val seventh = stats()
            assertEquals(4.06, requireNotNull(seventh.litresPer100Km), .00001)
            assertEquals(200.0, requireNotNull(seventh.typicalIntervalKm), .001)
            assertEquals(95.56650, requireNotNull(seventh.typicalRefuelRangeKm), .0001)

            val partialFill = fill(7, 2230.0, 1.0, 57.0, full = false)
            garage.saveFill(partialFill)
            val partial = stats()
            assertEquals(162.0, requireNotNull(partial.typicalIntervalKm), .001)
            assertEquals(133.90148, requireNotNull(partial.typicalRefuelRangeKm), .0001)
            assertEquals(162.0, requireNotNull(partial.untilTypicalKm), .001)
            assertFalse(partial.needsFuel(0.0))
            assertFalse(partial.needsFuel(156.0))
            // The reserve is reached before the 162 km between-purchases trigger.
            assertTrue(partial.needsFuel(157.0))

            garage.saveFill(partialFill.copy(litres = .5))
            assertEquals(277.88177, requireNotNull(stats().remainingRangeKm), .0001)
            garage.deleteFill(partialFill.id)
            val restored = stats()
            assertEquals(200.0, requireNotNull(restored.typicalIntervalKm), .001)
            assertEquals(95.56650, requireNotNull(restored.typicalRefuelRangeKm), .0001)

            garage.saveFill(fill(8, 2250.0, 1.0, 58.0, full = false, missed = true))
            assertFalse("The current tank cannot be estimated after an unknown partial refill", stats().needsFuel(1000.0))
            val inconsistent = fill(9, 2350.0, 13.1, 59.0)
            runCatching { garage.saveFill(inconsistent) }
            assertFalse("An amount beyond the tank capacity cannot trigger an offer", stats().needsFuel(1000.0))
        } finally {
            garage.deleteVehicle(bike.id)
            garage.deleteVehicle(car.id)
        }
    }

    @Test fun unassignedRideAddsNothingAndRepeatedAssignmentAddsOnlyAfterCounterBaseline() = runBlocking<Unit>(Dispatchers.IO) {
        val app = requireGarageQaTarget().applicationContext as IzApplication
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
