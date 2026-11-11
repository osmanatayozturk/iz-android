package org.iz.navigation.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.iz.navigation.data.OsmRef
import org.iz.navigation.integration.OsmSurveySnapshot
import org.iz.navigation.integration.osm.OsmCorrectionElement

/** Composition-owned read boundary. Navigation, Room drafts and publication are never replaced. */
internal class OsmSurveyReads(
    val cached: suspend (String?) -> OsmSurveySnapshot?,
    val nearby: suspend (String?, () -> Boolean) -> OsmSurveySnapshot,
    val target: suspend (OsmRef) -> OsmCorrectionElement,
)

internal val LocalOsmSurveyReads = staticCompositionLocalOf<OsmSurveyReads?> { null }
