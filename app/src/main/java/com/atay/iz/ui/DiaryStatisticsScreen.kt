package com.atay.iz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atay.iz.data.*
import java.util.Locale

@Composable
internal fun DiaryStatisticsScreen(state: DiaryState, now: Long, onJourney: (Journey) -> Unit) {
    val journeys = state.journeys.filter { it.status == JourneyStatus.CONFIRMED }.sortedByDescending { it.startedAt }
    val totals = remember(journeys, state.points, now) {
        journeys.map { JourneyStatistics.calculate(it, state.points.filter { p -> p.journeyId == it.id }, now) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("İstatistikler", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text("${journeys.size} yolculuk · ${state.places.size} yer", style = MaterialTheme.typography.titleMedium)
            Text(String.format(Locale.forLanguageTag("tr-TR"), "%.1f km kaydedildi", totals.sumOf { it.distanceMeters } / 1000),
                style = MaterialTheme.typography.headlineSmall)
            Text("${totals.sumOf { it.movingMillis } / 60_000} dk ölçülen hareket · ${totals.sumOf { it.unobservedMillis } / 60_000} dk GPS boşluğu",
                style = MaterialTheme.typography.bodyMedium)
            Text("Ayrıntılı hız, süre ve sağlık verileri için bir yolculuğu seç.", style = MaterialTheme.typography.bodySmall)
        }
        if (journeys.isEmpty()) item { Text("İlk yolculuğunu kaydettiğinde istatistiklerin burada görünecek.") }
        items(journeys, key = { it.id }) { journey ->
            OutlinedCard(onClick = { onJourney(journey) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(journey.title.ifBlank { journey.transport.label() }, style = MaterialTheme.typography.titleMedium)
                    Text(java.text.SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("tr-TR")).format(java.util.Date(journey.startedAt)))
                }
            }
        }
    }
}
