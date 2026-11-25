package org.iz.navigation.garage

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelMetricLearnerTest {
    @Test fun consumptionWeightsLitresAndKilometresWithinEachComponent() {
        val history = FuelMetricLearner(0.01)
        assertNull(history.estimate)
        history.observe(0L, numerator = 300.0, denominator = 100.0)
        history.observe(200 * DAY, numerator = 1500.0, denominator = 200.0)
        val decay = 0.5.pow(1.0 / 6)
        val lifetime = 1800.0 / 300.0
        val order = (decay * 300 + 1500) / (decay * 100 + 200)
        assertEquals(0.25 * lifetime + 0.75 * order, history.estimate!!, 1e-12)
        assertEquals(1, history.scoredCount)
    }

    @Test fun withholdingScoresStillTrainsAndNeverManufacturesForecastErrors() {
        val history = FuelMetricLearner(0.01)
        repeat(30) { index -> history.observe(index * DAY, if (index < 15) 1.0 else 9.0, score = false) }
        assertEquals(0, history.scoredCount)
        assertEquals(FuelWeightSetting(), history.setting)
        val before = history.estimate!!
        history.observe(31 * DAY, before)
        assertEquals(1, history.scoredCount)
        assertEquals(FuelWeightSetting(), history.setting)
    }

    @Test fun firstObservationStillContributesToTheHundredthEstimate() {
        fun estimate(first: Double): Double {
            val history = FuelMetricLearner(0.1)
            repeat(100) { i -> history.observe(i * DAY, if (i == 0) first else 200.0, score = false) }
            return history.estimate!!
        }
        val normalizedOldest = 0.5.pow(99.0 / 6) / (0..99).sumOf { 0.5.pow(it / 6.0) }
        val difference = 40.0 * (0.25 / 100 + 0.75 * normalizedOldest)
        assertEquals(difference, estimate(240.0) - estimate(200.0), 1e-10)
    }

    @Test fun constantDataAndNoiseBelowThePhysicalMinimumKeepTheSetting() {
        for (amplitude in listOf(0.0, 0.001)) {
            val history = FuelMetricLearner(0.01)
            repeat(75) { i ->
                history.observe(i * DAY, 7.234 + if (i in 30..50) amplitude else 0.0)
                assertEquals(FuelWeightSetting(), history.setting)
            }
            if (amplitude == 0.0) assertEquals(7.234, history.estimate!!, 0.0)
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
