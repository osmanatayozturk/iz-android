package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.*
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.PostJourneyAnalysis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ComparisonSelection(val id: String, val fingerprint: String)

/** Selection contains only identities; values always come from current matching prepared entries. */
@Composable
internal fun JourneyComparisonPanel(state: DiaryState) {
    val context = LocalContext.current.applicationContext
    val analysis = remember(context) { PostJourneyAnalysis.get(context) }
    val completed = state.journeys.filter { it.status == JourneyStatus.CONFIRMED && it.endedAt != null }
        .sortedByDescending { it.startedAt }
    val choices = completed.mapNotNull { journey ->
        state.preparedRecorded(journey)?.let { PreparedJourneyComparison(journey, it.fingerprint, it.stats) }
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
    LaunchedEffect(choices.map { it.journey.id to it.fingerprint }, firstSelection, secondSelection) {
        if (firstSelection != null && first == null) firstSelection = null
        if (secondSelection != null && (second == null || second.journey.id == first?.journey?.id)) secondSelection = null
    }
    val result = remember(first, second) {
        if (first != null && second != null && first.journey.id != second.journey.id)
            JourneyComparison.compare(first, second) else null
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Yolculukları karşılaştır", style = MaterialTheme.typography.titleLarge)
            if (completed.size < 2) {
                Text("Karşılaştırmak için iki tamamlanmış yolculuk gerekiyor.")
            } else {
                ComparisonJourneyChoice("İlk yolculuk", first,
                    choices.filter { it.journey.id != second?.journey?.id }) { choice ->
                    if (currentChoices.any { it.journey.id == choice.journey.id && it.fingerprint == choice.fingerprint } &&
                        choice.journey.id != secondSelection?.id)
                        firstSelection = ComparisonSelection(choice.journey.id, choice.fingerprint)
                }
                ComparisonJourneyChoice("İkinci yolculuk", second,
                    choices.filter { it.journey.id != first?.journey?.id }) { choice ->
                    if (currentChoices.any { it.journey.id == choice.journey.id && it.fingerprint == choice.fingerprint } &&
                        choice.journey.id != firstSelection?.id)
                        secondSelection = ComparisonSelection(choice.journey.id, choice.fingerprint)
                }
                if (firstSelection != null || secondSelection != null) {
                    TextButton(onClick = { firstSelection = null; secondSelection = null }) { Text("Seçimi temizle") }
                }
                if (first != null && second != null) {
                    if (result == null) Text("Bu yolculukların hazır verileri karşılaştırılamıyor.")
                    else JourneyComparisonValues(result)
                } else Text("Hazır analizi olan iki farklı yolculuk seç.", style = MaterialTheme.typography.bodySmall)
            }
            if (choices.size < completed.size) {
                Text(if (state.analysis.phase == AnalysisPhase.READY) "Hazır analiz verisi bekleniyor." else state.analysisLabel)
                Text("Ayrıntılar kayıt ve navigasyon bittikten sonra pil en az %20 olduğunda hazırlanır.",
                    style = MaterialTheme.typography.bodySmall)
                if (!state.analysis.pending && state.analysis.phase != AnalysisPhase.RUNNING) {
                    TextButton(onClick = { analysis.request() }) { Text("Ayrıntıları hazırla") }
                }
            }
        }
    }
}

@Composable
private fun ComparisonJourneyChoice(label: String, selected: PreparedJourneyComparison?,
    choices: List<PreparedJourneyComparison>, onSelect: (PreparedJourneyComparison) -> Unit) {
    var expanded by remember(choices.map { it.journey.id to it.fingerprint }) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(selected?.journey?.let(::comparisonJourneyName) ?: "Yolculuk seç")
                selected?.journey?.let { Text(comparisonJourneyDate(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { choice ->
                DropdownMenuItem(text = {
                    Column {
                        Text(comparisonJourneyName(choice.journey))
                        Text(comparisonJourneyDate(choice.journey), style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}

@Composable
private fun JourneyComparisonValues(result: JourneyComparisonResult) {
    if (result.differentTransport) Text("Yolculuklar farklı ulaşım türlerinde kaydedilmiş.")
    if (!result.routeIdentityVerified) Text("Rotanın aynı olduğu doğrulanmadı. Değerler ayrı ayrı gösterilir.")
    if (result.incompleteCoverage) Text("GPS kapsamı kısmi. Ölçülemeyen süre duraklama sayılmaz.")
    val first = result.first.stats
    val second = result.second.stats
    val values = listOf(
        Triple("Mesafe", comparisonDistance(first), comparisonDistance(second)),
        Triple("Toplam süre", comparisonDuration(first.elapsedMillis), comparisonDuration(second.elapsedMillis)),
        Triple("GPS ölçüm süresi", comparisonDuration(first.observedMillis), comparisonDuration(second.observedMillis)),
        Triple("Ölçülen hareket", comparisonDuration(first.movingMillis), comparisonDuration(second.movingMillis)),
        Triple("Ölçülen duraklama", comparisonDuration(first.stoppedMillis), comparisonDuration(second.stoppedMillis)),
        Triple("Ölçülemeyen süre", comparisonDuration(first.unobservedMillis), comparisonDuration(second.unobservedMillis)),
        Triple("Ortalama hız", comparisonSpeed(first.averageSpeedKmh), comparisonSpeed(second.averageSpeedKmh)),
        Triple("Hareketli ortalama", comparisonSpeed(first.movingAverageSpeedKmh), comparisonSpeed(second.movingAverageSpeedKmh)),
        Triple("Örneklenen en yüksek hız", comparisonSpeed(first.maxSpeedKmh), comparisonSpeed(second.maxSpeedKmh)),
    )
    values.forEach { (label, left, right) ->
        HorizontalDivider()
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("İlk: $left", Modifier.weight(1f))
            Text("İkinci: $right", Modifier.weight(1f))
        }
    }
    Text("Hızlar kullanılabilir GPS aralıklarından hesaplanır. Boşluklara değer eklenmez.",
        style = MaterialTheme.typography.bodySmall)
}

private fun comparisonJourneyName(journey: Journey): String = journey.title.ifBlank { journey.transport.label() }
private fun comparisonJourneyDate(journey: Journey): String =
    SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(journey.startedAt))
private fun comparisonSpeed(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f km/sa", it) } ?: "—"
internal fun comparisonDistance(stats: JourneyStats): String =
    stats.distanceMeters.takeIf { stats.observedMillis > 0 && it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", it / 1000) } ?: "—"
private fun comparisonDuration(millis: Long): String {
    if (millis < 0) return "—"
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "$seconds sn"
        seconds < 3600 -> "${seconds / 60} dk"
        else -> "${seconds / 3600} sa ${seconds % 3600 / 60} dk"
    }
}
