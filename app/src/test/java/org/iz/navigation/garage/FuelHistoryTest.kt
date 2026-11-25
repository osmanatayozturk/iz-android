package org.iz.navigation.garage

import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class FuelHistoryTest {
    private val bike = GarageVehicle(id = "bike", name = "Test", transport = Transport.MOTORCYCLE,
        capacityLitres = 12.0, odometerAt = 0L)

    private fun fill(at: Long, km: Double, litres: Double, vehicle: GarageVehicle = bike,
        full: Boolean = true, missed: Boolean = false) = FuelFill(
        id = "${vehicle.id}-$at", vehicleId = vehicle.id, at = at, odometerKm = km,
        litres = litres, pricePerLitre = 50.0, full = full, missedBefore = missed)

    private fun steady() = (1L..5L).map { fill(it, 1_000.0 + 80 * (it - 1), 4.0) }

    private fun initialEstimate(values: List<Double>) = initialRatio(values.map { it to 1.0 })

    private fun initialRatio(samples: List<Pair<Double, Double>>): Double {
        val weights = samples.indices.map { 0.5.pow((samples.lastIndex - it) / 6.0) }
        val lifetime = samples.sumOf { it.first } / samples.sumOf { it.second }
        val sequence = samples.indices.sumOf { weights[it] * samples[it].first } /
            samples.indices.sumOf { weights[it] * samples[it].second }
        return 0.25 * lifetime + 0.75 * sequence
    }

    @Test fun waitsForFourMeasuredIntervalsAndRelearnsBothMeansAtEachFullFill() {
        val records = listOf(fill(1, 1000.0, 5.0), fill(2, 1100.0, 3.0), fill(3, 1250.0, 6.0),
            fill(4, 1450.0, 7.0), fill(5, 1700.0, 9.0), fill(6, 1900.0, 4.0), fill(7, 2200.0, 9.0))
        val before = FuelHistory.calculate(bike, records.take(4), 1450.0)
        assertEquals(4, before.learningFullCount)
        assertNull(before.typicalIntervalKm)
        assertNull(before.typicalRefuelRangeKm)
        assertFalse(before.needsFuel(200.0))

        for (count in 5..7) {
            val history = records.take(count)
            val stats = FuelHistory.calculate(bike, history.reversed(), history.last().odometerKm)
            val intervals = history.zipWithNext { a, b -> b.odometerKm - a.odometerKm }
            val rate = initialRatio(history.drop(1).map { it.litres * 100 }.zip(intervals))
            assertEquals(5, stats.learningFullCount)
            assertEquals(rate, stats.litresPer100Km!!, 1e-9)
            assertEquals(initialEstimate(intervals), stats.typicalIntervalKm!!, 1e-9)
            assertEquals(initialEstimate(history.map { 12.0 - it.litres }) / (rate / 100), stats.typicalRefuelRangeKm!!, 1e-9)
        }
    }

    @Test fun allThreeMetricsRetainOlderHistoryBeyondTheLastFivePurchases() {
        val recent = (3L..10L).map { fill(it * DAY, 1_000.0 + 80 * it, 4.0) }
        val older = listOf(fill(DAY, 1000.0, 3.0), fill(2 * DAY, 1200.0, 9.0))
        val alternative = listOf(fill(DAY, 1090.0, 7.0), fill(2 * DAY, 1160.0, 4.5))
        val old = FuelHistory.calculate(bike, older + recent, recent.last().odometerKm)
        val revised = FuelHistory.calculate(bike, alternative + recent, recent.last().odometerKm)
        assertNotEquals(old.typicalIntervalKm, revised.typicalIntervalKm)
        assertNotEquals(old.typicalRefuelRangeKm, revised.typicalRefuelRangeKm)
        assertNotEquals(old.litresPer100Km, revised.litresPer100Km)
        assertEquals(old, FuelHistory.calculate(bike, (older + recent).reversed(), recent.last().odometerKm))
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
        val reach = initialEstimate(List(4) { 80.0 } + 60.0)
        val reserve = initialEstimate(List(5) { 8.0 } + 9.0) / .05
        assertEquals(reach, stats.typicalIntervalKm!!, 1e-9)
        assertEquals(reach, stats.untilTypicalKm!!, 1e-9)
        assertEquals(200.0, stats.remainingRangeKm!!, 1e-9)
        assertEquals(reserve, stats.typicalRefuelRangeKm!!, 1e-9)
        val tripDistanceAtReserve = 200.0 - stats.typicalRefuelRangeKm
        assertFalse(stats.needsFuel(tripDistanceAtReserve - .001))
        assertTrue(stats.needsFuel(tripDistanceAtReserve))

        val closed = FuelHistory.calculate(bike, steady() + partial + fill(7, 1_400.0, 3.0), 1_400.0)
        assertEquals(5.0, closed.litresPer100Km!!, 1e-9)
        assertEquals(initialEstimate(List(4) { 80.0 } + listOf(60.0, 20.0)), closed.typicalIntervalKm!!, 1e-9)
        assertEquals(initialEstimate(List(5) { 8.0 } + listOf(9.0, 9.0)) / .05, closed.typicalRefuelRangeKm!!, 1e-9)
    }

    @Test fun anUnconfirmedPartialEntersHistoryOnlyAfterTheMeasuredClosing() {
        val base = steady().map { it.copy(at = it.at * DAY) }
        // 3 L after 40 km overfills at the prior 0.05 L/km rate. Closing later measures 0.075 L/km.
        val partial = fill(6 * DAY, 1_360.0, 3.0, full = false)
        val open = FuelHistory.calculate(bike, base + partial, 1_360.0)
        assertEquals(80.0, open.typicalIntervalKm!!, 1e-9)
        assertEquals(160.0, open.typicalRefuelRangeKm!!, 1e-9)
        assertNull(open.remainingRangeKm)
        assertFalse(open.needsFuel(0.0))

        val closing = fill(15 * DAY, 1_420.0, 4.5)
        val closed = FuelHistory.calculate(bike, base + partial + closing, 1_420.0)
        val rate = initialRatio(List(4) { 400.0 to 80.0 } + (750.0 to 100.0))
        assertEquals(rate, closed.litresPer100Km!!, 1e-9)
        assertEquals(initialEstimate(List(4) { 80.0 } + listOf(40.0, 60.0)), closed.typicalIntervalKm!!, 1e-9)
        assertEquals(initialEstimate(List(5) { 8.0 } + listOf(9.0, 7.5)) / (rate / 100), closed.typicalRefuelRangeKm!!, 1e-9)
    }

    @Test fun matchingTheDistanceThresholdCanQualifyWithMoreFuelThanTheLearnedReserve() {
        val fills = steady().let { listOf(it.first().copy(litres = 12.0)) + it.drop(1) }
        val stats = FuelHistory.calculate(bike, fills, 1_320.0)
        assertEquals(initialEstimate(listOf(0.0, 8.0, 8.0, 8.0, 8.0)) / .05, stats.typicalRefuelRangeKm!!, 1e-9)
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
        val baseline = FuelHistory.calculate(bike, known, 1_380.0)
        val tails = listOf(
            fill(7, 1_390.0, 1.0, full = false, missed = true),
            fill(7, 1_390.0, 13.0, full = false),
            fill(7, 1_390.0, 12.0, full = false),
        )
        for (tail in tails) {
            val stats = FuelHistory.calculate(bike, known + tail, tail.odometerKm)
            assertEquals("tail=$tail", baseline.typicalIntervalKm, stats.typicalIntervalKm)
            assertEquals("tail=$tail", baseline.typicalRefuelRangeKm, stats.typicalRefuelRangeKm)
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
        assertEquals(initialEstimate(List(4) { 80.0 } + 60.0), stats.typicalIntervalKm!!, 1e-9)
        assertEquals(initialEstimate(List(5) { 8.0 } + listOf(9.0, 11.0)) / .05, stats.typicalRefuelRangeKm!!, 1e-9)
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

    @Test fun aPreparedHistoryRemainsStableAcrossOdometerUpdatesAndInputListChanges() {
        val fills = steady()
        val mutableFills = fills.toMutableList()
        val prepared = FuelHistory.prepare(bike, mutableFills)
        assertEquals(5, prepared.learningFullCount)
        mutableFills.clear()
        for (odometer in listOf(1_320.0, 1_400.0, 1_559.0, Double.NaN)) {
            assertEquals(FuelHistory.calculate(bike, fills, odometer), prepared.statsAt(odometer))
        }
    }

    private companion object { const val DAY = 86_400_000L }
}
