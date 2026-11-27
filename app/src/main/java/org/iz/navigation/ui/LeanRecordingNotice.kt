package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iz.navigation.lean.LeanCaptureSettings
import org.iz.navigation.lean.LeanMount
import org.iz.navigation.lean.LeanRecordingPhase
import org.iz.navigation.lean.LeanRecordingStatus
import org.iz.navigation.lean.LeanSettingsStore
import org.iz.navigation.lean.LiveLeanCapture
import org.iz.navigation.lean.liveReference

@Composable
internal fun ActiveLeanRecordingNotice(journeyId: String, settings: LeanCaptureSettings,
    status: LeanRecordingStatus?, settingsStore: LeanSettingsStore) {
    val scope = rememberCoroutineScope()
    var saving by remember(journeyId) { mutableStateOf(false) }
    var saveFailed by remember(journeyId) { mutableStateOf(false) }
    val reference = settings.liveReference()
    val current = status?.takeIf { it.journeyId == journeyId && it.calibrationEpoch == reference?.epoch }
    val label = when {
        !settings.enabled -> "Yatış kaydı kapalı"
        reference == null -> "Yatış kaydı için montaj kalibrasyonu gerekli"
        current?.phase == LeanRecordingPhase.RECORDING -> "Yatış sensörleri kaydediliyor"
        current?.phase == LeanRecordingPhase.SENSOR_UNAVAILABLE ->
            current.sensorFailure?.message ?: "Yatış sensörleri kullanılamıyor"
        current?.phase == LeanRecordingPhase.ACCELEROMETER_DATA_MISSING -> "İvmeölçer verisi kayda ulaşmıyor"
        current?.phase == LeanRecordingPhase.GYROSCOPE_DATA_MISSING -> "Jiroskop verisi kayda ulaşmıyor"
        current?.phase == LeanRecordingPhase.BOTH_SENSORS_DATA_MISSING -> "Yatış sensörlerinin verisi kayda ulaşmıyor"
        current?.phase == LeanRecordingPhase.STORAGE_UNAVAILABLE -> "Yatış verisi depolamaya yazılamıyor"
        settings.mount == LeanMount.HANDLEBAR -> "Gidon kalibrasyonu hazır · Sensör verisi bekleniyor"
        else -> "Gövde kalibrasyonu hazır · Sensör verisi bekleniyor"
    }
    val liveTarget by LiveLeanCapture.recordingTarget.collectAsStateWithLifecycle()
    val hideGnssHint = liveTarget?.let { (targetJourneyId, calibrationEpoch) ->
        targetJourneyId == journeyId && calibrationEpoch == reference?.epoch
    } == true
    val gnssHint = current?.gnssHint?.takeIf { settings.enabled && !hideGnssHint && it.isNotBlank() }
    LeanRecordingNotice(label, gnssHint, saving, saveFailed, onEnable = if (settings.enabled) null else {
        {
            if (!saving) {
                saving = true
                saveFailed = false
                scope.launch {
                    try { withContext(Dispatchers.IO) { settingsStore.enabled(true) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { saveFailed = true }
                    finally { saving = false }
                }
            }
        }
    })
}

@Composable
private fun LeanRecordingNotice(label: String, gnssHint: String?, saving: Boolean, saveFailed: Boolean, onEnable: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("lean-recording-notice")) {
        Text(label, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("lean-recording-status"),
            style = MaterialTheme.typography.bodySmall)
        gnssHint?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("lean-recording-gnss-hint"), style = MaterialTheme.typography.bodySmall) }
        if (onEnable != null) {
            TextButton(onEnable, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).testTag("lean-recording-enable")) {
                Text("Yatış kaydını aç")
            }
        }
        if (saveFailed) Text("Yatış kaydı ayarı saklanamadı.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}
