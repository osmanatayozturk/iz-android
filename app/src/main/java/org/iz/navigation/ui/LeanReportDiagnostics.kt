package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.lean.LeanGnssDiagnostics
import org.iz.navigation.lean.LeanLiveDecisionCode
import org.iz.navigation.lean.LeanLiveDecisionSummary
import org.iz.navigation.lean.LeanReportSession
import org.iz.navigation.lean.LeanStreamTiming
import org.iz.navigation.lean.leanGnssIssueLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LeanReportDiagnostics(
    sessions: List<LeanReportSession>,
    mountLabel: String,
    showLiveDecisions: Boolean = false,
) {
    if (sessions.isEmpty()) {
        if (showLiveDecisions) LeanLiveDecisionReadout(null)
        Text("Sensör ve GPS ayrıntıları bilinmiyor.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val (liveSummaries, sensorSessions) = sessions.partition { it.liveOnly }
    if (sensorSessions.isNotEmpty()) {
        Text("İlk ve son örnek oturum başına göre gösterilir. En uzun aralık iki ardışık örnek arasındadır. Başlangıç ve bitiş boşlukları ayrıca gösterilir.",
            style = MaterialTheme.typography.bodySmall)
    }
    val locale = Locale.forLanguageTag("tr-TR")
    val orderedSessions = sensorSessions.sortedBy { it.startedAtUtc } + liveSummaries.sortedBy { it.startedAtUtc }
    orderedSessions.forEach { session ->
        key(session.sessionId) {
            HorizontalDivider()
            Column(Modifier.testTag("lean-report-session-${session.sessionId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (session.liveOnly) {
                    LeanLiveDecisionReadout(session.liveDecisions)
                } else {
                    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
                        .format(Date(session.startedAtUtc))
                    Text("$mountLabel · $time", style = MaterialTheme.typography.titleSmall)
                    if (showLiveDecisions && (session.liveDecisions != null || liveSummaries.isEmpty())) {
                        LeanLiveDecisionReadout(session.liveDecisions)
                    }
                    val timing = session.sensorTiming
                    if (timing == null) Text("Sensör ayrıntıları bilinmiyor.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("Oturum süresi ${leanDiagnosticDuration(timing.durationNanos)}", style = MaterialTheme.typography.bodySmall)
                        LeanSensorTimingReadout("İvmeölçer", timing.durationNanos, timing.accelerometer)
                        LeanSensorTimingReadout("Jiroskop", timing.durationNanos, timing.gyroscope)
                    }
                    LeanGnssDiagnosticReadout(session.gnssDiagnostics)
                }
            }
        }
    }
}

@Composable
private fun LeanLiveDecisionReadout(summary: LeanLiveDecisionSummary?) {
    Text("Canlı yatış kadranı", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
    if (summary == null) {
        Text("Canlı kadran bilgisi bilinmiyor.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val counts = summary.counts
    if (counts.isEmpty()) {
        val status = if (summary.dialOpened) "Kadran açıldı, karar kaydı yok." else "Kadran açılmadı."
        Text(status, style = MaterialTheme.typography.bodySmall)
        return
    }
    val accepted = listOf(LeanLiveDecisionCode.ACCEPTED_MEASURED, LeanLiveDecisionCode.ACCEPTED_ESTIMATED)
    accepted.forEach { code ->
        Text("${liveDecisionLabel(code)} · ${summary.count(code)}", style = MaterialTheme.typography.bodySmall)
    }
    val rejected = counts.entries.filterNot { it.key in accepted }.sortedByDescending { it.value }
    if (rejected.isEmpty()) {
        Text("Açının hesaplanamadığı bir durum kaydedilmedi.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Açının hesaplanamadığı durumlar", style = MaterialTheme.typography.labelLarge)
        rejected.forEach { (code, count) ->
            Text("${liveDecisionLabel(code)} · $count kez", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun liveDecisionLabel(code: LeanLiveDecisionCode): String = when (code) {
    LeanLiveDecisionCode.ACCEPTED_MEASURED -> "Geçerli ölçüm"
    LeanLiveDecisionCode.ACCEPTED_ESTIMATED -> "Geçerli tahmin"
    LeanLiveDecisionCode.GNSS_NEVER_RECEIVED -> "GPS konumu hiç alınmadı"
    LeanLiveDecisionCode.GNSS_STALE -> "GPS konumu güncellenmedi"
    LeanLiveDecisionCode.SENSOR_UNAVAILABLE -> "Telefonun hareket sensörleri kullanılamadı"
    LeanLiveDecisionCode.SENSOR_STREAM_GAP -> "Hareket sensörü verisi kesildi"
    LeanLiveDecisionCode.INVALID_FIX -> "GPS konumu geçersizdi"
    LeanLiveDecisionCode.WAITING_FOR_PAIR -> "İkinci uygun GPS konumu bekleniyordu"
    LeanLiveDecisionCode.INVALID_CALIBRATION -> "Gidon kalibrasyonu geçerli değildi"
    LeanLiveDecisionCode.GNSS_BATCH -> "Birden fazla GPS konumu birlikte geldi"
    LeanLiveDecisionCode.GNSS_NO_SINGLE_FIX_RECEIPT -> "GPS konumunun alınış zamanı doğrulanamadı"
    LeanLiveDecisionCode.GNSS_RECEIPT_STALE -> "GPS konumu gecikmeli geldi"
    LeanLiveDecisionCode.GNSS_RECEIPT_CLOCK -> "GPS konumlarının alınış zamanı tutarsızdı"
    LeanLiveDecisionCode.GNSS_RECEIPT_JITTER -> "GPS konumlarının alınış aralıkları çok değişkendi"
    LeanLiveDecisionCode.GNSS_SOURCE_UNSUPPORTED -> "GPS konum kaynağı desteklenmedi"
    LeanLiveDecisionCode.GNSS_SOURCE_CHANGED -> "GPS konum kaynağı değişti"
    LeanLiveDecisionCode.GNSS_CLOCK -> "GPS konum zamanları birbiriyle uyuşmadı"
    LeanLiveDecisionCode.GNSS_TIME_UNCERTAIN -> "GPS konum zamanının kesinliği doğrulanamadı"
    LeanLiveDecisionCode.GNSS_FIX_CONTINUITY -> "Ardışık GPS konumlarının zamanları tutarsızdı"
    LeanLiveDecisionCode.GNSS_QUALITY -> "GPS verisi yeterince güvenilir değildi"
    LeanLiveDecisionCode.GNSS_MOTION -> "GPS hareket verisi yatışı hesaplamak için yeterli değildi"
    LeanLiveDecisionCode.SENSOR_QUALITY -> "Hareket sensörü verisi yeterince güvenilir değildi"
    LeanLiveDecisionCode.SENSOR_DISAGREEMENT -> "Hareket sensörleri GPS hareketiyle uyuşmadı"
    LeanLiveDecisionCode.TURN_SUPPORT -> "Dönüş için yeterli veri oluşmadı"
    LeanLiveDecisionCode.TURN_DIRECTION_CHANGED -> "Dönüş yönü değişti"
    else -> leanGnssIssueLabel(code.name)
}

@Composable
private fun LeanSensorTimingReadout(label: String, durationNanos: Long, timing: LeanStreamTiming) {
    val firstOffset = timing.firstOffsetNanos?.takeIf { it in 0..durationNanos }
    val lastOffset = timing.lastOffsetNanos?.takeIf { it in 0..durationNanos }
    val noSample = if (timing.count == 0L) "Örnek yok" else "Bilinmiyor"
    val first = firstOffset?.let(::leanDiagnosticDuration) ?: noSample
    val last = lastOffset?.let(::leanDiagnosticDuration) ?: noSample
    val span = when {
        timing.count <= 1L -> "Aralık için veri yok"
        firstOffset != null && lastOffset != null && lastOffset >= firstOffset -> leanDiagnosticDuration(lastOffset - firstOffset)
        else -> "Bilinmiyor"
    }
    val endGap = lastOffset?.let { leanDiagnosticDuration(durationNanos - it) } ?: noSample
    val longest = timing.longestIntervalNanos?.let(::leanDiagnosticDuration)
        ?: if (timing.count <= 1L) "Aralık için veri yok" else "Bilinmiyor"
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text("Kaydedilen örnek · ${timing.count} örnek", style = MaterialTheme.typography.bodySmall)
    Text("İlk örnek · $first", style = MaterialTheme.typography.bodySmall)
    Text("Son örnek · $last", style = MaterialTheme.typography.bodySmall)
    Text("İlk–son örnek kapsamı · $span", style = MaterialTheme.typography.bodySmall)
    Text("Örnekler arasındaki en uzun süre · $longest", style = MaterialTheme.typography.bodySmall)
    Text("Başlangıçta örneksiz süre · $first", style = MaterialTheme.typography.bodySmall)
    Text("Bitişte örneksiz süre · $endGap", style = MaterialTheme.typography.bodySmall)
    if (timing.count == 0L) {
        Text("Oturum boyunca örnek yok · ${leanDiagnosticDuration(durationNanos)}",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LeanGnssDiagnosticReadout(diagnostics: LeanGnssDiagnostics?) {
    Text("GPS ölçüm ayrıntıları", style = MaterialTheme.typography.labelLarge)
    when {
        diagnostics == null -> Text("Bilinmiyor", style = MaterialTheme.typography.bodySmall)
        diagnostics.issues.isEmpty() -> Text("Kaydedilen GPS gerekçesi yok.", style = MaterialTheme.typography.bodySmall)
        else -> {
            diagnostics.issues.forEach { issue ->
                val isPair = issue.unit == "pair"
                val unit = if (isPair) "ölçüm aralığı" else "ölçüm"
                val duration = if (isPair || issue.durationNanos > 0L) {
                    leanDiagnosticDuration(issue.durationNanos)
                } else {
                    "süre ölçülmedi"
                }
                Text("${leanGnssIssueLabel(issue.code)} · ${issue.count} $unit · $duration",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Bir ölçüm aralığı birden fazla gerekçede yer alabilir.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun leanDiagnosticDuration(nanos: Long): String {
    val locale = Locale.forLanguageTag("tr-TR")
    return if (nanos < 1_000_000_000L) String.format(locale, "%.1f ms", nanos / 1_000_000.0)
    else String.format(locale, "%.2f sn", nanos / 1_000_000_000.0)
}
