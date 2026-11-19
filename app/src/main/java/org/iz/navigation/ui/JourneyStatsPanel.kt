package org.iz.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.data.*
import org.iz.navigation.integration.averagePaceMinutesPerKm
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.PostJourneyAnalysis
import java.util.Locale
import kotlin.math.roundToLong

/** Legacy raw-input call sites also consume only prepared results; they cannot bypass the gate. */
@Composable
internal fun JourneyStatsPanel(journey: Journey, points: List<TrackPoint>, now: Long) {
    val application = LocalContext.current.applicationContext
    val service = remember(application) { PostJourneyAnalysis.get(application) }
    val analysis by service.state.collectAsStateWithLifecycle()
    val label = if (analysis.phase == AnalysisPhase.READY) "Ayrıntılar hazırlanıyor" else analysis.label
    JourneyStatsPanel(journey, analysis.ready(journey.id)?.takeIf { it.matches(journey) }?.stats, label)
}

@Composable
internal fun JourneyStatsPanel(journey: Journey, stats: JourneyStats?, waitingLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Yolculuk istatistikleri", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (stats == null) {
            StatisticsSurface {
                Text(waitingLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Konum kayıtların korunuyor. Ayrıntılar kayıt ve navigasyon bittikten sonra pil en az %20 olduğunda hazırlanır.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        } else {
            StatisticsSurface(emphasized = true) {
                Text("Hareket ve durma", style = MaterialTheme.typography.titleMedium)
                Text("Ölçülen hareket süresi", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (stats.observedMillis > 0) statisticsDuration(stats.movingMillis) else "Ölçüm yok",
                    style = MaterialTheme.typography.headlineMedium,
                )
                JourneyCoverageBar(stats)
                StatisticsMetricGrid(listOf(
                    "Ölçülen duraklama" to statisticsDuration(stats.stoppedMillis),
                    "GPS ölçüm süresi" to statisticsDuration(stats.observedMillis),
                    "Ölçülemeyen süre" to statisticsDuration(stats.unobservedMillis),
                ))
            }
            StatisticsSurface {
                Text("Hız ve süre", style = MaterialTheme.typography.titleMedium)
                val values = buildList {
                    add("Mesafe" to comparisonDistance(stats))
                    add("Toplam süre" to statisticsDuration(stats.elapsedMillis))
                    add("Ortalama hız" to statisticsSpeed(stats.averageSpeedKmh))
                    add("Örneklenen en yüksek hız" to statisticsSpeed(stats.maxSpeedKmh))
                    if (journey.transport == Transport.RUN) {
                        add("Ortalama tempo" to runningPaceLabel(averagePaceMinutesPerKm(stats.distanceMeters, stats.elapsedMillis)))
                    }
                    add("Hareketli ortalama" to statisticsSpeed(stats.movingAverageSpeedKmh))
                    if (journey.transport.supportsSteps) {
                        add("Adım sayısı" to (journey.stepCount?.let { "$it adım" } ?: "Veri yok"))
                    }
                }
                StatisticsMetricGrid(values)
                Text(
                    "Hızlar güvenilir GPS noktaları arasındaki mesafeden hesaplanır. Ortalama hız toplam süreyi, hareketli ortalama yalnızca ölçülen hareket süresini kullanır. GPS boşlukları duraklama sayılmaz.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                if (journey.transport == Transport.RUN) {
                    Text(
                        "Ortalama tempo duraklamalar dahil toplam süreyi kullanır ve kaydedilmiş GPS mesafesine dayanır.",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                if (journey.transport.supportsSteps && journey.stepCount == null) {
                    Text(
                        "Adım verisi için telefonun adım sensörü ve fiziksel aktivite izni gerekir. Eski yolculukların adımları geriye dönük hesaplanmaz.",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun JourneyCoverageBar(stats: JourneyStats) {
    val colors = MaterialTheme.colorScheme
    val segments = listOf(
        stats.movingMillis to colors.primary,
        stats.stoppedMillis to colors.outline,
        stats.unobservedMillis to colors.outlineVariant,
    ).filter { (duration, _) -> duration > 0 }
    if (segments.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { (duration, color) ->
            Box(Modifier.weight(duration.toFloat()).fillMaxHeight().background(color))
        }
    }
}

internal fun runningPaceLabel(minutesPerKm: Double?): String {
    if (minutesPerKm == null || !minutesPerKm.isFinite() || minutesPerKm <= 0.0) return "-"
    val totalSeconds = minutesPerKm * 60.0
    if (!totalSeconds.isFinite() || totalSeconds >= Long.MAX_VALUE.toDouble()) return "-"
    val roundedSeconds = totalSeconds.roundToLong()
    return String.format(Locale.forLanguageTag("tr-TR"), "%d:%02d dk/km", roundedSeconds / 60, roundedSeconds % 60)
}
