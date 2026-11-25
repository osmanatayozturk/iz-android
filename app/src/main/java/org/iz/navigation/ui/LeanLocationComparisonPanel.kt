package org.iz.navigation.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.iz.navigation.tracking.*
import org.iz.navigation.IzApplication
import java.util.Locale

@Composable
internal fun LeanLocationComparisonPanel() {
    val context = LocalContext.current
    val app = context.applicationContext as IzApplication
    val navigation by app.navigation.state.collectAsStateWithLifecycle()
    val block = parkedDiagnosticBlock(navigation, TrackingService.isRunning, System.currentTimeMillis())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val capture = remember(context) { LocationSourceComparison(context) }
    val scope = rememberCoroutineScope()
    val snapshot by capture.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var parked by remember { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var precise by remember { mutableStateOf(LocationSourceComparison.preciseGranted(context)) }
    var job by remember { mutableStateOf<Job?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        precise = LocationSourceComparison.preciseGranted(context)
    }
    fun close() { open = false; parked = false; job?.cancel() }
    DisposableEffect(lifecycle, capture) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; precise = LocationSourceComparison.preciseGranted(context) }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> { resumed = false; close() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    LaunchedEffect(snapshot.phase) {
        precise = LocationSourceComparison.preciseGranted(context)
        if (snapshot.issue in setOf(ComparisonIssue.MOVING, ComparisonIssue.ACTIVE_RIDE)) parked = false
    }
    OutlinedButton(enabled = job == null && resumed && block == null, onClick = { capture.clear(); open = true },
        modifier = Modifier.heightIn(min = 48.dp).testTag("location-comparison-open")) {
        Text("GPS kaynaklarını karşılaştır")
    }
    if (block != null) Text(if (block == ComparisonIssue.MOVING) "Karşılaştırmayı park halinde yapın."
        else "Karşılaştırma için açık kayıt veya tarifi bitirin.")
    if (open) Dialog(onDismissRequest = ::close) {
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused) { if (!focused) job?.cancel() }
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            LeanLocationComparisonContent(snapshot, parked, job != null, precise, resumed && focused && block == null,
                onParkedChange = { if (job == null) parked = it },
                onStart = {
                    if (job == null && parked && resumed && focused && LocationSourceComparison.preciseGranted(context) &&
                        parkedDiagnosticBlock(app.navigation.state.value, TrackingService.isRunning, System.currentTimeMillis()) == null) {
                        val measuring = scope.launch(start = CoroutineStart.LAZY) {
                            try { capture.measure() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            finally { job = null }
                        }
                        job = measuring
                        measuring.start()
                    }
                }, onCancel = { job?.cancel() }, onClose = ::close,
                onPermission = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) })
        }
    }
}

/** Render/test boundary: no Android location objects, app graph, storage or acquisition work. */
@Composable
internal fun LeanLocationComparisonContent(snapshot: LocationComparisonSnapshot, parked: Boolean, running: Boolean,
    precise: Boolean, ready: Boolean, onParkedChange: (Boolean) -> Unit, onStart: () -> Unit,
    onCancel: () -> Unit, onClose: () -> Unit, onPermission: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)
        .testTag("location-comparison-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GPS kaynak karşılaştırması", style = MaterialTheme.typography.titleLarge)
        Text("Güvenli bir yerde park edin ve telefonu sabit tutun. İki kaynak hazır olunca aynı 10 saniye ölçülür.")
        Text("Bu panel koordinat saklamaz; dosya, geçmiş veya ağ kaydı oluşturmaz. Kalibrasyonu ve yolculuk kaydını değiştirmez.")
        Text(if (precise) "Kesin konum izni: verildi" else "Kesin konum izni gerekli; yaklaşık konum yeterli değil.",
            Modifier.testTag("location-comparison-permission"))
        if (!precise) TextButton(onClick = onPermission, enabled = !running) { Text("Kesin konum izni ver") }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("location-comparison-parked")
            .toggleable(parked, enabled = !running, role = Role.Checkbox, onValueChange = onParkedChange),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(parked, onCheckedChange = null, enabled = !running)
            Text("Park halindeyim; telefon sabit.", Modifier.weight(1f))
        }
        if (running) Button(onClick = onCancel, modifier = Modifier.testTag("location-comparison-cancel").heightIn(min = 48.dp)) {
            Text("Ölçümü durdur")
        } else Button(onClick = onStart, enabled = parked && precise && ready,
            modifier = Modifier.testTag("location-comparison-start").heightIn(min = 48.dp)) { Text("10 saniye karşılaştır") }
        Text(comparisonStatus(snapshot), Modifier.testTag("location-comparison-result")
            .semantics { liveRegion = LiveRegionMode.Polite })
        val measured = snapshot.startedElapsedNanos != null
        if (measured) Text("Ölçülen ${number(snapshot.elapsedMillis / 1000.0)} / 10 sn")
        ComparisonSourceContent("Birleştirilmiş kaynak · yüksek doğruluk isteği", "fused", snapshot.fused, measured, false)
        ComparisonSourceContent("Doğrudan GPS · Android GPS_PROVIDER", "gps", snapshot.gps, measured, false)
        TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("location-comparison-details")) { Text(if (details) "Teknik ayrıntıları gizle" else "Teknik ayrıntıları göster") }
        if (details && measured) {
            Text("Ortak monoton başlangıç: ${snapshot.startedElapsedNanos} ns · ölçülen ${number(snapshot.elapsedMillis / 1000.0)} / 10 sn")
            Text("Hazırlık: ${snapshot.registrationMillis} ms. Hazırlık örnekleri ve önceki ölçümler sayılmaz.")
            ComparisonSourceContent("Birleştirilmiş kaynak ayrıntıları", "fused", snapshot.fused, true, true)
            ComparisonSourceContent("Doğrudan GPS ayrıntıları", "gps", snapshot.gps, true, true)
        }
        if (details) Text("İstenen aralık: iki kaynakta 1000 ms. Yüksek doğruluk bir istektir, ölçüm garantisi değildir. Bu kısa sabit ölçüm kaynak üstünlüğü veya yatış doğruluğu kanıtlamaz.")
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("location-comparison-close")) { Text("Kapat") }
    }
}

