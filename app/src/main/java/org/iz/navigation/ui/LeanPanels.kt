@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val config by settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var upright by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var calibrationJob by remember { mutableStateOf<Job?>(null) }
    var calibrationGeneration by remember { mutableLongStateOf(0L) }
    fun cancel() { calibrationGeneration++; calibrationJob?.cancel(); calibrationJob = null; calibrating = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    fun save(action: () -> Unit) {
        try { action() } catch (_: Exception) { onMessage("Ölçüm ayarları kaydedilemedi. Yeniden deneyebilirsin.") }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Motosiklet · Deneysel yatış raporu", style = MaterialTheme.typography.titleLarge)
            Text("Rapor sürüş bittikten sonra hazırlanır. Canlı yatış göstergesi değildir; doğruluk garantisi vermez.")
            Row { Text("Ölçüm için sensör verisi kaydet", Modifier.weight(1f)); Switch(config.enabled,
                { enabled -> cancel(); save { settings.enabled(enabled) } }) }
            if (config.enabled) {
                Text("Telefonun bağlantısı", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(LeanMount.UNKNOWN to "Belirsiz", LeanMount.HANDLEBAR to "Gidon", LeanMount.BODY to "Gövde").forEach { (mount, label) ->
                        FilterChip(config.mount == mount, { cancel(); upright = false; save { settings.mount(mount) } }, { Text(label) })
                    }
                }
                if (config.mount != LeanMount.BODY) Text("Gidon veya belirsiz bağlantıda sayısal yatış raporu üretilemez. Navigasyon ve yolculuk kaydı kullanılabilir.")
                else {
                    Text("Telefonu motosiklet gövdesine sıkıca sabitle. Referansı güvenli bir yerde dururken, motosikleti dik tutarak al; yan ayaklıkta alma.")
                    Row { Checkbox(upright, { upright = it }, enabled = !calibrating); Text("Duruyorum; motosiklet dik ve telefon gövdeye sabit.", Modifier.weight(1f)) }
                    Button(enabled = upright && !calibrating, onClick = {
                        calibrating = true
                        val generation = ++calibrationGeneration
                        calibrationJob = scope.launch {
                            try {
                                val reference = LeanSensorCapture.calibrate(app)
                                if (reference != null && generation == calibrationGeneration && settings.state.value.enabled &&
                                    settings.state.value.mount == LeanMount.BODY && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    settings.reference(reference)
                                    onMessage("Dik referans kaydedildi. Eksen uygunluğu sürüş sonrası doğrulanacak.")
                                } else onMessage("Uygun sabit referans alınamadı. Sensörleri ve sabit bağlantıyı kontrol et.")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { onMessage("Referans kaydedilemedi. Yeniden deneyebilirsin.") }
                            finally { if (generation == calibrationGeneration) { calibrating = false; calibrationJob = null } }
                        }
                    }) { Text(if (calibrating) "Üç saniye sabit tut…" else "Dik referans al") }
                    if (calibrating) TextButton(onClick = ::cancel) { Text("İptal") }
                    if (config.calibration != null) {
                        Text("Dik referans hazır. Sayısal sonuç için sürüş sonrası üç eksen doğrulaması da gerekir.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { cancel(); upright = false; save { settings.clearCalibration() } }) { Text("Bağlantı değişti · Referansı sıfırla") }
                    }
                }
                Text("Yalnız onaylanmış motosiklet kaydında ölçülür. Ayrıntılı analiz, kayıt ve navigasyon kapalıyken pil en az %20 olduğunda yapılır. Ham veri sınırı dolarsa sensör ölçümü durur; GPS kaydı devam eder.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun LeanJourneyReportPanel(journey: Journey) {
    if (journey.transport != Transport.MOTORCYCLE || journey.status != JourneyStatus.CONFIRMED) return
    val app = LocalContext.current.applicationContext
    val store = remember(app) { LeanStore.get(app) }
    val flow = remember(store, journey.id) { store.observeReport(journey.id) }
    val report by flow.collectAsStateWithLifecycle(LeanJourneyReport(false, null, null, 0, 0, emptySet(), emptyList(), emptySet()))
    var curves by remember(journey.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deneysel yatış raporu", style = MaterialTheme.typography.titleLarge)
            Text("Dikey dünyaya göre tahmini gövde yatışı; ölçülmüş doğruluk iddiası değildir.", style = MaterialTheme.typography.bodySmall)
            if (journey.endedAt == null) Text("Rapor yolculuk bittikten sonra hazırlanır.")
            else if (report.pending) Text("Hazırlanacak · Kayıt ve navigasyon bitmeli, pil en az %20 olmalı.")
            else if (report.leftDegrees == null && report.rightDegrees == null) Text("Kullanılabilir yatış tahmini yok. Önceki yolculuklara sonradan değer eklenmez.")
            else {
                fun angle(value: Double?) = value?.takeIf { it.isFinite() }?.let { "${it.roundToInt()}°" } ?: "Kullanılamıyor"
                Text("En büyük geçerli sol tahmin: ${angle(report.leftDegrees)}")
                Text("En büyük geçerli sağ tahmin: ${angle(report.rightDegrees)}")
                if (report.curves.isNotEmpty()) {
                    TextButton(onClick = { curves = !curves }) { Text(if (curves) "Virajları gizle" else "Virajları göster (${report.curves.size})") }
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
            if (journey.endedAt != null && !report.pending) {
                Text("Geçerli kapsam ${report.validMillis / 1000} sn / gözlenen ${report.observedMillis / 1000} sn", style = MaterialTheme.typography.bodySmall)
            }
            report.partialReasons.map(::leanPartialLabel).distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun leanPartialLabel(reason: String): String {
    val value = reason.lowercase(Locale.ROOT)
    return when {
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