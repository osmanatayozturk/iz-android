package com.atay.iz.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atay.iz.data.GeoCoordinate
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap

// Kept in sync with the heatmap expression and existing on-screen legend.
val HeatmapColors = listOf(Color(0xFF3989C9), Color(0xFF54BEAC), Color(0xFFF0C65D), Color(0xFFD8613F))

@Composable
fun HeatmapMap(data: HeatmapData, filterKey: String, modifier: Modifier = Modifier,
    fullscreenActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    expandable: Boolean = true,
) {
    ExpandableMap(modifier, title = "Isı haritası", expandable = expandable, actions = fullscreenActions) { mapModifier ->
        HeatmapMapContent(data, filterKey, mapModifier)
    }
}

@Composable
private fun HeatmapMapContent(data: HeatmapData, filterKey: String, modifier: Modifier) {
    val context = LocalContext.current
    val tileUrl = OsmServiceSettings(context).read().tileUrl
    val style = remember(tileUrl) { mapStyle(tileUrl, listOf("heatmap"), heatmapLayer) }
    var map by remember(tileUrl) { mutableStateOf<MapLibreMap?>(null) }
    val coordinates = remember(data.cells) { data.cells.map { GeoCoordinate(it.latitude, it.longitude) } }
    val padding = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(map, data.cells) {
        map?.let { current ->
            // Empty filtered datasets replace the old features immediately, so no stale intensity remains.
            current.updateGeoJson("heatmap", featureCollection(data.cells.map { cell ->
                pointFeature(cell.latitude, cell.longitude, JSONObject().put("weight", cell.intensity))
            }))
        }
    }
    LaunchedEffect(map, filterKey, coordinates.isEmpty()) {
        map?.let { frameCoordinates(it, coordinates, padding, 16.0) }
    }
    OsmMapView(style, modifier.testTag("heatmap_map"), onReady = { map = it })
}

private const val heatmapLayer = """
 {"id":"journey-heatmap","type":"heatmap","source":"heatmap","paint":{
   "heatmap-weight":["get","weight"],"heatmap-radius":32,"heatmap-opacity":0.75,
   "heatmap-color":["interpolate",["linear"],["heatmap-density"],
     0,"rgba(57,137,201,0)",0.1,"#3989C9",0.4,"#54BEAC",0.7,"#F0C65D",1,"#D8613F"]
 }}
"""
