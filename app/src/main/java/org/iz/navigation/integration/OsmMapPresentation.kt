package org.iz.navigation.integration

import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.android.maps.MapLibreMap

/** Local style/observation boundary; the native view, renderer and hit testing remain real. */
internal class OsmMapPresentation(
    val style: (String) -> String,
    val onReady: (MapLibreMap) -> Unit,
)

internal val LocalOsmMapPresentation = staticCompositionLocalOf<OsmMapPresentation?> { null }
