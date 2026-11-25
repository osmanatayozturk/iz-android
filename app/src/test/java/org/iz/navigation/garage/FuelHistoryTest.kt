package org.iz.navigation.garage

import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class FuelHistoryTest {
    private val bike = GarageVehicle(id = "bike", name = "Test", transport = Transport.MOTORCYCLE,
        capacityLitres = 12.0, odometerAt = 0L)

    private fun fill(at: Long, km: Double, litres: Double, vehicle: GarageVehicle = bike,
        full: Boolean = true, missed: Boolean = false) = FuelFill(
        id = "${vehicle.id}-$at", vehicleId = vehicle.id, at = at, odometerKm = km,
        litres = litres, pricePerLitre = 50.0, full = full, missedBefore = missed)

    private fun steady() = (1L..5L).map { fill(it, 1_000.0 + 80 * (it - 1), 4.0) }

    @Test fun waitsForFourMeasuredIntervalsAndRelearnsBothMeansAtEachFullFill() {
        val records = listOf(fill(1, 1000.0, 5.0), fill(2, 1100.0, 3.0), fill(3, 1250.0, 6.0),
            fill(4, 1450.0, 7.0), fill(5, 1700.0, 9.0), fill(6, 1900.0, 4.0), fill(7, 2200.0, 9.0))
        val before = FuelHistory.calculate(bike, records.take(4), 1450.0)
        assertEquals(4, before.learningFullCount)
        assertNull(before.typicalIntervalKm)
        assertNull(before.typicalRefuelRangeKm)
        assertFalse(before.needsFuel(200.0))

        val first = FuelHistory.calculate(bike, records.take(5), 1700.0)
        assertEquals(5, first.learningFullCount)
        assertEquals(25.0 / 700 * 100, first.litresPer100Km!!, 1e-9)
        assertEquals(175.0, first.typicalIntervalKm!!, 1e-9)
        assertEquals(168.0, first.typicalRefuelRangeKm!!, 1e-9)

        val next = FuelHistory.calculate(bike, records.take(6), 1900.0)
        assertEquals(29.0 / 900 * 100, next.litresPer100Km!!, 1e-9)
        assertEquals(180.0, next.typicalIntervalKm!!, 1e-9)
        assertEquals(192.41379310344828, next.typicalRefuelRangeKm!!, 1e-9)

        val rolling = FuelHistory.calculate(bike, records.reversed(), 2200.0)
        assertEquals(35.0 / 1_100 * 100, rolling.litresPer100Km!!, 1e-9)
        assertEquals(220.0, rolling.typicalIntervalKm!!, 1e-9)
        assertEquals(157.14285714285714, rolling.typicalRefuelRangeKm!!, 1e-9)
    }

    @Test fun filtersHistoryByVehicleAndIgnoresLegacyThresholdsInDecisions() {
        val car = bike.copy(id = "car", transport = Transport.CAR, capacityLitres = 40.0)
        val mixed = steady() + (1L..5L).map { fill(it, 20_000.0 + 200 * (it - 1), 10.0, car) }
        val motorcycle = FuelHistory.calculate(bike, mixed, 1_320.0)
        val motorCar = FuelHistory.calculate(car, mixed, 20_800.0)
        assertEquals(80.0, motorcycle.typicalIntervalKm!!, 1e-9)
        assertEquals(160.0, motorcycle.typicalRefuelRangeKm!!, 1e-9)
        assertEquals(200.0, motorCar.typicalIntervalKm!!, 1e-9)
        assertEquals(600.0, motorCar.typicalRefuelRangeKm!!, 1e-9)
        val legacy = FuelHistory.calculate(bike.copy(typicalThresholdKm = 800.0, rangeThresholdKm = 800.0), mixed, 1_320.0)
        assertEquals(motorcycle, legacy)
        assertFalse(legacy.needsFuel(79.999))
        assertTrue(legacy.needsFuel(80.0))
        assertTrue(FuelHistory.calculate(bike, mixed, 1_400.0).needsFuel(0.0))
    }

    @Test fun partialFillCarriesMeasuredFuelAndResetsDistanceFromLastPurchase() {
        val partial = fill(6, 1_380.0, 1.0, full = false)
        val stats = FuelHistory.calculate(bike, steady() + partial, partial.odometerKm)
        assertEquals(76.0, stats.typicalIntervalKm!!, 1e-9)
        assertEquals(76.0, stats.untilTypicalKm!!, 1e-9)
        assertEquals(200.0, stats.remainingRangeKm!!, 1e-9)
        assertEquals(164.0, stats.typicalRefuelRangeKm!!, 1e-9)
        assertFalse(stats.needsFuel(35.999))
        assertTrue(stats.needsFuel(36.0))

        val closed = FuelHistory.calculate(bike, steady() + partial + fill(7, 1_400.0, 3.0), 1_400.0)
        assertEquals(5.0, closed.litresPer100Km!!, 1e-9)
        assertEquals(64.0, closed.typicalIntervalKm!!, 1e-9)
        assertEquals(168.0, closed.typicalRefuelRangeKm!!, 1e-9)
    }

    @Test fun matchingTheDistanceThresholdCanQualifyWithMoreFuelThanTheLearnedReserve() {
        val fills = steady().let { listOf(it.first().copy(litres = 12.0)) + it.drop(1) }
        val stats = FuelHistory.calculate(bike, fills, 1_320.0)
        assertEquals(128.0, stats.typicalRefuelRangeKm!!, 1e-9)
        assertTrue(stats.remainingRangeKm!! - 80 > stats.typicalRefuelRangeKm)
        assertFalse(stats.needsFuel(79.999))
        assertTrue(stats.needsFuel(80.0))
    }

    @Test fun ignoresUnmeasuredIntervalsAndInvalidCurrentTankInsteadOfInventingZero() {
        val full = steady()
        val broken = listOf(fill(6, 1_400.0, 2.0, full = false, missed = true),
            fill(6, 1_330.0, 2.0, full = false), fill(6, 1_400.0, 13.0),
            fill(6, 1_310.0, 5.0), fill(6, 1_320.0, 2.0, full = false), fill(6, 1_400.0, Double.NaN))
        for (row in broken) {
            val calculated = FuelHistory.calculate(bike, full + row, row.odometerKm)
            assertNull("row=$row", calculated.remainingRangeKm)
            assertNull(calculated.untilTypicalKm)
            assertFalse(calculated.needsFuel(220.0))
        }
        val fiveWithoutFourIntervals = full.take(2) + full[2].copy(missedBefore = true) + full.drop(3)
        assertNull(FuelHistory.calculate(bike, fiveWithoutFourIntervals, 1_320.0).typicalIntervalKm)

        val anchored = FuelHistory.calculate(bike, full + fill(6, 1_500.0, 7.0, missed = true), 1_500.0)
        assertEquals(80.0, anchored.typicalIntervalKm!!, 1e-9)
        assertEquals(240.0, anchored.remainingRangeKm!!, 1e-9)
        assertFalse(anchored.needsFuel(0.0))
    }

    @Test fun contradictoryPartialCannotTeachAClosedIntervalAndDuplicateTimeBreaksTrust() {
        val full = steady()
        val overfilled = fill(6, 1_330.0, 5.0, full = false)
        val closed = FuelHistory.calculate(bike, full + overfilled + fill(7, 1_400.0, 4.0), 1_400.0)
        assertEquals(5.0, closed.litresPer100Km!!, 1e-9)
        assertEquals(160.0, closed.typicalRefuelRangeKm!!, 1e-9)
        val left = fill(6, 1_380.0, 1.0)
        val right = left.copy(id = "other-id", odometerKm = 1_400.0, litres = 2.0)
        val duplicateTime = FuelHistory.calculate(bike, full + left + right, right.odometerKm)
        assertNull(duplicateTime.remainingRangeKm)
        assertFalse(duplicateTime.needsFuel(200.0))
    }

    @Test fun aGapOrUnreliableTailKeepsTheVerifiedPartialPurchaseWithoutOfferingFuel() {
        val known = steady() + fill(6, 1_380.0, 1.0, full = false)
        val tails = listOf(
            fill(7, 1_390.0, 1.0, full = false, missed = true),
            fill(7, 1_390.0, 13.0, full = false),
            fill(7, 1_390.0, 12.0, full = false),
        )
        for (tail in tails) {
            val stats = FuelHistory.calculate(bike, known + tail, tail.odometerKm)
            assertEquals("tail=$tail", 76.0, stats.typicalIntervalKm!!, 1e-9)
            assertEquals("tail=$tail", 164.0, stats.typicalRefuelRangeKm!!, 1e-9)
            assertEquals(5.0, stats.litresPer100Km!!, 1e-9)
            assertNull(stats.remainingRangeKm)
            assertNull(stats.untilTypicalKm)
            assertFalse(stats.needsFuel(0.0))
            assertFalse(stats.needsFuel(100.0))
        }
    }

    @Test fun aFullPurchaseWithAnUnmeasurableIntervalKeepsPriorVerifiedObservations() {
        val fills = steady() + fill(6, 1_380.0, 1.0, full = false) + fill(7, 1_500.0, 1.0)
        val stats = FuelHistory.calculate(bike, fills, 1_500.0)
        assertEquals(5.0, stats.litresPer100Km!!, 1e-9)
        assertEquals(76.0, stats.typicalIntervalKm!!, 1e-9)
        assertEquals(176.0, stats.typicalRefuelRangeKm!!, 1e-9)
        assertEquals(240.0, stats.remainingRangeKm!!, 1e-9)
        assertFalse(stats.needsFuel(0.0))
    }

    @Test fun refusesInvalidOdometerAndLiveDistanceBeyondPhysicalRange() {
        val fills = steady()
        for (odometer in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 1_319.0, 1_561.0)) {
            val stats = FuelHistory.calculate(bike, fills, odometer)
            assertNull(stats.remainingRangeKm)
            assertFalse(stats.needsFuel(0.0))
        }
        val calibrated = FuelHistory.calculate(bike, fills, 1_320.0)
        for (extra in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 240.001, Double.MAX_VALUE)) {
            assertFalse("extra=$extra", calibrated.needsFuel(extra))
        }
        assertTrue(calibrated.needsFuel(240.0))
    }
}
