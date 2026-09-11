package com.atay.iz.weather

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object WeatherEngine {
    private const val SAMPLE_INTERVAL_SECONDS = 900.0
    private const val MAX_SAMPLES = 48
    private const val FORECAST_MATCH_METERS = 100.0
    private val missingReading = WeatherReading(null, null, null, null, null, null)

    fun sampleVertices(route: PlannedRoute): List<RouteVertex> {
        val vertices = route.vertices
        if (vertices.isEmpty()) return emptyList()
        val duration = route.durationSeconds
        if (duration <= 0.0) return listOf(vertices.first().copy(elapsedSeconds = 0.0))
        val naturalIntervals = ceil(duration / SAMPLE_INTERVAL_SECONDS).toInt().coerceAtLeast(1)
        val targets = if (naturalIntervals <= MAX_SAMPLES - 1) {
            buildList {
                add(0.0)
                var elapsed = SAMPLE_INTERVAL_SECONDS
                while (elapsed < duration) {
                    add(elapsed)
                    elapsed += SAMPLE_INTERVAL_SECONDS
                }
                add(duration)
            }
        } else {
            val interval = duration / (MAX_SAMPLES - 1)
            (0 until MAX_SAMPLES).map { index -> if (index == MAX_SAMPLES - 1) duration else index * interval }
        }
        return targets.distinct().map { target -> RouteVertex(interpolate(vertices, target), target) }
    }

    fun assess(
        route: PlannedRoute,
        departureAt: Long,
        forecasts: List<LocationForecast>,
        thresholds: WeatherThresholds,
        elapsedSeconds: Double = 0.0,
    ): WeatherAssessment {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) { "İlerleme süresi geçersiz." }
        val usedForecasts = mutableListOf<LocationForecast>()
        val samples = sampleVertices(route).asSequence()
            .filter { it.elapsedSeconds + 1e-9 >= elapsedSeconds }
            .map { vertex ->
                val arrivalAt = departureAt + (vertex.elapsedSeconds * 1_000.0).toLong()
                val forecast = forecasts.asSequence()
                    .map { it to distanceMeters(vertex.coordinate, it.coordinate) }
                    .filter { it.second <= FORECAST_MATCH_METERS }
                    .minByOrNull { it.second }
                    ?.first
                val reading = forecast?.let { forecastAt(it, arrivalAt) } ?: missingReading
                if (forecast != null && reading !== missingReading) usedForecasts += forecast
                RouteWeatherSample(
                    coordinate = vertex.coordinate,
                    elapsedSeconds = vertex.elapsedSeconds,
                    arrivalAt = arrivalAt,
                    reading = reading,
                    hazards = hazards(reading, thresholds),
                    complete = reading.isComplete(),
                )
            }.toList()
        val exceeded = samples.zipWithNext().sumOf { (start, finish) ->
            if (start.hazards.isNotEmpty() || finish.hazards.isNotEmpty()) {
                max(0.0, finish.elapsedSeconds - start.elapsedSeconds)
            } else 0.0
        }
        val readings = samples.map { it.reading }
        return WeatherAssessment(
            departureAt = departureAt,
            samples = samples,
            exceededSeconds = exceeded,
            complete = samples.isNotEmpty() && samples.all { it.complete },
            minTemperatureC = readings.mapNotNull { it.temperatureC }.minOrNull(),
            maxTemperatureC = readings.mapNotNull { it.temperatureC }.maxOrNull(),
            maxPrecipitationProbabilityPercent = readings.mapNotNull { it.precipitationProbabilityPercent }.maxOrNull(),
            maxWindKmh = readings.mapNotNull { it.windKmh }.maxOrNull(),
            maxGustKmh = readings.mapNotNull { it.gustKmh }.maxOrNull(),
            fetchedAt = usedForecasts.minOfOrNull { it.fetchedAt },
        )
    }

    fun compare(
        route: PlannedRoute,
        departureAt: Long,
        forecasts: List<LocationForecast>,
        thresholds: WeatherThresholds,
    ): List<WeatherAssessment> = (0..6).map { offset ->
        assess(route, departureAt + offset * 30L * 60L * 1_000L, forecasts, thresholds, 0.0)
    }

    fun hazards(reading: WeatherReading, thresholds: WeatherThresholds): Set<WeatherHazard> = buildSet {
        if (reading.precipitationProbabilityPercent?.let { it >= thresholds.precipitationProbabilityPercent } == true ||
            reading.precipitationMm?.let { it >= thresholds.precipitationMm } == true
        ) add(WeatherHazard.RAIN)
        if (reading.windKmh?.let { it >= thresholds.windKmh } == true ||
            reading.gustKmh?.let { it >= thresholds.gustKmh } == true
        ) add(WeatherHazard.WIND)
        if (reading.temperatureC?.let { it <= thresholds.coldC } == true) add(WeatherHazard.COLD)
        if (reading.temperatureC?.let { it >= thresholds.hotC } == true) add(WeatherHazard.HEAT)
    }

    fun distanceMeters(a: WeatherCoordinate, b: WeatherCoordinate): Double {
        val radius = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val haversine = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
        return 2.0 * radius * asin(min(1.0, sqrt(haversine)))
    }

    private fun interpolate(vertices: List<RouteVertex>, target: Double): WeatherCoordinate {
        if (target <= vertices.first().elapsedSeconds) return vertices.first().coordinate
        if (target >= vertices.last().elapsedSeconds) return vertices.last().coordinate
        val upperIndex = vertices.indexOfFirst { it.elapsedSeconds >= target }.coerceAtLeast(1)
        val before = vertices[upperIndex - 1]
        val after = vertices[upperIndex]
        val duration = after.elapsedSeconds - before.elapsedSeconds
        if (duration <= 0.0) return after.coordinate
        val ratio = ((target - before.elapsedSeconds) / duration).coerceIn(0.0, 1.0)
        val longitudeDelta = ((after.coordinate.longitude - before.coordinate.longitude + 540.0) % 360.0) - 180.0
        val longitude = ((before.coordinate.longitude + longitudeDelta * ratio + 540.0) % 360.0) - 180.0
        return WeatherCoordinate(
            latitude = before.coordinate.latitude + (after.coordinate.latitude - before.coordinate.latitude) * ratio,
            longitude = longitude,
        )
    }

    private fun forecastAt(forecast: LocationForecast, arrivalAt: Long): WeatherReading {
        val before = forecast.hours.lastOrNull { it.time <= arrivalAt } ?: return missingReading
        val after = forecast.hours.firstOrNull { it.time >= arrivalAt } ?: return missingReading
        if (after.time - before.time > 3_600_000L) return missingReading
        return WeatherReading(
            temperatureC = before.reading.temperatureC,
            precipitationProbabilityPercent = after.reading.precipitationProbabilityPercent,
            precipitationMm = after.reading.precipitationMm,
            windKmh = before.reading.windKmh,
            gustKmh = after.reading.gustKmh,
            windDirectionDegrees = before.reading.windDirectionDegrees,
        )
    }

    private fun WeatherReading.isComplete(): Boolean = temperatureC != null &&
        precipitationProbabilityPercent != null && precipitationMm != null && windKmh != null && gustKmh != null
}
