package org.iz.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.integration.PreparedOsmGpxTrace
import kotlin.math.cos

internal val OsmGpsPreparedSha256 = SemanticsPropertyKey<String>("OsmGpsPreparedSha256")
internal val OsmGpsPreparedPointCount = SemanticsPropertyKey<Int>("OsmGpsPreparedPointCount")

/** Draw the upload's retained points locally. Previewing never requests map tiles. */
@Composable
internal fun OsmGpsPreview(trace: PreparedOsmGpxTrace) {
    val lineColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.fillMaxWidth().height(144.dp).background(background).testTag("osm-gps-preview-${trace.journeyId}")
        .semantics {
            contentDescription = "Kırpılmış GPS izi, ${trace.pointCount} nokta, ${trace.segments.size} bölüm"
            this[OsmGpsPreparedSha256] = trace.sha256
            this[OsmGpsPreparedPointCount] = trace.pointCount
        }
        .drawWithCache {
            val first = trace.segments.first().first()
            var minX = 0.0
            var maxX = 0.0
            var minY = first.latitude
            var maxY = first.latitude
            fun visitPoints(visitor: (Boolean, Double, Double) -> Unit) {
                var previousLongitude = first.longitude
                var longitude = 0.0
                for (segment in trace.segments) segment.forEachIndexed { index, point ->
                    val delta = point.longitude - previousLongitude
                    longitude += (delta + 540.0) % 360.0 - 180.0
                    previousLongitude = point.longitude
                    visitor(index == 0, longitude, point.latitude)
                }
            }
            visitPoints { _, x, y ->
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
            val horizontalScale = cos(Math.toRadians((minY + maxY) / 2)).coerceAtLeast(1e-6)
            val padding = 14.dp.toPx()
            val scale = 1 / maxOf((maxX - minX) * horizontalScale / (size.width - 2 * padding).coerceAtLeast(1f),
                (maxY - minY) / (size.height - 2 * padding).coerceAtLeast(1f), 1e-9)
            val centerX = (minX + maxX) / 2
            val centerY = (minY + maxY) / 2
            val path = Path()
            visitPoints { firstInSegment, x, y ->
                val px = size.width / 2 + ((x - centerX) * horizontalScale * scale).toFloat()
                val py = size.height / 2 - ((y - centerY) * scale).toFloat()
                if (firstInSegment) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind { drawPath(path, lineColor, style = stroke) }
        })
}
