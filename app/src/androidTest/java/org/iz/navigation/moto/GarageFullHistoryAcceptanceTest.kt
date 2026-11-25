package org.iz.navigation.moto

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.FuelHistory
import org.iz.navigation.garage.FuelMetricLearner
import org.iz.navigation.garage.FuelWeightSetting
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.garage.needsFuel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GarageFullHistoryAcceptanceTest {
    @Test fun firstPurchaseStillAffectsHundredthAndZipRestoresThePrediction() = runBlocking<Unit>(Dispatchers.IO) {
        val context = requireFuelHistoryQaTarget()
        val source: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        val restored: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        val archive = File.createTempFile("fuel-history-qa-", ".zip", directory)
        try {
            val history = DiaryRepository(context, source)
            val reloaded = DiaryRepository(context, restored)
            val control = GarageVehicle(name = "QA constant fuel", transport = Transport.MOTORCYCLE,
                capacityLitres = 12.0, odometerAt = 0L)
            val changed = control.copy(id = "qa-history-first-fill", name = "QA first fill changed")
            val ordinary = regularHistory(control, firstLitres = 8.0)
            val alternative = regularHistory(changed, firstLitres = 9.0)
            history.restore(DiarySnapshot(garageVehicles = listOf(control, changed), fuelFills = ordinary + alternative))
            val stored = history.snapshot()

            val controlStats = FuelHistory.calculate(control, stored.fuelFills, 20_800.0)
            val changedStats = FuelHistory.calculate(changed, stored.fuelFills, 20_800.0)
            val controlReserve = requireNotNull(controlStats.typicalRefuelRangeKm)
            val changedReserve = requireNotNull(changedStats.typicalRefuelRangeKm)
            assertEquals(4.0, requireNotNull(changedStats.litresPer100Km), 1e-9)
            assertEquals(200.0, requireNotNull(changedStats.typicalIntervalKm), 1e-9)
            assertEquals(100.0, controlReserve, 1e-8)
            // One additional first-fill litre contributes at least 25% × (1/100) / 0.04 km.
            assertTrue("The first purchase must still lower reserve at the hundredth", controlReserve - changedReserve >= 0.062499)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
            DiaryBackup(context, history).exportTo(uri)
            DiaryBackup(context, reloaded).importFrom(uri)
            val roundTrip = reloaded.snapshot()
            assertEquals(stored, roundTrip)
            assertEquals(changedStats, FuelHistory.calculate(changed, roundTrip.fuelFills, 20_800.0))

            val row = alternative.first()
            restored.garageDao().save(row.copy(litres = 8.0))
            assertEquals(100.0, reserve(reloaded, changed), 1e-8)
            restored.garageDao().deleteFill(row.id)
            val afterDelete = reloaded.snapshot()
            assertEquals(99, afterDelete.fuelFills.count { it.vehicleId == changed.id })
            assertEquals(100, afterDelete.fuelFills.count { it.vehicleId == control.id })
            assertEquals(100.0, reserve(reloaded, changed), 1e-8)
        } finally {
            restored.close()
            source.close()
            archive.delete()
        }
    }

    @Test fun calendarCanRiseAndFallByOneStepAndDoesNotChangeWhenIdle() {
        requireFuelHistoryQaTarget()
        val original = FuelMetricLearner(minimumImprovement = .1)
        val shifted = FuelMetricLearner(minimumImprovement = .1)
        assertNull(original.estimate)
        val initial = FuelWeightSetting(orderHalfLife = 6, calendarPercent = 0)
        assertEquals(initial, original.setting)
        var at = START
        val calendarSteps = mutableListOf(0)
        fun observe(gapDays: Long, km: Double) {
            at += gapDays * DAY
            val prior = original.setting
            original.observe(at, km)
            shifted.observe(at + 180L * DAY, km)
            val learned = original.setting
            assertTrue(learned.calendarPercent in setOf(0, 10, 20))
            assertTrue(learned.orderHalfLife in setOf(3, 6, 12))
            assertEquals(learned, shifted.setting)
            assertEquals(original.scoredCount, shifted.scoredCount)
            assertEquals(requireNotNull(original.estimate), requireNotNull(shifted.estimate), 1e-9)
            if (original.scoredCount < 12) assertEquals(initial, learned)
            if (learned.calendarPercent != prior.calendarPercent) {
                assertTrue("Calendar changes are adjacent", abs(learned.calendarPercent - prior.calendarPercent) == 10)
                assertEquals("The order half-life cannot change in the same opportunity", prior.orderHalfLife, learned.orderHalfLife)
                calendarSteps += learned.calendarPercent
            }
        }
        repeat(12) { observe(7, 200.0) }
        assertEquals(11, original.scoredCount)
        repeat(7) { observe(if (it == 0) 630 else 5, 60.0) }
        repeat(7) { observe(if (it == 0) 630 else 5, 210.0) }
        assertEquals(listOf(0, 10, 20), calendarSteps)
        repeat(15) {
            observe(630, 400.0)
            repeat(4) { observe(2, 60.0) }
        }
        assertEquals(listOf(0, 10, 20, 10, 0), calendarSteps)
    }

    @Test fun repeatedPartialPurchasesAreCountedOnceWhenTheFullIntervalCloses() = runBlocking<Unit>(Dispatchers.IO) {
        val garage = (requireFuelHistoryQaTarget().applicationContext as IzApplication).garage
        val bike = GarageVehicle(name = "QA frequent partial fuel", transport = Transport.MOTORCYCLE,
            capacityLitres = 12.0, odometerAt = 0L)
        suspend fun stats() = withTimeout(4_000) { garage.stats(bike.id).first() }
        suspend fun save(day: Int, km: Double, litres: Double, full: Boolean = true) {
            garage.saveFill(FuelFill(vehicleId = bike.id, at = START + day * DAY, odometerKm = km,
                litres = litres, pricePerLitre = 50.0, full = full))
        }
        try {
            garage.saveVehicle(bike)
            repeat(5) { save(it, 1000.0 + 200 * it, 8.0) }
            for (index in 1..4) {
                save(4 + index, 1800.0 + 20 * index, .4, full = false)
                val measured = stats()
                assertEquals(300.0 - index * 10, requireNotNull(measured.remainingRangeKm), 1e-6)
                assertFalse(measured.needsFuel(0.0))
            }
            val open = stats()
            assertEquals(94.67590829, requireNotNull(open.typicalIntervalKm), 1e-6)
            assertEquals(188.55278172, requireNotNull(open.typicalRefuelRangeKm), 1e-6)
            // 100 km consumed 4 L. The four partials supplied 1.6 L before this 2.4 L closing fill.
            save(9, 1900.0, 2.4)
            val closed = stats()
            assertEquals(4.0, requireNotNull(closed.litresPer100Km), 1e-6)
            assertEquals(83.37014089, requireNotNull(closed.typicalIntervalKm), 1e-6)
            assertEquals(195.75885485, requireNotNull(closed.typicalRefuelRangeKm), 1e-6)
            assertEquals(300.0, requireNotNull(closed.remainingRangeKm), 1e-6)
            assertEquals(10, garage.fills(bike.id).first().size)
        } finally {
            garage.deleteVehicle(bike.id)
        }
    }

    private suspend fun reserve(repository: DiaryRepository, vehicle: GarageVehicle): Double =
        requireNotNull(FuelHistory.calculate(vehicle, repository.snapshot().fuelFills, 20_800.0).typicalRefuelRangeKm)

    private fun regularHistory(vehicle: GarageVehicle, firstLitres: Double): List<FuelFill> =
        List(100) { index ->
            FuelFill(vehicleId = vehicle.id, at = START + index * 7L * DAY,
                odometerKm = 1000.0 + index * 200.0, litres = if (index == 0) firstLitres else 8.0, pricePerLitre = 50.0)
        }

    private companion object {
        const val START = 1_704_067_200_000L
        const val DAY = 86_400_000L
    }
}
