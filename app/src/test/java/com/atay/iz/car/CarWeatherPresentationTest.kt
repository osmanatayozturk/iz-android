package com.atay.iz.car

import com.atay.iz.data.Transport
import com.atay.iz.weather.*
import org.junit.Assert.*
import org.junit.Test

class CarWeatherPresentationTest {
    private val now = 1_800_000_000_000L
    private val point = WeatherCoordinate(41.0, 29.0)
    private val route = PlannedRoute("car-weather", listOf(RouteStop("A", point), RouteStop("B", point)),
        listOf(RouteVertex(point, 0.0), RouteVertex(point, 60.0)), 100.0, 60.0, now, transport = Transport.CAR)
    private val assessment = WeatherAssessment(now, emptyList(), 0.0, true, 18.0, 23.0, 35.0, 12.0, 20.0, now)
    private fun ready() = RideWeatherLiveState(RideWeatherStatus.READY, "trip", route, assessment)

    @Test fun completeRouteWithoutMessageShowsActualReadingsInCompactSummary() {
        val value = carWeatherPresentation(ready(), now)
        assertTrue(value.summary.contains("18–23 °C"))
        assertTrue(value.summary.contains("Yağış %35"))
        assertTrue(value.summary.contains("Rüzgâr 12 km/sa"))
        assertTrue(value.detail.contains("Hamle 20 km/sa"))
        assertFalse(value.detail.contains("henüz alınmadı"))
        assertTrue(value.summary.length <= 250)
    }

    @Test fun currentLocationForecastIsNotLabeledAsTheRoadAhead() {
        val value = carWeatherPresentation(ready().copy(route = null), now)
        assertTrue(value.summary.startsWith("Mevcut konum"))
        assertTrue(value.detail.contains("Yalnızca mevcut konum"))
        assertFalse(value.title.contains("Rota"))
    }

    @Test fun staleGpsAndOldForecastKeepReadingsButExposeBothLimitations() {
        val value = carWeatherPresentation(ready().copy(gpsStale = true,
            assessment = assessment.copy(fetchedAt = now - 3_600_000L)), now)
        assertTrue(value.summary.startsWith("GPS güncel değil"))
        assertTrue(value.detail.contains("Tahmin güncel değil"))
        assertTrue(value.detail.contains("18–23 °C"))
    }

    @Test fun incompleteForecastDoesNotBecomeReadyAndMissingValuesAreNotZero() {
        val value = carWeatherPresentation(ready().copy(assessment = assessment.copy(
            complete = false, minTemperatureC = null, maxTemperatureC = null,
            maxPrecipitationProbabilityPercent = null)), now)
        assertTrue(value.summary.startsWith("Tahmin kısmen eksik"))
        assertTrue(value.detail.contains("Sıcaklık —"))
        assertTrue(value.detail.contains("Yağış —"))
        assertFalse(value.detail.contains("Yağış %0"))
    }

    @Test fun loadingOffAndUnavailableStatesRemainDistinct() {
        assertEquals("Hava takibi kapalı", carWeatherPresentation(RideWeatherLiveState(), now).summary)
        assertTrue(carWeatherPresentation(ready().copy(status = RideWeatherStatus.LOADING), now)
            .summary.startsWith("Tahmin alınıyor"))
        assertEquals("Tahmin verisi yok", carWeatherPresentation(ready().copy(assessment = null), now).summary)
    }

    @Test fun failedRefreshRetainsCachedValuesAndShowsFailure() {
        val value = carWeatherPresentation(ready().copy(refreshFailed = true), now)
        assertTrue(value.summary.startsWith("Tahmin yenilenemedi"))
        assertTrue(value.detail.contains("23 °C"))
        assertTrue(value.detail.contains("Tahmin yenilenemedi"))
        val absent = carWeatherPresentation(ready().copy(status = RideWeatherStatus.ERROR, assessment = null), now)
        assertEquals("Tahmin alınamadı", absent.summary)
    }
}

