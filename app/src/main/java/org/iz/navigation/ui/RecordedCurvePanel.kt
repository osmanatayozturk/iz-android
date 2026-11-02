package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.data.*
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.PostJourneyAnalysis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CurveReadKey(val journeyId: String, val fingerprint: String, val revision: String, val changed: Long)
private data class CurveRead(val key: CurveReadKey, val details: RecordedCurveDetails?, val failed: Boolean)

/** Reads only the disposable prepared GPS-curve asset, never raw points or lean output. */
@Composable
internal fun RecordedCurvePanel(journey: Journey, state: DiaryState) {
    val context = LocalContext.current.applicationContext
    val analysis = remember(context) { PostJourneyAnalysis.get(context) }
    val current = state.journeys.firstOrNull { it.id == journey.id && it.status == JourneyStatus.CONFIRMED }
    val prepared = current?.takeIf { it.endedAt != null }?.let { state.preparedRecorded(it) }
    val key = prepared?.let { CurveReadKey(journey.id, it.fingerprint, state.analysis.cacheRevision, state.analysis.changed) }
    // This request guard survives pending/READY transitions, preventing a failed-cache request loop.
    val requested = remember(journey.id) { mutableSetOf<String>() }
    var expanded by remember(key) { mutableStateOf(false) }
    var selectedId by remember(key) { mutableStateOf<String?>(null) }
    val loaded by produceState<CurveRead?>(null, key, analysis) {
        if (key == null) {
            value = null
            return@produceState
        }
        val read = withContext(Dispatchers.IO) { runCatching { analysis.cache.curves(key.journeyId, key.fingerprint) } }
        val valid = read.getOrNull()?.takeIf { it.matchesRead(key) }
        value = CurveRead(key, valid, read.isFailure)
        if (valid == null && requested.add(key.fingerprint)) analysis.request()
    }
    // produceState retains its old value across a key change; never render it for a new owner/revision.
    val details = loaded?.takeIf { it.key == key }?.details
    val selected = details?.curves?.firstOrNull { it.id == selectedId }
    LaunchedEffect(details) {
        if (selectedId != null && details?.curves?.none { it.id == selectedId } != false) selectedId = null
    }
    if (current == null) return

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GPS viraj ayrıntıları", style = MaterialTheme.typography.titleLarge)
            Text("Kaydedilmiş GPS noktalarından hazırlanır. Konum boşlukları ayrıntıya katılmaz.",
                style = MaterialTheme.typography.bodySmall)
            if (details == null) {
                Text(when {
                    current.endedAt == null -> "Yolculuk bittikten sonra hazırlanacak."
                    state.analysis.phase == AnalysisPhase.READY -> "Viraj ayrıntıları bekleniyor."
                    else -> state.analysisLabel
                })
                if (loaded?.key == key && loaded?.failed == true) Text("Hazır ayrıntılar okunamadı.")
                Text("Ayrıntılar kayıt ve navigasyon bittikten sonra pil en az %20 olduğunda hazırlanır.",
                    style = MaterialTheme.typography.bodySmall)
                if (current.endedAt != null && !state.analysis.pending && state.analysis.phase != AnalysisPhase.RUNNING) {
                    TextButton(onClick = { analysis.request() }) { Text("Ayrıntıları hazırla") }
                }
            } else {
                Text("Kaynak: Kaydedilmiş GPS noktaları", style = MaterialTheme.typography.bodyMedium)
                Text("${details.usablePointCount} kullanılabilir GPS örneği · ${details.excludedIntervalCount} aralık viraj çözümlemesine alınmadı",
                    style = MaterialTheme.typography.bodySmall)
                if (details.truncated) Text("Ayrıntılar kısmi. Veri sınırı nedeniyle bazı bölümler gösterilmez.", style = MaterialTheme.typography.bodySmall)
                if (details.curves.isEmpty()) {
                    Text("Bu kayıtta gösterilecek viraj ayrıntısı yok.")
                } else {
                    TextButton(onClick = { expanded = !expanded; if (!expanded) selectedId = null }) {
                        Text(if (expanded) "Viraj ayrıntılarını gizle" else "Virajları göster (${details.curves.size})")
                    }
                    if (expanded) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(details.curves, key = { it.id }) { curve ->
                                OutlinedCard(onClick = { selectedId = curve.id }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(curveDirection(curve.direction), style = MaterialTheme.typography.titleSmall)
                                        Text("Başlangıç: ${curveTime(curve.start.at)}", style = MaterialTheme.typography.bodySmall)
                                        Text("Süre: ${curveDuration(curve.end.at - curve.start.at)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        selected?.let { curve ->
                            RecordedCurveDetailValues(curve)
                            TextButton(onClick = { selectedId = null }) { Text("Seçili ayrıntıyı kapat") }
                        }
                    }
                }
            }
        }
    }
}

