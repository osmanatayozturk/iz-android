package org.iz.navigation.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun LiveLeanDial(sessionId: String, reading: LiveLeanReading?, modifier: Modifier = Modifier) {
    var expired by remember(sessionId, reading) { mutableStateOf(false) }
    val degrees = if (expired) null else validLiveLeanDegrees(reading, sessionId, SystemClock.elapsedRealtime())
    LaunchedEffect(sessionId, reading) {
        if (reading != null && reading.sessionId == sessionId) {
            val age = SystemClock.elapsedRealtime() - reading.measuredElapsedMillis
            if (age in 0 until LiveLeanLifetimeMillis) delay(LiveLeanLifetimeMillis - age)
            expired = true
        }
    }
    val value = degrees?.let { abs(it).roundToInt() }
    val side = when {
        value == null -> null
        value == 0 -> "Dik"
        degrees != null && degrees < 0 -> "Sol"
        else -> "Sağ"
    }
    val primary = MaterialTheme.colorScheme.primary
    val scale = MaterialTheme.colorScheme.outline
    Surface(modifier.sizeIn(minWidth = 114.dp, minHeight = 51.dp).testTag("live-lean-dial").clearAndSetSemantics {
        contentDescription = if (value == null) "Tahmini yatış, ölçüm yok" else "Tahmini yatış, $side $value derece"
    }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(48.dp, 40.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.width * .43f
                    val pivot = Offset(size.width / 2f, size.height * .73f)
                    val circle = Offset(pivot.x - radius, pivot.y - radius)
                    val diameter = Size(radius * 2, radius * 2)
                    drawArc(scale, 205f, 130f, false, circle, diameter, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    for (tick in -60..60 step 30) {
                        val radians = Math.toRadians((tick - 90).toDouble())
                        fun point(length: Float) = pivot + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * length
                        drawLine(scale, point(radius * .79f), point(radius * .88f), 1.dp.toPx(), StrokeCap.Round)
                    }
                    if (degrees != null) {
                        val angle = degrees.toFloat()
                        drawArc(primary, 270f + minOf(0f, angle), abs(angle), false, circle, diameter,
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                        val radians = Math.toRadians((angle - 90f).toDouble())
                        val tip = pivot + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius
                        drawCircle(primary, 2.dp.toPx(), tip)
                    }
                }
                if (degrees != null) Icon(Icons.Outlined.TwoWheeler, null,
                    Modifier.size(17.dp).offset(y = 5.dp).rotate(degrees.toFloat()), tint = primary)
            }
            Column {
                if (value != null) {
                    Text("≈$value°", Modifier.testTag("live-lean-value"), style = MaterialTheme.typography.titleMedium,
                        fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (value == 0) "Dik" else "$side yatış", style = MaterialTheme.typography.labelSmall)
                } else Text("Ölçüm yok", Modifier.testTag("live-lean-unavailable"), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
