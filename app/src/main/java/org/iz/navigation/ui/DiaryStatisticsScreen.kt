package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.power.AnalysisPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DiaryStatisticsScreen(
    state: DiaryState,
    now: Long,
    onCompare: (() -> Unit)? = null,
    onJourney: (Journey) -> Unit,
) {
    val journeys = remember(state.journeys) {
        state.journeys.filter { it.status == JourneyStatus.CONFIRMED }.sortedByDescending { it.startedAt }
    }
    val completed = remember(journeys) { journeys.filter { it.endedAt != null } }
    val totals = remember(completed, state.analysis) { completed.mapNotNull { state.prepared(it)?.stats } }
    val ready = !state.analysis.pending && totals.size == completed.size &&
        state.analysis.phase != AnalysisPhase.WAITING_END
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("İstatistikler", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium) }
        item {
            StatisticsSurface(emphasized = true) {
                Text("Ölçülen hareket süresi", style = MaterialTheme.typography.titleMedium)
                if (ready && completed.isNotEmpty()) {
                    val observed = totals.sumOf { it.observedMillis }
                    val moving = if (observed > 0) statisticsDuration(totals.sumOf { it.movingMillis }) else "Ölçüm yok"
                    val distance = if (observed > 0) {
                        String.format(Locale.forLanguageTag("tr-TR"), "%.1f km", totals.sumOf { it.distanceMeters } / 1000)
                    } else "-"
                    Text(moving, style = MaterialTheme.typography.headlineMedium)
                    StatisticsMetricGrid(listOf(
                        "Mesafe" to distance,
                        "GPS ölçüm süresi" to statisticsDuration(observed),
                        "Ölçülemeyen süre" to statisticsDuration(totals.sumOf { it.unobservedMillis }),
                    ))
                } else {
                    Text(
                        if (journeys.isEmpty()) "İlk yolculuğunu kaydettiğinde istatistiklerin burada görünecek."
                        else if (completed.isEmpty()) "İstatistikler yolculuk tamamlandığında hazırlanır."
                        else state.analysisLabel,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text("${journeys.size} yolculuk · ${state.places.size} yer", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (onCompare != null) {
            item {
                OutlinedButton(onClick = onCompare, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Karşılaştır")
                }
            }
        }
        if (journeys.isNotEmpty()) {
            item {
                Text("Yolculuk seç", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
        }
        items(journeys, key = { it.id }) { journey ->
            Card(
                onClick = { onJourney(journey) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = journey.transport.badgeColor(),
                        contentColor = journey.transport.accentColor(),
                    ) {
                        Icon(journey.transport.icon(), contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(journey.title.ifBlank { journey.transport.label() }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("tr-TR")).format(Date(journey.startedAt)),
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Muted)
                }
            }
        }
    }
}
