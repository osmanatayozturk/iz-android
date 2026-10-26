package org.iz.navigation.integration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.power.AnalysisState
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.PostJourneyAnalysis
import org.iz.navigation.power.PowerDiagnostics
import androidx.compose.ui.platform.LocalContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.roundToInt

internal data class RecordedTrailRender(val trail: RecordedSpeedTrail = RecordedSpeedTrail(), val json: String = "{\"type\":\"FeatureCollection\",\"features\":[]}", val journeyIds: Set<String> = emptySet())

private data class CachedTrail(val id: String, val fingerprint: String, val render: RecordedTrailRender)

/** Decode the backend's prepared private cache on IO. Opening a map never builds a trail. */
@Composable
internal fun rememberRecordedTrail(journeyId: String?, analysis: AnalysisState): RecordedTrailRender {
    val application = LocalContext.current.applicationContext
    val service = remember(application) { PostJourneyAnalysis.get(application) }
    val entry = journeyId?.let(analysis::ready).takeUnless { analysis.phase == AnalysisPhase.WAITING_END }
    val fingerprint = entry?.fingerprint
    val prepared by produceState<CachedTrail?>(null, journeyId, fingerprint) {
        value = null
        if (journeyId == null || fingerprint == null) return@produceState
        value = withContext(Dispatchers.IO) {
            service.cache.trail(journeyId, fingerprint)?.let { CachedTrail(journeyId, fingerprint, it) }
        }
    }
    return prepared?.takeIf { it.id == journeyId && it.fingerprint == fingerprint }?.render ?: RecordedTrailRender()
}

/** Count only successful native source uploads, at the point the SDK actually receives data. */
internal fun MapLibreMap.updateMeasuredGeoJson(sourceId: String, json: String, metric: PowerDiagnostics.Metric) {
    val source = style?.getSourceAs<GeoJsonSource>(sourceId) ?: return
    val started = System.nanoTime()
    source.setGeoJson(json)
    PowerDiagnostics.count(metric, System.nanoTime() - started)
}

internal fun speedScaleLabel(trail: RecordedSpeedTrail): String = when {
    trail.lines.isEmpty() -> ""
    trail.scales.isEmpty() -> "Hız ölçümü yok · Gri"
    trail.scales.size > 1 -> "Her yolculuk kendi hız aralığında"
    else -> trail.scales.single().let { "${it.minKmh.roundToInt()}–${it.maxKmh.roundToInt()} km/sa · Bu yolculuk" }
}

@Composable
internal fun RecordedSpeedLegend(trail: RecordedSpeedTrail, modifier: Modifier = Modifier) {
    if (trail.lines.isEmpty()) return
    Surface(modifier.testTag("recorded-speed-legend"), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f),
        shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(speedScaleLabel(trail), style = MaterialTheme.typography.labelSmall)
            if (trail.scales.isNotEmpty()) {
                val singleTone = trail.scales.all { it.maxKmh - it.minKmh < 1.0 }
                Box(Modifier.width(124.dp).height(4.dp).background(Brush.horizontalGradient(
                    if (singleTone) listOf(Color(0xFF2563EB), Color(0xFF2563EB))
                    else listOf(Color(0xFF2563EB), Color(0xFF06B6D4), Color(0xFFEAB308), Color(0xFFDC2626)))))
            }
        }
    }
}
