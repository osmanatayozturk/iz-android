package org.iz.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.speed.roadSpeedPresentation

internal data class CompactRoadSpeedReadout(val value: String, val source: String, val description: String)

internal fun compactRoadSpeedReadout(state: RoadSpeedState): CompactRoadSpeedReadout {
    val speed = roadSpeedPresentation(state)
    val own = if (state.ownKmh == null) "—" else speed.ownText
    val limit = when {
        state.limit == null -> "—"
        state.limit.valueKmh == null -> "∞"
        else -> speed.limitText
    }
    val ownDescription = if (state.ownKmh == null) "Hızım bilinmiyor" else "Hızım ${speed.ownText} kilometre/saat, GPS"
    val limitDescription = when (limit) {
        "—" -> "Hız sınırı bilinmiyor"
        "∞" -> "Hız sınırı sınırsız, ${speed.sourceText}"
        else -> "Hız sınırı ${speed.limitText} kilometre/saat, ${speed.sourceText}"
    }
    return CompactRoadSpeedReadout("$own/$limit km/sa", speed.sourceText, "$ownDescription. $limitDescription.")
}

@Composable
internal fun CompactGuidanceHeader(
    maxHeight: Dp,
    alongside: Boolean,
    roadSpeed: RoadSpeedState,
    controls: @Composable () -> Unit,
    hasWarning: Boolean,
    primary: @Composable (@Composable () -> Unit) -> Unit,
    text: @Composable () -> Unit,
    accessories: @Composable () -> Unit,
) {
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().testTag("navigation-map-controls-row"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                if (roadSpeed.visible) {
                    val speed = compactRoadSpeedReadout(roadSpeed)
                    Column(Modifier.testTag("road-speed-panel").clearAndSetSemantics { contentDescription = speed.description }) {
                        Text(speed.value, style = MaterialTheme.typography.titleMedium)
                        Text("GPS · ${speed.source}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            controls()
        }
    }
    val textPane: @Composable () -> Unit = {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                .testTag("compact-guidance-text").verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                text()
            }
            if (scroll.maxValue != Int.MAX_VALUE && scroll.canScrollForward) {
                Text(if (hasWarning) "Uyarı var · Devamı aşağıda" else "Devamı aşağıda",
                    modifier = Modifier.testTag("compact-guidance-overflow"),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    // Measure fixed actions first. The text uses the remaining height and scrolls independently.
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).background(MaterialTheme.colorScheme.surface)
        .testTag("compact-guidance-header").padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (alongside) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1.25f)) { primary(textPane) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions()
                    accessories()
                }
            }
        } else {
            Box(Modifier.weight(1f, fill = false)) { primary(textPane) }
            actions()
            accessories()
        }
    }
}
