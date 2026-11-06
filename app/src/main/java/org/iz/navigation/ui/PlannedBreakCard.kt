package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.iz.navigation.navigation.PlannedBreakState
import org.iz.navigation.weather.RouteTripTiming

@Composable
internal fun PlannedBreakCard(active: PlannedBreakState, enabled: Boolean, onResume: () -> Unit,
    timing: RouteTripTiming? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PlannedBreakDetails(active, timing)
        PlannedBreakElapsed(active)
        PlannedBreakResumeButton(enabled, onResume)
    }
}

@Composable
internal fun PlannedBreakDetails(active: PlannedBreakState, timing: RouteTripTiming? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Planlı mola · ${active.stopLabel}", style = MaterialTheme.typography.titleSmall)
        val planned = formatDuration(active.plannedSeconds.toDouble())
        Text(if (active.elapsedSeconds > active.plannedSeconds) "Planlanan $planned aşıldı" else "Planlanan $planned",
            style = MaterialTheme.typography.bodySmall)
        if (timing != null) Text("Tahmini varış ${weatherTime(timing.arrivalAt)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PlannedBreakElapsed(active: PlannedBreakState, compact: Boolean = false) {
    val elapsed = active.elapsedSeconds.toLong()
    val description = "${active.stopLabel} molası, geçen süre ${elapsed / 60} dakika ${elapsed % 60} saniye"
    val label = if (compact) "Mola\n${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}"
        else "Geçen süre ${elapsed / 60} dk ${elapsed % 60} sn"
    Text(label, Modifier.testTag("planned-break-elapsed").clearAndSetSemantics { contentDescription = description },
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun PlannedBreakResumeButton(enabled: Boolean, onResume: () -> Unit) {
    Button(onClick = onResume, enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("planned-break-resume"),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Devam et") }
}
