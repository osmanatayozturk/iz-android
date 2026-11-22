package org.iz.navigation.tracking

import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarMapFixWindowTest {
    private val sample = NavigationFix(WeatherCoordinate(0.0, 0.0), 20_000, 5f)

    @Test fun priorRegistrationAndOutOfOrderSamplesCannotReviveTheMarker() {
        val window = CarMapFixWindow(30)
        assertNull(window.accept(sample, 29, 20_000))
        assertEquals(10_001L, window.accept(sample, 31, 20_000))
        assertNull(window.accept(sample, 30, 20_000))
        assertNull(window.accept(sample, 31, 20_000))
    }

    @Test fun delayedSampleExpiresFromMeasurementAndPoorAccuracyDoesNotAdvanceTheWindow() {
        val window = CarMapFixWindow(30)
        assertNull(window.accept(sample.copy(accuracyMeters = 51f), 33, 20_000))
        assertNull(window.accept(sample, 32, 30_001))
        assertEquals(1L, window.accept(sample, 31, 30_000))
    }
}
