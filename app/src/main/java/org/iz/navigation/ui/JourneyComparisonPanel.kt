package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.*
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.PostJourneyAnalysis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class ComparisonSelection(val id: String, val fingerprint: String)

/** Selection contains only identities; values always come from current matching prepared entries. */
@Composable
internal fun JourneyComparisonPanel(state: DiaryState) {
    val context = LocalContext.current.applicationContext
    val analysis = remember(context) { PostJourneyAnalysis.get(context) }
    val completed = remember(state.journeys) {
        state.journeys.filter { it.status == JourneyStatus.CONFIRMED && it.endedAt != null }
            .sortedByDescending { it.startedAt }
    }
    val choices = remember(completed, state.analysis) {
        completed.mapNotNull { journey ->
            state.preparedRecorded(journey)?.let { PreparedJourneyComparison(journey, it.fingerprint, it.stats) }
        }
    }
    var firstSelection by remember(state.analysis.cacheRevision, state.analysis.changed, state.analysis.pending) {
        mutableStateOf<ComparisonSelection?>(null)
    }
    var secondSelection by remember(state.analysis.cacheRevision, state.analysis.changed, state.analysis.pending) {
        mutableStateOf<ComparisonSelection?>(null)
    }
    val currentChoices by rememberUpdatedState(choices)
    val first = choices.firstOrNull { it.journey.id == firstSelection?.id && it.fingerprint == firstSelection?.fingerprint }
    val second = choices.firstOrNull { it.journey.id == secondSelection?.id && it.fingerprint == secondSelection?.fingerprint }
    // Render guards above hide stale values immediately, before this cleanup effect executes.
    LaunchedEffect(choices, firstSelection, secondSelection) {
        if (firstSelection != null && first == null) firstSelection = null
        if (secondSelection != null && (second == null || second.journey.id == first?.journey?.id)) secondSelection = null
    }
    val result = remember(first, second) {
        if (first != null && second != null && first.journey.id != second.journey.id)
            JourneyComparison.compare(first, second) else null
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("İki yolculuğu karşılaştır", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        StatisticsSurface(emphasized = true) {
            if (completed.size < 2) {
                Text("Karşılaştırmak için iki tamamlanmış yolculuk gerekiyor.")
            } else {
                ComparisonJourneyChoice(
                    "Birinci yolculuk", first, choices.filter { it.journey.id != second?.journey?.id },
                ) { choice ->
                    if (currentChoices.any { it.journey.id == choice.journey.id && it.fingerprint == choice.fingerprint } &&
                        choice.journey.id != secondSelection?.id
                    ) firstSelection = ComparisonSelection(choice.journey.id, choice.fingerprint)
                }
                ComparisonJourneyChoice(
                    "İkinci yolculuk", second, choices.filter { it.journey.id != first?.journey?.id },
                ) { choice ->
                    if (currentChoices.any { it.journey.id == choice.journey.id && it.fingerprint == choice.fingerprint } &&
                        choice.journey.id != firstSelection?.id
                    ) secondSelection = ComparisonSelection(choice.journey.id, choice.fingerprint)
                }
                if (firstSelection != null || secondSelection != null) {
                    TextButton(onClick = {
                        firstSelection = null
                        secondSelection = null
                    }) { Text("Seçimi temizle") }
                }
                if (first == null || second == null) {
                    Text("Hazır analizi olan iki farklı yolculuk seç.", style = MaterialTheme.typography.bodySmall)
                } else if (result == null) {
                    Text("Bu yolculukların hazır verileri karşılaştırılamıyor.")
                }
            }
        }

        if (choices.size < completed.size) {
            StatisticsSurface {
                Text(if (state.analysis.phase == AnalysisPhase.READY) "Hazır analiz verisi bekleniyor." else state.analysisLabel)
                Text(
                    "Ayrıntılar kayıt ve navigasyon bittikten sonra pil en az %20 olduğunda hazırlanır.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                if (!state.analysis.pending && state.analysis.phase != AnalysisPhase.RUNNING) {
                    TextButton(onClick = { analysis.request() }) { Text("Ayrıntıları hazırla") }
                }
            }
        }
        result?.let { JourneyComparisonValues(it) }
    }
}

@Composable
private fun ComparisonJourneyChoice(
    label: String,
    selected: PreparedJourneyComparison?,
    choices: List<PreparedJourneyComparison>,
    onSelect: (PreparedJourneyComparison) -> Unit,
) {
    var expanded by remember(choices.map { it.journey.id to it.fingerprint }) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedCard(
            onClick = { expanded = true }, enabled = choices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(selected?.journey?.let(::comparisonJourneyName) ?: "Yolculuk seç")
                    selected?.journey?.let { Text(comparisonJourneyDate(it), style = MaterialTheme.typography.bodySmall) }
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null)
            }
        }
        DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(text = {
                    Column {
                        Text(comparisonJourneyName(choice.journey))
                        Text(comparisonJourneyDate(choice.journey), style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = {
                    expanded = false
                    onSelect(choice)
                })
            }
        }
    }
}

