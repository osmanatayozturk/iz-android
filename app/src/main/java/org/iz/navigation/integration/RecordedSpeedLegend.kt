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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.iz.navigation.data.TrackPoint
import kotlin.math.roundToInt

internal data class RecordedTrailRender(val trail: RecordedSpeedTrail = RecordedSpeedTrail(), val json: String = "{\"type\":\"FeatureCollection\",\"features\":[]}", val journeyIds: Set<String> = emptySet())

/** Heavy sorting, coloring, simplification and JSON construction never run on the UI thread. */
@Composable
internal fun rememberRecordedTrail(points: List<TrackPoint>): RecordedTrailRender {
    val identity = remember(points) { points.map { it.journeyId }.distinct() }
    val result by produceState(RecordedTrailRender(), identity, points) {
        if (points.isEmpty()) { value = RecordedTrailRender(); return@produceState }
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val trail = recordedSpeedTrail(points) { context.ensureActive() }
            RecordedTrailRender(trail, trail.geoJson(), identity.toSet())
        }
    }
    // produceState retains its previous value until the new effect starts: don't expose a previous
    // journey even for one frame while a different identity is being computed.
    return result.takeIf { it.journeyIds == identity.toSet() } ?: RecordedTrailRender()
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
