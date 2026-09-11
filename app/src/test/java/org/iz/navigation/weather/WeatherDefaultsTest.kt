package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherDefaultsTest {
    @Test fun eachModeUsesItsApprovedNotificationThresholds() {
        val expected = mapOf(
            Transport.CAR to WeatherThresholds(70.0, 2.0, 50.0, 70.0, 0.0, 38.0),
            Transport.MOTORCYCLE to WeatherThresholds(50.0, 0.2, 30.0, 50.0, 5.0, 35.0),
            Transport.BICYCLE to WeatherThresholds(40.0, 0.2, 20.0, 35.0, 5.0, 32.0),
            Transport.WALK to WeatherThresholds(50.0, 0.5, 30.0, 45.0, 0.0, 32.0),
            Transport.RUN to WeatherThresholds(40.0, 0.2, 25.0, 40.0, 5.0, 28.0),
            Transport.PASSENGER to WeatherThresholds(70.0, 2.0, 50.0, 70.0, 0.0, 38.0),
        )
        expected.forEach { (mode, thresholds) ->
            assertEquals(mode.name, thresholds, defaultWeatherSettings(mode).thresholds)
        }
    }
}