@Composable
private fun JourneyComparisonValues(result: JourneyComparisonResult) {
    val first = result.first.stats
    val second = result.second.stats
    val distanceDifference = if (first.observedMillis > 0 && second.observedMillis > 0) {
        val km = (second.distanceMeters - first.distanceMeters) / 1000
        String.format(Locale.forLanguageTag("tr-TR"), "%+.2f km", km)
    } else "-"
    val timeDifference = second.elapsedMillis - first.elapsedMillis
    val sign = when {
        timeDifference > 0 -> "+"
        timeDifference < 0 -> "−"
        else -> ""
    }
    StatisticsSurface(emphasized = true) {
        Text("Süre ve mesafe farkı", style = MaterialTheme.typography.titleMedium)
        Text("İkinci yolculuktan birincisi çıkarılır.", style = MaterialTheme.typography.bodySmall)
        StatisticsMetricGrid(listOf(
            "Toplam süre" to (sign + statisticsDuration(abs(timeDifference))),
            "Ölçülen mesafe" to distanceDifference,
        ))
    }
    StatisticsSurface {
        Text("Kapsam ve rota eşleşmesi", style = MaterialTheme.typography.titleMedium)
        if (result.differentTransport) Text("Yolculuklar farklı ulaşım türlerinde kaydedilmiş.")
        if (!result.routeIdentityVerified) Text("Rota eşleşmesi doğrulanmadı. Değerler iki kayıt için ayrı gösterilir.")
        if (result.incompleteCoverage) Text("GPS kapsamı kısmi. Ölçülemeyen süre duraklama sayılmaz.")
        Text(
            "Hızlar kullanılabilir GPS aralıklarından hesaplanır.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
    }
    val values = listOf(
        Triple("Mesafe", comparisonDistance(first), comparisonDistance(second)),
        Triple("Toplam süre", statisticsDuration(first.elapsedMillis), statisticsDuration(second.elapsedMillis)),
        Triple("GPS ölçüm süresi", statisticsDuration(first.observedMillis), statisticsDuration(second.observedMillis)),
        Triple("Ölçülen hareket", statisticsDuration(first.movingMillis), statisticsDuration(second.movingMillis)),
        Triple("Ölçülen duraklama", statisticsDuration(first.stoppedMillis), statisticsDuration(second.stoppedMillis)),
        Triple("Ölçülemeyen süre", statisticsDuration(first.unobservedMillis), statisticsDuration(second.unobservedMillis)),
        Triple("Ortalama hız", statisticsSpeed(first.averageSpeedKmh), statisticsSpeed(second.averageSpeedKmh)),
        Triple("Hareketli ortalama", statisticsSpeed(first.movingAverageSpeedKmh), statisticsSpeed(second.movingAverageSpeedKmh)),
        Triple("Örneklenen en yüksek hız", statisticsSpeed(first.maxSpeedKmh), statisticsSpeed(second.maxSpeedKmh)),
    )
    StatisticsSurface {
        values.forEachIndexed { index, (label, left, right) ->
            if (index > 0) HorizontalDivider()
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComparisonValue("Birinci", left, Modifier.weight(1f))
                ComparisonValue("İkinci", right, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ComparisonValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun comparisonJourneyName(journey: Journey): String = journey.title.ifBlank { journey.transport.label() }

private fun comparisonJourneyDate(journey: Journey): String =
    SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(journey.startedAt))

internal fun comparisonDistance(stats: JourneyStats): String =
    stats.distanceMeters.takeIf { stats.observedMillis > 0 && it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", it / 1000) } ?: "-"
