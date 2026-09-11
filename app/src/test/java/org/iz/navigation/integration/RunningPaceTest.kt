package org.iz.navigation.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunningPaceTest {
    @Test fun averagePaceIncludesStoppedTime() {
        // Five kilometres in 25 moving minutes plus five stopped minutes is 6:00/km.
        assertEquals(6.0, averagePaceMinutesPerKm(5_000.0, 30 * 60_000L)!!, 0.000001)
        assertEquals(5.0, averagePaceMinutesPerKm(5_000.0, 25 * 60_000L)!!, 0.000001)
    }

    @Test fun unavailableOrInvalidDistanceAndDurationHaveNoPace() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertNull(averagePaceMinutesPerKm(it, 60_000L))
        }
        assertNull(averagePaceMinutesPerKm(1_000.0, 0))
        assertNull(averagePaceMinutesPerKm(1_000.0, -1))
        assertNull(averagePaceMinutesPerKm(Double.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun partialKilometresUseTheSameTimeBasis() {
        assertEquals(5.5, averagePaceMinutesPerKm(400.0, 132_000L)!!, 0.000001)
    }
}
