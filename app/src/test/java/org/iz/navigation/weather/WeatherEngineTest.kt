package org.iz.navigation.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherEngineTest {
    private val start = WeatherCoordinate(41.0, 29.0)
    private val end = WeatherCoordinate(41.09, 29.0)
    private val route = PlannedRoute(
        id = "route",
        stops = listOf(RouteStop("A", start), RouteStop("B", end)),
        vertices = listOf(RouteVertex(start, 0.0), RouteVertex(end, 1_800.0)),
        distanceMeters = 10_000.0,
        durationSeconds = 1_800.0,
        createdAt = 1_700_000_000_000L,
    )

    @Test fun samplingIncludesEndpointsAndUsesAtMostFortyEightStablePoints() {
        val ordinary = WeatherEngine.sampleVertices(route)
        assertEquals(listOf(0.0, 900.0, 1_800.0), ordinary.map { it.elapsedSeconds })
        assertEquals(start, ordinary.first().coordinate)
        assertEquals(end, ordinary.last().coordinate)

        val longRoute = route.copy(
            vertices = listOf(RouteVertex(start, 0.0), RouteVertex(end, 90_000.0)),
            durationSeconds = 90_000.0,
        )
        val sampled = WeatherEngine.sampleVertices(longRoute)
        assertEquals(48, sampled.size)
        assertEquals(0.0, sampled.first().elapsedSeconds, 0.0)
        assertEquals(90_000.0, sampled.last().elapsedSeconds, 0.0)
    }

    @Test fun thresholdEqualityTriggersEachHazardAndMissingValuesDoNot() {
        val thresholds = WeatherThresholds()
        assertEquals(
            setOf(WeatherHazard.RAIN, WeatherHazard.WIND, WeatherHazard.COLD, WeatherHazard.HEAT),
            WeatherEngine.hazards(WeatherReading(5.0, 50.0, 0.2, 30.0, 50.0, null), thresholds) +
                WeatherEngine.hazards(WeatherReading(35.0, null, null, null, null, null), thresholds),
        )
        assertTrue(WeatherEngine.hazards(WeatherReading(null, null, null, null, null, null), thresholds).isEmpty())
    }

    @Test fun assessmentMatchesRoundedForecastCoordinateAndUsesFollowingHourConservatively() {
        val departure = 1_704_067_200_000L
        val safe = WeatherReading(15.0, 0.0, 0.0, 5.0, 8.0, null)
        val wet = WeatherReading(14.0, 60.0, 0.3, 5.0, 8.0, null)
        val forecasts = WeatherEngine.sampleVertices(route).mapIndexed { index, vertex ->
            LocationForecast(
                WeatherCoordinate(vertex.coordinate.latitude + 0.00001, vertex.coordinate.longitude),
                listOf(WeatherHour(departure + vertex.elapsedSeconds.toLong() * 1_000, if (index == 2) wet else safe)),
                fetchedAt = departure - 1_000 - index,
            )
        }

        val result = WeatherEngine.assess(route, departure, forecasts, WeatherThresholds())

        assertTrue(result.complete)
        assertEquals(900.0, result.exceededSeconds, 0.0)
        assertEquals(setOf(WeatherHazard.RAIN), result.samples.last().hazards)
        assertEquals(departure - 1_002, result.fetchedAt)
    }

    @Test fun aHazardAtEitherEndpointExposesTheSegmentWithoutDoubleCounting() {
        val departure = 1_704_067_200_000L
        val wet = WeatherReading(14.0, 60.0, 0.3, 5.0, 8.0, null)
        val safe = WeatherReading(15.0, 0.0, 0.0, 5.0, 8.0, null)
        val sampled = WeatherEngine.sampleVertices(route)
        val forecasts = sampled.mapIndexed { index, vertex ->
            LocationForecast(
                vertex.coordinate,
                listOf(WeatherHour(departure + vertex.elapsedSeconds.toLong() * 1_000, if (index < 2) wet else safe)),
                departure,
            )
        }

        val result = WeatherEngine.assess(route, departure, forecasts, WeatherThresholds())

        assertEquals(1_800.0, result.exceededSeconds, 0.0)
    }

    @Test fun samplingInterpolatesLongitudeAcrossTheDateLineByTheShortArc() {
        val west = WeatherCoordinate(0.0, 179.0)
        val east = WeatherCoordinate(0.0, -179.0)
        val crossing = route.copy(
            stops = listOf(RouteStop("Batı", west), RouteStop("Doğu", east)),
            vertices = listOf(RouteVertex(west, 0.0), RouteVertex(east, 1_800.0)),
        )

        val middle = WeatherEngine.sampleVertices(crossing)[1].coordinate

        assertEquals(180.0, kotlin.math.abs(middle.longitude), 0.000001)
    }

    @Test fun missingOrUnrelatedForecastNeverBecomesSafeOrComplete() {
        val departure = 1_704_067_200_000L
        val farAway = LocationForecast(
            WeatherCoordinate(42.0, 29.0),
            listOf(WeatherHour(departure, WeatherReading(15.0, 0.0, 0.0, 5.0, 8.0, null))),
            fetchedAt = departure,
        )

        val result = WeatherEngine.assess(route, departure, listOf(farAway), WeatherThresholds())

        assertFalse(result.complete)
        assertTrue(result.samples.all { !it.complete })
        assertTrue(result.samples.all { it.hazards.isEmpty() })
        assertNull(result.fetchedAt)
    }

    @Test fun halfPastUsesCurrentInstantValuesAndFollowingPrecedingHourTotals() {
        val ten = 1_704_103_200_000L
        val eleven = ten + 3_600_000L
        val coordinate = start
        val shortRoute = route.copy(
            stops = listOf(RouteStop("A", coordinate), RouteStop("B", coordinate)),
            vertices = listOf(RouteVertex(coordinate, 0.0), RouteVertex(coordinate, 1.0)),
            durationSeconds = 1.0,
        )
        val forecast = LocationForecast(
            coordinate,
            listOf(
                WeatherHour(ten, WeatherReading(10.0, 0.0, 0.0, 5.0, 10.0, 90.0)),
                WeatherHour(eleven, WeatherReading(20.0, 70.0, 0.4, 40.0, 60.0, 180.0)),
            ),
            ten,
        )

        val result = WeatherEngine.assess(shortRoute, ten + 1_800_000L, listOf(forecast), WeatherThresholds())

        assertEquals(10.0, result.samples.first().reading.temperatureC!!, 0.0)
        assertEquals(5.0, result.samples.first().reading.windKmh!!, 0.0)
        assertEquals(60.0, result.samples.first().reading.gustKmh!!, 0.0)
        assertTrue(WeatherHazard.RAIN in result.samples.first().hazards)
        assertTrue(WeatherHazard.WIND in result.samples.first().hazards)
    }

    @Test fun aMissingHourlyRowCannotBeBridgedIntoAFabricatedCompleteForecast() {
        val ten = 1_704_103_200_000L
        val twelve = ten + 7_200_000L
        val coordinate = start
        val shortRoute = route.copy(
            stops = listOf(RouteStop("A", coordinate), RouteStop("B", coordinate)),
            vertices = listOf(RouteVertex(coordinate, 0.0), RouteVertex(coordinate, 1.0)),
            durationSeconds = 1.0,
        )
        val complete = WeatherReading(15.0, 10.0, 0.0, 5.0, 8.0, 90.0)
        val forecast = LocationForecast(
            coordinate,
            listOf(WeatherHour(ten, complete), WeatherHour(twelve, complete)),
            ten,
        )

        val result = WeatherEngine.assess(shortRoute, ten + 1_800_000L, listOf(forecast), WeatherThresholds())

        assertFalse(result.complete)
        assertFalse(result.samples.first().complete)
    }

    @Test fun liveAssessmentKeepsSampleCoordinatesStableWhileEtasShiftFromOriginalDeparture() {
        val departure = 1_704_067_200_000L
        val result = WeatherEngine.assess(route, departure, emptyList(), WeatherThresholds(), elapsedSeconds = 600.0)
        assertEquals(listOf(900.0, 1_800.0), result.samples.map { it.elapsedSeconds })
        assertEquals(departure + 900_000, result.samples.first().arrivalAt)
        assertEquals(WeatherEngine.sampleVertices(route).drop(1).map { it.coordinate }, result.samples.map { it.coordinate })
    }

    @Test fun compareReturnsSevenChronologicalCandidatesWithoutRankingThem() {
        val departure = 1_704_067_200_000L
        val result = WeatherEngine.compare(route, departure, emptyList(), WeatherThresholds())
        assertEquals((0..6).map { departure + it * 1_800_000L }, result.map { it.departureAt })
    }
}
