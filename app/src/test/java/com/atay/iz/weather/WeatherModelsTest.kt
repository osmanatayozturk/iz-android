package com.atay.iz.weather

import com.atay.iz.data.Transport
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherModelsTest {
    @Test fun routeRejectsUnspecifiedTravelModeAndInvalidPaces() {
        val coordinate = WeatherCoordinate(41.0, 29.0)
        val stops = listOf(RouteStop("A", coordinate), RouteStop("B", coordinate))
        listOf(
            Transport.UNKNOWN to null,
            Transport.WALK to Double.POSITIVE_INFINITY,
            Transport.RUN to 25.01,
            Transport.BICYCLE to 4.99,
            Transport.CAR to 5.0,
        ).forEach { (transport, speed) ->
            assertThrows(IllegalArgumentException::class.java) {
                PlannedRoute("r", stops, listOf(RouteVertex(coordinate, 0.0)), 0.0, 0.0, 1L,
                    transport = transport, travelSpeedKmh = speed)
            }
        }
    }

    @Test fun coordinatesRejectNonFiniteAndOutOfWorldValues() {
        listOf(
            Double.NaN to 29.0,
            41.0 to Double.POSITIVE_INFINITY,
            90.0001 to 29.0,
            41.0 to -180.0001,
        ).forEach { (latitude, longitude) ->
            assertThrows(IllegalArgumentException::class.java) {
                WeatherCoordinate(latitude, longitude)
            }
        }
    }

    @Test fun thresholdsRejectPercentagesAndPhysicalValuesOutsideTheirDomains() {
        listOf<() -> WeatherThresholds>(
            { WeatherThresholds(precipitationProbabilityPercent = -0.1) },
            { WeatherThresholds(precipitationProbabilityPercent = 100.1) },
            { WeatherThresholds(precipitationMm = -0.1) },
            { WeatherThresholds(windKmh = Double.NaN) },
            { WeatherThresholds(gustKmh = Double.POSITIVE_INFINITY) },
            { WeatherThresholds(coldC = -100.1) },
            { WeatherThresholds(hotC = 100.1) },
            { WeatherThresholds(coldC = 20.0, hotC = 19.9) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }

    @Test fun readingsPermitMissingFieldsButRejectInvalidPresentValues() {
        WeatherReading(null, null, null, null, null, null)
        listOf<() -> WeatherReading>(
            { WeatherReading(Double.NaN, null, null, null, null, null) },
            { WeatherReading(null, 101.0, null, null, null, null) },
            { WeatherReading(null, null, -0.01, null, null, null) },
            { WeatherReading(null, null, null, -0.01, null, null) },
            { WeatherReading(null, null, null, null, -0.01, null) },
            { WeatherReading(null, null, null, null, null, 360.01) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }

    @Test fun routeRejectsNonChronologicalOrImpossibleGeometry() {
        val coordinate = WeatherCoordinate(41.0, 29.0)
        val stops = listOf(RouteStop("A", coordinate), RouteStop("B", coordinate))
        assertThrows(IllegalArgumentException::class.java) {
            PlannedRoute("r", stops, listOf(RouteVertex(coordinate, 2.0), RouteVertex(coordinate, 1.0)), 1.0, 2.0, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlannedRoute("r", stops, listOf(RouteVertex(coordinate, 0.0)), -1.0, 2.0, 1L)
        }
    }
}
