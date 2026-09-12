package org.iz.navigation.integration

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeatmapStyleTest {
    @Test fun recordedTracesUseNarrowOpaqueLinesInsteadOfOverlappingHeatKernels() {
        val layers = JSONArray("[$heatmapLayer]")
        val line = layers.getJSONObject(0)
        assertEquals("line", line.getString("type"))
        val paint = line.getJSONObject("paint")
        assertEquals(1.0, paint.getDouble("line-opacity"), 0.0)
        assertEquals(0.0, paint.getDouble("line-blur"), 0.0)
        val width = paint.getJSONArray("line-width")
        assertEquals("interpolate", width.getString(0))
        assertEquals("zoom", width.getJSONArray(2).getString(0))
        assertEquals(8.0, width.getDouble(3), 0.0)
        assertEquals(1.0, width.getDouble(4), 0.0)
        assertEquals(12.0, width.getDouble(5), 0.0)
        assertEquals(2.0, width.getDouble(6), 0.0)
        assertEquals(16.0, width.getDouble(7), 0.0)
        assertEquals(3.0, width.getDouble(8), 0.0)
        assertFalse(paint.has("heatmap-density"))
    }

    @Test fun isolatedFixesHaveSmallOpaqueMarkersWithoutAHeatHalo() {
        val layers = JSONArray("[$heatmapLayer]")
        assertEquals(2, layers.length())
        val dots = layers.getJSONObject(1)
        assertEquals("circle", dots.getString("type"))
        val paint = dots.getJSONObject("paint")
        assertEquals(1.5, paint.getDouble("circle-radius"), 0.0)
        assertEquals(1.0, paint.getDouble("circle-opacity"), 0.0)
        assertEquals(0.0, paint.getDouble("circle-blur"), 0.0)
    }
}
