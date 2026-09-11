package com.atay.iz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.atay.iz.tracking.TrackingService
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.atay.iz.tracking.DetectionRegistrationState
import com.atay.iz.tracking.TrackerSettings
import com.atay.iz.tracking.TrackingController
import java.text.DateFormat
import java.util.Date

/** The parent recomposes this with its normal settings refresh tick. */
@Composable
fun TrackingDiagnosticsPanel(settings: TrackerSettings, refresh: Int, onRetry: () -> Unit) {
    val tick by produceState(0L) { while (true) { delay(1000); value = value + 1 } }
    val cadence = remember(tick, refresh) {
        Triple(TrackingService.currentLocationIntervalMillis, TrackingService.desiredLocationIntervalMillis, TrackingService.locationRetryPending)
    }
    val delivering = remember(tick, refresh) { TrackingService.locationDeliveryActive }
    val context = LocalContext.current
    val state = if (settings.enabled) settings.registrationState else DetectionRegistrationState.OFF
    val title = when (state) {
        DetectionRegistrationState.OFF -> "Otomatik başlangıç kapalı"
        DetectionRegistrationState.REGISTERING -> "Hareket algılama bağlanıyor"
        DetectionRegistrationState.READY -> "Hareket geçişi bekleniyor"
        DetectionRegistrationState.PERMISSION_REQUIRED -> "Otomatik başlangıç için izin gerekiyor"
        DetectionRegistrationState.RETRY_PENDING -> "Hareket algılama bağlantısı yeniden denenecek"
    }
    fun time(value: Long) = if (value > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value)) else "Henüz yok"
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(if (delivering) "GPS bağlantısı etkin" else "GPS bağlantısı kapalı", style = MaterialTheme.typography.bodySmall)
        Text("GPS aralığı: ${cadence.first?.let { "${it / 1000} sn" } ?: "—"} · İstenen: ${cadence.second?.let { "${it / 1000} sn" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
        if (cadence.third) Text("Yeni GPS aralığı yeniden denenecek; mevcut bağlantı korunuyor.", style = MaterialTheme.typography.bodySmall)
        if (settings.enabled) TrackingController.detectionPermissionError(context)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        settings.registrationError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Son bağlantı denemesi: ${time(settings.lastRegistrationAttemptAt)}", style = MaterialTheme.typography.bodySmall)
        Text("Son başarılı bağlantı: ${time(settings.lastRegistrationAt)}", style = MaterialTheme.typography.bodySmall)
        Text("Son kabul edilen konum: ${time(settings.lastAcceptedLocationAt)}", style = MaterialTheme.typography.bodySmall)
        if (settings.enabled) OutlinedButton(onClick = onRetry, enabled = state != DetectionRegistrationState.REGISTERING) {
            Text("Algılamayı yeniden bağla")
        }
    }
}