private fun RecordedCurveDetails.matchesRead(key: CurveReadKey): Boolean =
    journeyId == key.journeyId && sourceFingerprint == key.fingerprint &&
        algorithmVersion == RecordedCurves.ALGORITHM_VERSION && usablePointCount >= 0 && excludedIntervalCount >= 0 &&
        curves.map { it.id }.distinct().size == curves.size &&
        curves.all { it.id.isNotBlank() && it.journeyId == key.journeyId && it.sourceFingerprint == key.fingerprint &&
            it.algorithmVersion == algorithmVersion && it.start.pointId.isNotBlank() && it.end.pointId.isNotBlank() &&
            it.pointCount >= 2 && it.start.at >= 0 && it.end.at >= it.start.at &&
            it.recordedDistanceMeters.isFinite() && it.recordedDistanceMeters >= 0.0 }

@Composable
private fun RecordedCurveDetailValues(curve: RecordedCurveDetail) {
    HorizontalDivider()
    Text("Seçili viraj: ${curveDirection(curve.direction)}", style = MaterialTheme.typography.titleMedium)
    Text("Başlangıç: ${curveTime(curve.start.at)}")
    Text("Başlangıç konumu: ${curveLocation(curve.start)}", style = MaterialTheme.typography.bodySmall)
    Text("Bitiş: ${curveTime(curve.end.at)}")
    Text("Bitiş konumu: ${curveLocation(curve.end)}", style = MaterialTheme.typography.bodySmall)
    Text("Süre: ${curveDuration(curve.end.at - curve.start.at)}")
    Text("Kaydedilen mesafe: ${curveDistance(curve.recordedDistanceMeters)}")
    Text("Kullanılan GPS örneği: ${curve.pointCount}")
    Text("Örneklenen en düşük hız: ${curveSpeed(curve.sampledMinSpeedKmh)}")
    Text("Örneklenen en yüksek hız: ${curveSpeed(curve.sampledMaxSpeedKmh)}")
    Text("Hızlar bu kayıttaki kullanılabilir örneklerden gelir.", style = MaterialTheme.typography.bodySmall)
}

private fun curveDirection(direction: RecordedCurveDirection): String = when (direction) {
    RecordedCurveDirection.LEFT -> "Sol viraj"
    RecordedCurveDirection.RIGHT -> "Sağ viraj"
}
private fun curveTime(at: Long): String =
    SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR")).format(Date(at))
private fun curveLocation(point: RecordedCurveEndpoint): String =
    if (point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
        point.longitude.isFinite() && point.longitude in -180.0..180.0)
        String.format(Locale.ROOT, "Enlem %.5f · Boylam %.5f", point.latitude, point.longitude)
    else "Konum bilgisi kullanılamıyor."
private fun curveDistance(value: Double): String = value.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f m", it) } ?: "—"
private fun curveSpeed(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f km/sa", it) } ?: "—"
private fun curveDuration(millis: Long): String {
    if (millis < 0) return "—"
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "$seconds sn"
        seconds < 3600 -> "${seconds / 60} dk ${seconds % 60} sn"
        else -> "${seconds / 3600} sa ${seconds % 3600 / 60} dk"
    }
}
