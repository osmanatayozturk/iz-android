package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.iz.navigation.IzApplication
import org.iz.navigation.lean.AndroidLeanImuCheck
import org.iz.navigation.lean.LEAN_IMU_CHECK_DURATION_NANOS
import org.iz.navigation.lean.LeanImuCheckProgress
import org.iz.navigation.lean.LeanImuCheckRegistration
import org.iz.navigation.lean.LeanSensorKind
import org.iz.navigation.tracking.ComparisonIssue
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.tracking.parkedDiagnosticBlock
import java.util.Locale

@Composable
internal fun LeanImuCheckControl() {
    val app = LocalContext.current.applicationContext as IzApplication
    val sensorCheck = remember(app) { AndroidLeanImuCheck(app) }
    val navigation by app.navigation.state.collectAsStateWithLifecycle()
    val block = parkedDiagnosticBlock(navigation, TrackingService.isRunning, System.currentTimeMillis())
    fun currentBlock() =
        parkedDiagnosticBlock(app.navigation.state.value, TrackingService.isRunning, System.currentTimeMillis())
    var open by remember { mutableStateOf(false) }
    if (block == null) OutlinedButton(onClick = { open = true },
        modifier = Modifier.heightIn(min = 48.dp).testTag("lean-imu-check-open")) { Text("Sensörleri sına") }
    else Text(if (block == ComparisonIssue.MOVING) "Testi park hâlinde yap." else "Tanı, sürüş tamamlanınca açılır.",
        style = MaterialTheme.typography.bodySmall)
    if (open) LeanImuCheckDialog(canStart = block == null,
        startAllowed = { currentBlock() == null },
        test = sensorCheck::check,
        interruption = {
            when (currentBlock()) {
                ComparisonIssue.MOVING -> "Hareket algılandı. Park edince yeniden sına."
                ComparisonIssue.ACTIVE_RIDE -> "Sürüş başladığı için test durdu."
                else -> null
            }
        },
        onClose = { open = false })
}

@Composable
internal fun LeanImuCheckDialog(canStart: Boolean, startAllowed: () -> Boolean,
    test: () -> Flow<LeanImuCheckProgress>, interruption: () -> String? = { null }, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var job by remember { mutableStateOf<Job?>(null) }
    var parked by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<LeanImuCheckProgress?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val busy = job != null

    fun stop(message: String) {
        if (job?.isActive != true) return
        parked = false
        progress = null
        status = message
        job?.cancel()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                parked = false
                stop("Ekrandan ayrıldığın için test durdu.")
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }
    LaunchedEffect(canStart) {
        if (!canStart) {
            parked = false
            stop(interruption() ?: "Sürüş başladığı için test durdu.")
        }
    }
    val dismiss = {
        job?.cancel()
        onClose()
    }
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("lean-imu-check"),
        title = { Text("Sensörleri sına") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test 10 saniye sürer. Telefonu güvenli bir yerde sabit tut.")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lean-imu-check-parked")
                    .toggleable(parked, enabled = canStart && !busy, role = Role.Checkbox, onValueChange = { parked = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(parked, onCheckedChange = null, enabled = canStart && !busy)
                    Text("Park hâlindeyim.")
                }
                status?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("lean-imu-check-status"))
                }
                if (busy) {
                    val remaining = progress?.remainingNanos ?: LEAN_IMU_CHECK_DURATION_NANOS
                    val seconds = (remaining + 999_999_999L) / 1_000_000_000L
                    Text("Kalan $seconds sn", Modifier.testTag("lean-imu-check-remaining"))
                }
                progress?.let { read ->
                    SensorCheckReading(LeanSensorKind.ACCELEROMETER, "İvmeölçer", read)
                    SensorCheckReading(LeanSensorKind.GYROSCOPE, "Jiroskop", read)
                    if (read.complete) Text("Örnek sayıları sensör akışını gösterir. Kalibrasyon ve yatış ölçümü için mevcut adımları kullan.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (job != null || !parked || !startAllowed() ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@Button
                status = "Sensör testi başladı."
                progress = null
                job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        test().collect { read ->
                            if (!startAllowed()) {
                                stop(interruption() ?: "Sürüş başladığı için test durdu.")
                                return@collect
                            }
                            progress = read
                            if (read.complete) status = "Sensör testi tamamlandı."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status = "Sensör testi tamamlanamadı. Yeniden dene."
                    } finally {
                        job = null
                    }
                }
                job?.start()
            }, enabled = canStart && parked && !busy, modifier = Modifier.testTag("lean-imu-check-start")) {
                Text(if (progress?.complete == true) "Yeniden sına" else "10 saniyelik testi başlat")
            }
        },
        dismissButton = {
            if (busy) TextButton(onClick = { stop("Sensör testi durduruldu.") },
                modifier = Modifier.testTag("lean-imu-check-stop")) { Text("Testi durdur") }
            else TextButton(onClick = dismiss) { Text("Kapat") }
        })
}

@Composable
private fun SensorCheckReading(kind: LeanSensorKind, label: String, progress: LeanImuCheckProgress) {
    val counts = progress.counts.getValue(kind)
    val registration = progress.registration.getValue(kind)
    val result = when (registration) {
        LeanImuCheckRegistration.MISSING -> "Sensör yok"
        LeanImuCheckRegistration.PERMISSION_RESTRICTED -> "İzin kısıtı nedeniyle sensöre erişilemedi"
        LeanImuCheckRegistration.FAILED -> "Sensör başlatılamadı"
        LeanImuCheckRegistration.LISTENING -> when {
            !progress.complete -> "${counts.received} örnek"
            counts.received == 0L -> "Örnek gelmedi"
            else -> "${counts.received} örnek alındı"
        }
    }
    Column(Modifier.testTag("lean-imu-check-${kind.name.lowercase(Locale.ROOT)}")) {
        Text("$label · $result")
        if (counts.usable != counts.received) Text("${counts.usable} geçerli örnek", style = MaterialTheme.typography.bodySmall)
        if (progress.complete && registration == LeanImuCheckRegistration.LISTENING) {
            val locale = Locale.forLanguageTag("tr-TR")
            val hertz = String.format(locale, "%.1f Hz", counts.usableHertz)
            val interval = counts.longestIntervalNanos
                ?.let { String.format(locale, "%.1f ms", it / 1_000_000.0) } ?: "Ölçülemedi"
            Text("Geçerli örnek hızı · $hertz", style = MaterialTheme.typography.bodySmall)
            Text("Örnekler arasındaki en uzun süre · $interval", style = MaterialTheme.typography.bodySmall)
        }
    }
}
