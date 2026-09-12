package org.iz.navigation.integration

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.MapLibreMap

@Composable
fun HeatmapMap(
    data: HeatmapData, filterKey: String, modifier: Modifier = Modifier,
    fullscreenActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    expandable: Boolean = true,
) {
    val render = rememberHeatmapGeometry(data)
    ExpandableMap(modifier, title = "Isı haritası", expandable = expandable, actions = fullscreenActions) { mapModifier ->
        HeatmapMapContent(render, filterKey, mapModifier)
    }
}

/** A screen can share one prepared result between its preview and fullscreen map. */
@Composable
internal fun HeatmapMapContent(render: HeatmapRender?, filterKey: String, modifier: Modifier) {
    val context = LocalContext.current
    val tileUrl = OsmServiceSettings(context).read().tileUrl
    val style = remember(tileUrl) { mapStyle(tileUrl, listOf("heatmap"), heatmapLayer) }
    var map by remember(tileUrl) { mutableStateOf<MapLibreMap?>(null) }
    var hasFramed by remember(map, filterKey) { mutableStateOf(false) }
    val padding = with(LocalDensity.current) { 32.dp.roundToPx() }
    LaunchedEffect(map, render, filterKey) {
        map?.let { current ->
            // Clear old features even while a new filter is still being prepared.
            current.updateGeoJson("heatmap", render?.json ?: emptyGeoJson)
            if (render != null && render.bounds.isNotEmpty() && !hasFramed) {
                frameCoordinates(current, render.bounds, padding, 16.0)
                hasFramed = true
            }
        }
    }
    OsmMapView(style, modifier.testTag("heatmap_map"), onReady = { map = it })
}

// Fully opaque lines avoid manufacturing extra density from repeated geometry.
// Feature colors and legend use the same fixed frequency bands.
internal const val heatmapLayer = """
 {"id":"journey-heatmap","type":"line","source":"heatmap",
  "filter":["==",["geometry-type"],"LineString"],
  "layout":{"line-cap":"round","line-join":"round","line-sort-key":["get","journeyCount"]},
  "paint":{"line-color":["get","color"],"line-opacity":1,"line-blur":0,
    "line-width":["interpolate",["linear"],["zoom"],8,1,12,2,16,3]}},
 {"id":"journey-heatmap-points","type":"circle","source":"heatmap",
  "filter":["==",["geometry-type"],"Point"],
  "layout":{"circle-sort-key":["get","journeyCount"]},
  "paint":{"circle-color":["get","color"],"circle-radius":1.5,"circle-opacity":1,"circle-blur":0}}
"""
