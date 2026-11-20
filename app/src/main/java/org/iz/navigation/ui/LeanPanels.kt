@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun LeanSetupPanel(onMessage: (String) -> Unit) {
    val app = LocalContext.current.applicationContext
    val settings = remember(app) { LeanSettingsStore.get(app) }
    val liveDisplay = remember(app) { LiveLeanDisplaySettings.get(app) }
    val liveEnabled by liveDisplay.enabled.collectAsStateWithLifecycle()
    val config by settings.state.collectAsStateWithLifecycle()
    val liveOptionVisible = LiveLeanGate.allows(LeanMount.BODY) || LiveLeanGate.allows(LeanMount.HANDLEBAR)
    val liveAllowed = LiveLeanGate.allows(config.mount)
    fun save(action: () -> Unit) {
        try { action() } catch (_: Exception) { onMessage("Ölçüm ayarları kaydedilemedi. Yeniden deneyebilirsin.") }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Motosiklet montajı ve kalibrasyon", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Kalibrasyonu park hâlinde yap. Yatış tahmini deneysel bir ölçümdür.")
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Sürüş sonrası rapor için sensör verisi kaydet", Modifier.weight(1f))
                Switch(config.enabled, { enabled -> save { settings.enabled(enabled) } },
                    modifier = Modifier.semantics { contentDescription = "Yatış raporu için sensör verisi kaydet" })
            }
            Text("Telefonun bağlantısı", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(LeanMount.UNKNOWN to "Belirsiz", LeanMount.HANDLEBAR to "Gidon", LeanMount.BODY to "Gövde").forEach { (mount, label) ->
                    FilterChip(config.mount == mount, {
                        if (config.mount != mount) save { settings.mount(mount) }
                    }, { Text(label) })
                }
            }
            if (liveOptionVisible) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Canlı tahmin", Modifier.weight(1f))
                    Switch(liveEnabled && liveAllowed, { enabled -> liveDisplay.setEnabled(enabled) },
                        enabled = liveAllowed,
                        modifier = Modifier.testTag("live-lean-setting").semantics { contentDescription = "Canlı yatış tahmini" })
                }
                if (!liveAllowed) Text("Canlı tahmin için gidon veya gövde montajını seç.", style = MaterialTheme.typography.bodySmall)
            }
            if (config.mount == LeanMount.HANDLEBAR) {
                HandlebarCalibrationControl(
                    hasProfile = validHandlebarReference(config.calibration),
                    canCalibrate = { settings.state.value.mount == LeanMount.HANDLEBAR },
                    onSave = { profile ->
                        settings.reference(profile)
                        onMessage("Gidon kalibrasyonu kaydedildi.")
                    },
                    onReset = { save { settings.clearCalibration() } },
                    capture = { step, accepted, retry -> captureHandlebarCalibration(app, step, accepted, retry) },
                )
            } else if (config.mount == LeanMount.BODY) {
                BodyCalibrationControl(
                    hasProfile = validLeanReference(config.calibration),
                    canCalibrate = { settings.state.value.mount == LeanMount.BODY },
                    capture = { LeanSensorCapture.calibrate(app) },
                    onSave = { profile ->
                        settings.reference(profile)
                        onMessage("Dik referans kaydedildi. Eksen uygunluğu sürüş sonrası doğrulanacak.")
                    },
                    onReset = { save { settings.clearCalibration() } },
                )
            } else {
                Text("Kalibrasyon için telefonun gidona mı, gövdeye mi sabitlendiğini seç.")
            }
            if (config.enabled) Text(
                "Rapor için yalnız onaylanmış motosiklet kaydında ölçülür. Ayrıntılı analiz, kayıt ve navigasyon kapalıyken pil en az %20 olduğunda yapılır. Ham veri sınırı dolarsa sensör ölçümü durur; GPS kaydı devam eder.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun BodyCalibrationControl(
    hasProfile: Boolean,
    canCalibrate: () -> Boolean,
    capture: suspend () -> CalibrationResult,
    onSave: (LeanCalibration) -> Unit,
    onReset: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var upright by remember { mutableStateOf(false) }
    var bodyError by remember { mutableStateOf<String?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    var calibrationJob by remember { mutableStateOf<Job?>(null) }
    var calibrationGeneration by remember { mutableLongStateOf(0L) }
    fun cancel() { calibrationGeneration++; calibrationJob?.cancel(); calibrationJob = null; calibrating = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (calibrating) {
                    bodyError = "Ekrandan ayrıldığın için ölçüm durdu. Dik referansı yeniden al."
                    upright = false
                }
                cancel()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    Text("Telefonu motosiklet gövdesine sıkıca sabitle. Güvenli bir yerde durup motosikleti yan ayaklıktan kaldır ve dik tut.")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(upright, enabled = !calibrating, role = Role.Checkbox, onValueChange = { upright = it }),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(upright, onCheckedChange = null, enabled = !calibrating)
        Text("Park hâlindeyim, motosiklet dik ve telefon gövdeye sabit.", Modifier.weight(1f))
    }
    Button(enabled = upright && !calibrating, onClick = {
        if (calibrating || !canCalibrate() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@Button
        bodyError = null
        calibrating = true
        val generation = ++calibrationGeneration
        calibrationJob = scope.launch {
            try {
                val result = capture()
                if (generation != calibrationGeneration || !canCalibrate() ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                when (result) {
                    is CalibrationResult.Failed -> bodyError = result.reason.message
                    is CalibrationResult.Ready -> {
                        try {
                            onSave(result.profile)
                        } catch (_: Exception) {
                            bodyError = "Dik referans ölçüldü, ancak kaydedilemedi. Yeniden dene."
                            return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == calibrationGeneration) bodyError = "Sensör ölçümü kesildi. Yeniden dene." }
            finally { if (generation == calibrationGeneration) { calibrating = false; calibrationJob = null } }
        }
    }) { Text(if (calibrating) "Üç saniye sabit tut…" else "Dik referans al") }
    bodyError?.let { reason ->
        Text(reason, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error)
    }
    if (calibrating) TextButton(onClick = ::cancel) { Text("İptal") }
    if (hasProfile) {
        Text("Dik referans hazır. Sayısal sonuç için sürüş sonrası üç eksen doğrulaması da gerekir.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { cancel(); upright = false; bodyError = null; onReset() }) { Text("Bağlantı değişti · Referansı sıfırla") }
    }
}

@Composable
internal fun LeanJourneyReportPanel(journey: Journey) {
    if (journey.transport != Transport.MOTORCYCLE || journey.status != JourneyStatus.CONFIRMED) return
    val app = LocalContext.current.applicationContext
    val store = remember(app) { LeanStore.get(app) }
    val flow = remember(store, journey.id) { store.observeReport(journey.id) }
    val report by flow.collectAsStateWithLifecycle(LeanJourneyReport(false, null, null, 0, 0, emptySet(), emptyList(), emptySet()))
    val handlebar = report.handlebar
    val showBody = handlebar == null || report.pending || report.observedMillis > 0 || report.partialReasons.isNotEmpty() ||
        report.leftDegrees != null || report.rightDegrees != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deneysel yatış raporu", style = MaterialTheme.typography.titleLarge)
            if (showBody) {
                key(journey.id, LeanMount.BODY) {
                    LeanReportSection(journey, report, "Gövde yatışı".takeIf { handlebar != null },
                        "Dikey dünyaya göre tahmini gövde yatışı; ölçülmüş doğruluk iddiası değildir.", "Gövde", "body-lean-report")
                }
            }
            if (handlebar != null) {
                if (showBody) HorizontalDivider()
                key(journey.id, LeanMount.HANDLEBAR) {
                    LeanReportSection(journey, handlebar, "Gidon bağlantısından tahmini yatış",
                        "Gidon hareketi ayrıştırılarak yapılan deneysel sürüş sonrası tahmin.", "Gidon", "handlebar-lean-report")
                }
            }
        }
    }
}

@Composable
private fun LeanReportSection(journey: Journey, report: LeanJourneyReport, title: String?, description: String,
    mountLabel: String, reportTag: String) {
    var curves by remember { mutableStateOf(false) }
    var coverage by remember { mutableStateOf(false) }
    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
    Text(description, Modifier.testTag(reportTag), style = MaterialTheme.typography.bodySmall)
    if (journey.endedAt == null) Text("Rapor yolculuk bittikten sonra hazırlanır.")
    else if (report.pending) Text("Hazırlanacak · Kayıt ve navigasyon bitmeli, pil en az %20 olmalı.")
    else if (report.leftDegrees == null && report.rightDegrees == null) Text("Kullanılabilir yatış tahmini yok. Önceki yolculuklara sonradan değer eklenmez.")
    else {
        fun angle(value: Double?) = value?.takeIf { it.isFinite() }?.let { "${it.roundToInt()}°" } ?: "Kullanılamıyor"
        Text("En büyük geçerli sol tahmin: ${angle(report.leftDegrees)}")
        Text("En büyük geçerli sağ tahmin: ${angle(report.rightDegrees)}")
        if (report.curves.isNotEmpty()) {
            TextButton(onClick = { curves = !curves }, modifier = Modifier.testTag("$reportTag-curves")) {
                Text(if (curves) "$mountLabel virajlarını gizle" else "$mountLabel virajlarını göster (${report.curves.size})")
            }
            if (curves) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(report.curves) { curve ->
                    val time = curve.startUtcMillis?.let { SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("tr-TR")).format(Date(it)) } ?: "Saat eşlenemedi"
                    Text("$time · Sol ${angle(curve.leftDegrees)} · Sağ ${angle(curve.rightDegrees)}", style = MaterialTheme.typography.bodyMedium)
                    if (curve.latitude != null && curve.longitude != null) Text(String.format(Locale.ROOT, "Rota noktası %.5f, %.5f", curve.latitude, curve.longitude), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
            }
        }
    }
    val completed = journey.endedAt != null && !report.pending
    if (completed || report.partialReasons.isNotEmpty()) {
        TextButton(onClick = { coverage = !coverage }, modifier = Modifier.testTag("$reportTag-coverage")) {
            Text(if (coverage) "Ölçüm kapsamını gizle" else "Ölçüm kapsamı ve sınırlamalar")
        }
        if (coverage) {
            if (completed) Text("Geçerli kapsam ${report.validMillis / 1000} sn / gözlenen ${report.observedMillis / 1000} sn",
                style = MaterialTheme.typography.bodySmall)
            report.partialReasons.map(::leanPartialLabel).distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun leanPartialLabel(reason: String): String {
    val value = reason.lowercase(Locale.ROOT)
    return when {
        value == "gnss_course_uncertain" -> "GPS yönü yeterince güvenilir değildi."
        value == "gnss_motion_disagreement" -> "GPS konum değişimi, hız veya yön bilgisiyle uyuşmadı."
        value == "gnss_quality" -> "GPS konum veya hız kalitesi yetersizdi."
        value == "parked_steering_or_stationary" -> "Motosiklet hareketsizdi. Parkta gidon çevirmek viraj olarak sayılmaz."
        value == "low_speed_unsupported" -> "Düşük hızdaki bölümler yatış tahminine katılmadı."
        value == "steering_or_motion_veto" -> "Gidon hareketi, titreşim veya ani hareket nedeniyle bazı bölümler tahmine katılmadı."
        value == "no_supported_curves" -> "Yatış tahmini için yeterli koşulları sağlayan viraj bulunamadı."
        "chunk_limit" in value -> "Sensör verisi bölüm sınırına ulaştı; rapor kısmi."
        "capacity" in value || "budget" in value -> "Sensör verisi depolama sınırına ulaştı; rapor kısmi."
        "sensor" in value -> "Gerekli sensör verisi uygun değil veya sensör bulunamadı."
        "calib" in value || "frame" in value -> "Bağlantı veya motosiklet eksenleri doğrulanamadı."
        "clock" in value || "time" in value -> "Ölçümlerin zaman eşlemesi uygun değil."
        "rate" in value || "gnss" in value || "gps" in value -> "Gerçek konum/sensör örnek sıklığı bu bölüm için yetersiz."
        "gap" in value || "drop" in value -> "Ölçüm boşlukları bulunan bölümler tahmine katılmadı."
        else -> "Ölçümün bir bölümü kullanılamadı; eksik bölümlere yatış değeri eklenmedi."
    }
}
