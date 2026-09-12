package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import org.iz.navigation.data.GeoCoordinate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapPresentationTest {
    private val route = listOf(GeoCoordinate(41.0, 29.0), GeoCoordinate(41.001, 29.002))

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