@Composable
private fun ComparisonSourceContent(title: String, tag: String, value: ComparisonStreamSummary, measured: Boolean, details: Boolean) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (!details) {
        Text(if (measured) "Benzersiz ölçüm ${value.accepted} · pencere ${number(value.windowHz)} Hz" else "Henüz ölçülmedi.",
            Modifier.testTag("location-comparison-$tag-summary"))
        if (measured) {
            val speed = value.fields.getValue(ComparisonField.SPEED_ACCURACY)
            val time = value.fields.getValue(ComparisonField.TIME_UNCERTAINTY_MILLIS)
            Text("Hız doğruluğu alanı: ${speed.values.count} geçerli · zaman belirsizliği alanı: ${time.values.count} geçerli")
        }
        return
    }
    Text("Çağrı ${value.callbacks} · alınan ${value.received} · benzersiz ${value.accepted}")
    Text("Ardışık aralık ms: ${range(value.intervalMillis)} · teslim yaşı ms: ${range(value.deliveryAgeMillis)}")
    Text("Pencere dışı ${value.outsideWindow} · hatalı zaman ${value.invalidTime} · tekrar ${value.duplicates} · sırasız ${value.outOfOrder} · sahte ${value.mock}")
    Text("Kullanılabilirlik: ${value.available?.let { if (it) "bildirildi" else "şu an bildirilmedi" } ?: "bildirim yok"} · olumsuz bildirim ${value.unavailableReports}")
    ComparisonField.entries.forEach { field ->
        val stats = value.fields.getValue(field)
        Text("${fieldLabel(field)}: dolu ${stats.values.count + stats.invalid}, eksik ${stats.missing}, hatalı ${stats.invalid}" +
            if (field == ComparisonField.ALTITUDE) "" else " · min/ort/maks ${range(stats.values)}")
    }
}
private fun number(value: Double?) = value?.let { String.format(Locale.forLanguageTag("tr"), "%.2f", it) } ?: "—"
private fun range(value: ComparisonValues) = "${number(value.minimum)} / ${number(value.mean)} / ${number(value.maximum)}"
private fun fieldLabel(field: ComparisonField) = when (field) {
    ComparisonField.HORIZONTAL_ACCURACY -> "Yatay doğruluk (m)"
    ComparisonField.SPEED -> "Hız (m/sn)"
    ComparisonField.SPEED_ACCURACY -> "Hız doğruluğu (m/sn)"
    ComparisonField.BEARING -> "Yön (°)"
    ComparisonField.BEARING_ACCURACY -> "Yön doğruluğu (°)"
    ComparisonField.ALTITUDE -> "Yükseklik alanı (değer saklanmaz)"
    ComparisonField.VERTICAL_ACCURACY -> "Dikey doğruluk (m)"
    ComparisonField.TIME_UNCERTAINTY_MILLIS -> "Monoton zaman belirsizliği (ms)"
}
private fun comparisonStatus(state: LocationComparisonSnapshot): String = when (state.phase) {
    ComparisonPhase.IDLE -> "Ölçüm başlamadı."
    ComparisonPhase.REGISTERING -> "İki kaynak hazırlanıyor…"
    ComparisonPhase.MEASURING -> "Aynı pencerede ölçülüyor…"
    ComparisonPhase.COMPLETE -> "Ölçüm tamamlandı. Örnek yoksa alan kalitesi karşılaştırılamaz."
    ComparisonPhase.CANCELLED -> "Ölçüm durduruldu; kısmi sonuç tam 10 saniye değildir."
    ComparisonPhase.ERROR -> (state.issueSource?.let { if (it == ComparisonSourceId.FUSED) "Birleştirilmiş kaynak: " else "Doğrudan GPS: " } ?: "") + when (state.issue) {
        ComparisonIssue.FINE_PERMISSION -> "Kesin konum izni yok veya değişti; iki akış durduruldu."
        ComparisonIssue.LOCATION_DISABLED -> "Android konumu kapalı; iki akış durduruldu."
        ComparisonIssue.GPS_UNAVAILABLE -> "GPS sağlayıcısı bulunamadı."
        ComparisonIssue.PROVIDER_DISABLED -> "GPS sağlayıcısı kapandı; iki akış durduruldu."
        ComparisonIssue.REGISTRATION_TIMEOUT -> "İki kaynak zamanında hazırlanamadı. Yeniden deneyin."
        ComparisonIssue.ACTIVE_RIDE -> "Kayıt veya tarif açıldığı için ölçüm durdu."
        ComparisonIssue.MOVING -> "Güncel hareket bilgisi alındı; park halinde yeniden deneyin."
        else -> "Kaynak kaydı açılamadı; ölçüm tamamlanmadı. Yeniden deneyin."
    }
}
