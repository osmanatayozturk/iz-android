package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.*
import org.iz.navigation.integration.averagePaceMinutesPerKm
import java.util.Locale
import kotlin.math.roundToLong

@Composable
internal fun JourneyStatsPanel(journey: Journey, points: List<TrackPoint>, now: Long) {
    val stats = remember(journey, points, now) { JourneyStatistics.calculate(journey, points, now) }
    fun speed(value: Double?) = value?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f km/sa", it) } ?: "—"
    fun time(value: Long): String {
        val seconds = value.coerceAtLeast(0) / 1000
        return if (seconds < 60) "$seconds sn" else if (seconds < 3600) "${seconds / 60} dk" else "${seconds / 3600} sa ${seconds % 3600 / 60} dk"
    }
    val values = buildList {
        add("Mesafe" to String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", stats.distanceMeters / 1000))
        add("Toplam süre" to time(stats.elapsedMillis))
        add("Ortalama hız" to speed(stats.averageSpeedKmh))
        add("En yüksek hız" to speed(stats.maxSpeedKmh))
        if (journey.transport == Transport.RUN) add("Ortalama tempo" to runningPaceLabel(averagePaceMinutesPerKm(stats.distanceMeters, stats.elapsedMillis)))
        add("Hareketli ortalama" to speed(stats.movingAverageSpeedKmh))
        add("Hareket süresi" to time(stats.movingMillis))
        add("Ölçülen duraklama" to time(stats.stoppedMillis))
        add("GPS ölçüm süresi" to time(stats.observedMillis))
        if (stats.unobservedMillis > 0) add("Ölçülemeyen süre" to time(stats.unobservedMillis))
        if (journey.transport.supportsSteps) add("Adım sayısı" to (journey.stepCount?.let { "$it adım" } ?: "Veri yok"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Yolculuk istatistikleri", style = MaterialTheme.typography.titleLarge)
        values.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), color = Color.White, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
                            Text(value, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("Hızlar güvenilir GPS noktaları arasındaki mesafeden hesaplanır. Ortalama hız toplam süreyi, hareketli ortalama yalnızca ölçülen hareket süresini kullanır. GPS boşlukları duraklama sayılmaz.", style = MaterialTheme.typography.bodySmall, color = Muted)
        if (journey.transport == Transport.RUN) Text("Ortalama tempo duraklamalar dahil toplam süreyi kullanır; yalnız kaydedilmiş GPS mesafesine dayanır.", style = MaterialTheme.typography.bodySmall, color = Muted)
        if (journey.transport.supportsSteps && journey.stepCount == null) Text("Adım verisi için telefonun adım sensörü ve fiziksel aktivite izni gerekir. Eski yolculukların adımları geriye dönük hesaplanmaz.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

internal fun runningPaceLabel(minutesPerKm: Double?): String {
    if (minutesPerKm == null || !minutesPerKm.isFinite() || minutesPerKm <= 0.0) return "—"
    val totalSeconds = minutesPerKm * 60.0
    if (!totalSeconds.isFinite() || totalSeconds >= Long.MAX_VALUE.toDouble()) return "—"
    val roundedSeconds = totalSeconds.roundToLong()
    return String.format(Locale.forLanguageTag("tr-TR"), "%d:%02d dk/km", roundedSeconds / 60, roundedSeconds % 60)
}
