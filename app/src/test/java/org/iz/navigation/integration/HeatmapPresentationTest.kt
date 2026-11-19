package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapPresentationTest {
    private val route = listOf(GeoCoordinate(41.0, 29.0), GeoCoordinate(41.001, 29.002))

    @Test fun lastSevenLocalDaysIntersectTransportAndChangeMembershipAtMidnight() {
        val zone = ZoneId.of("Europe/Berlin")
        fun time(value: String) = Instant.parse(value).toEpochMilli()
        val window = heatmapWindow(HeatmapPeriod.WEEK, time("2026-03-30T21:59:59Z"), zone)!!
        assertEquals(time("2026-03-23T23:00:00Z"), window.start)
        assertEquals(time("2026-03-30T22:00:00Z"), window.endExclusive)
        fun trip(id: String, end: Long?, mode: Transport = Transport.CAR) =
            Journey(id = id, startedAt = window.start - 1000, endedAt = end, transport = mode)
        val journeys = listOf(trip("old", window.start - 1), trip("boundary", window.start),
            trip("recent", window.endExclusive - 1), trip("next", window.endExclusive),
            trip("walking", window.start, Transport.WALK), trip("recording", null))
        val selection = selectHeatmap(journeys, Transport.CAR, window)
        assertEquals(listOf("boundary", "recent"), selection.journeys.map { it.id })
        assertEquals(selection.cacheKey, selectHeatmap(journeys.reversed(), Transport.CAR, window).cacheKey)
        val tomorrow = heatmapWindow(HeatmapPeriod.WEEK, window.endExclusive, zone)!!
        val nextSelection = selectHeatmap(journeys, Transport.CAR, tomorrow)
        assertEquals(listOf("recent", "next"), nextSelection.journeys.map { it.id })
        assertFalse(nextSelection.cacheKey == selection.cacheKey)
        assertEquals("WALK", selectHeatmap(journeys, Transport.WALK, window).cacheKey)
        assertEquals("EMPTY", selectHeatmap(journeys, Transport.RUN, window).cacheKey)
        val month = heatmapWindow(HeatmapPeriod.MONTH, window.endExclusive - 1, zone)!!
        assertEquals(listOf("old", "boundary", "recent"), selectHeatmap(journeys, Transport.CAR, month).journeys.map { it.id })
    }

    @Test fun renderedColorsHaveFixedCountMeaningEvenWhenAnotherJourneyIsMuchMoreFrequent() {
        val counts = listOf(1, 2, 3, 4, 7, 8, 100)
        val data = HeatmapData(lines = counts.map { HeatmapTraceLine(route, it) }, journeyCount = 100)
        val features = JSONObject(prepareHeatmapRender(data).json).getJSONArray("features")
        val colors = (0 until features.length()).map {
            val properties = features.getJSONObject(it).getJSONObject("properties")
            assertEquals(counts[it], properties.getInt("journeyCount"))
            properties.getString("color")
        }
        assertEquals(listOf("#3989C9", "#54BEAC", "#54BEAC", "#F0C65D", "#F0C65D", "#D8613F", "#D8613F"), colors)
        val single = JSONObject(prepareHeatmapRender(HeatmapData(lines = listOf(HeatmapTraceLine(route, 1)))).json)
        assertEquals(colors.first(), single.getJSONArray("features").getJSONObject(0).getJSONObject("properties").getString("color"))
    }

    @Test fun geometryUsesRecordedCoordinatesAndRetainsDisconnectedFixAsPoint() {
        val data = HeatmapData(lines = listOf(HeatmapTraceLine(route, 2)),
            isolatedPoints = listOf(HeatmapTracePoint(GeoCoordinate(40.0, 30.0), 1)), journeyCount = 3)
        val render = prepareHeatmapRender(data)
        val features = JSONObject(render.json).getJSONArray("features")
        assertEquals(2, features.length())
        val line = features.getJSONObject(0).getJSONObject("geometry")
        assertEquals("LineString", line.getString("type"))
        assertEquals(29.0, line.getJSONArray("coordinates").getJSONArray(0).getDouble(0), 0.0)
        assertEquals(41.001, line.getJSONArray("coordinates").getJSONArray(1).getDouble(1), 0.0)
        val point = features.getJSONObject(1).getJSONObject("geometry")
        assertEquals("Point", point.getString("type"))
        assertEquals(30.0, point.getJSONArray("coordinates").getDouble(0), 0.0)
        assertEquals(listOf(GeoCoordinate(40.0, 29.0), GeoCoordinate(41.001, 30.0)), render.bounds)
    }

    @Test fun emptyResultsClearSourceAndNeverRequestACameraFit() {
        val render = prepareHeatmapRender(HeatmapData())
        assertEquals(0, JSONObject(render.json).getJSONArray("features").length())
        assertTrue(render.bounds.isEmpty())
        assertFalse(render.hasGeometry)
    }

    @Test(expected = CancellationException::class)
    fun cancelledLargeGeometryPreparationNeverReturnsAnOutdatedResult() {
        val coordinates = List(10_000) { GeoCoordinate(41.0 + it * .000001, 29.0) }
        var checks = 0
        prepareHeatmapRender(HeatmapData(lines = listOf(HeatmapTraceLine(coordinates, 1)))) {
            if (++checks == 2) throw CancellationException("replaced by another filter")
        }
    }
}
