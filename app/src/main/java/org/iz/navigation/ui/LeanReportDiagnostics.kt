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
import androidx.compose.ui.unit.dp
import org.iz.navigation.lean.LeanGnssDiagnostics
import org.iz.navigation.lean.LeanReportSession
import org.iz.navigation.lean.LeanStreamTiming
import org.iz.navigation.lean.leanGnssIssueLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LeanReportDiagnostics(sessions: List<LeanReportSession>, mountLabel: String) {
    if (sessions.isEmpty()) {
        Text("Sensör ve GPS ayrıntıları bilinmiyor.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val locale = Locale.forLanguageTag("tr-TR")
    Text("İlk ve son örnek oturum başına göre gösterilir. En uzun aralık iki ardışık örnek arasındadır. Başlangıç ve bitiş boşlukları ayrıca gösterilir.",
        style = MaterialTheme.typography.bodySmall)
    sessions.sortedBy { it.startedAtUtc }.forEach { session ->
        key(session.sessionId) {
            HorizontalDivider()
            Column(Modifier.testTag("lean-report-session-${session.sessionId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
                    .format(Date(session.startedAtUtc))
                Text("$mountLabel · $time", style = MaterialTheme.typography.titleSmall)
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
