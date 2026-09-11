package org.iz.navigation.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.health.*
import org.iz.navigation.wear.WatchHealthConnection
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun healthTime(time: Long) = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(time))
private fun healthValue(value: Double) = String.format(Locale.getDefault(), "%.0f", value)
private fun HealthMetric.healthLabel() = when (this) {
    HealthMetric.HEART_RATE_BPM -> "Nabız"
    HealthMetric.TOTAL_CALORIES_KCAL -> "Toplam kalori"
    HealthMetric.STEPS -> "Saat adımları"
}

@Composable
internal fun HealthSettingsPanel(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val manager = (context.applicationContext as? IzApplication)?.healthManager ?: return
    val status by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var clearing by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch {
            if (it.any { permission -> HealthMetric.entries.any { metric -> metric.readPermission() == permission } }) manager.connect()
            else manager.refresh()
        }
    }
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) scope.launch { manager.refresh() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(manager) { manager.refresh() }

    val watchLink by WatchHealthConnection.state.collectAsStateWithLifecycle()
    var linkNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); linkNow = System.currentTimeMillis() } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("İz · Canlı saat bağlantısı", style = MaterialTheme.typography.titleLarge)
            Text("Saatte İz'i açıp Otomatik ölçümü aç seçeneğini bir kez etkinleştir. Sensör ve her zaman arka plan sensörü izinlerini ver. Onaylanan yolculuk sırasında saat bilekteyse nabız ve adımlar kaydedilir.")
            if (watchLink.lastSeenAt == null) Text("Saatten henüz bağlantı bilgisi gelmedi.")
            else {
                Text(watchLink.deviceName)
                Text("Son bağlantı: ${healthTime(watchLink.lastSeenAt!!)}", style = MaterialTheme.typography.bodySmall)
                Text(when {
                    linkNow - watchLink.lastSeenAt!! > 60_000 -> "Son bağlantı bilgisi eski; saatte İz'i kontrol et."
                    watchLink.capturing -> "Saat ölçüm yapıyor"
                    watchLink.onBody == false -> "Saat bilekte değil; ölçüm bekliyor"
                    else -> "Saat hazır; yolculuk veya bilekte olma doğrulaması bekleniyor"
                })
                if (watchLink.buffered > 0) Text("Aktarım bekleyen ${watchLink.buffered} ölçüm")
            }
            Text("İz ölçümleri ve Samsung Health verileri ayrı gösterilir; adımlar toplanmaz. Kalori Samsung Health'ten sonradan gelir. Saatteki kalıcı bildirimden otomatik ölçümü kapatabilirsin.", style = MaterialTheme.typography.bodySmall)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Samsung Health", style = MaterialTheme.typography.titleLarge)
            Text("Saat kaynaklı nabız, toplam kalori ve adımlar yolculuklarına eklenir. Samsung Health eşitlemesi gecikebilir.")
            Text(when {
                status.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE -> "Bu cihazda Health Connect kullanılamıyor."
                status.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect kurulmalı veya güncellenmeli."
                !status.enabled -> "Bağlantı kapalı"
                status.grantedMetrics.isEmpty() -> "Sağlık okuma izni gerekli."
                status.backgroundAllowed -> "Arka planda otomatik eşitleme açık"
                else -> "Uygulama açıldığında eşitlenir"
            }, style = MaterialTheme.typography.titleSmall)
            if (status.enabled) HealthMetric.entries.forEach { metric ->
                Text("${metric.healthLabel()}: ${if (metric in status.grantedMetrics) "okuma izni var" else "izin verilmedi"}",
                    style = MaterialTheme.typography.bodySmall)
            }
            status.lastSyncedAt?.let { Text("Son eşitleme: ${healthTime(it)}", style = MaterialTheme.typography.bodySmall) }
            if (status.enabled) Text("İz açıkken her 60 saniyede yeni veri kontrol edilir. Samsung Health'in saate ait verileri telefona aktarması gecikebilir.", style = MaterialTheme.typography.bodySmall)
            if (status.sourceCounts.isNotEmpty()) {
                Text("Son okuma tanısı", style = MaterialTheme.typography.titleSmall)
                HealthMetric.entries.forEach { metric ->
                    val counts = status.sourceCounts[metric] ?: HealthSourceCounts()
                    Text("${metric.healthLabel()}: ${counts.records} kayıt; saat ${counts.watchRecords}, cihazı belirsiz ${counts.missingDeviceRecords}, diğer cihaz ${counts.otherDeviceRecords}; ${counts.samples} uygun ölçüm",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Sayılar bu okuma turuna aittir; değişiklikler yeniden okunabilir. Yolculuk sınırları dışında kalan ölçümler kayda eklenmez.", style = MaterialTheme.typography.bodySmall)
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (status.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
                Button(onClick = {
                    try { permissionLauncher.launch(HealthMetric.entries.map { it.readPermission() }.toSet()) }
                    catch (_: Exception) { onMessage("Sağlık izin ekranı açılamadı. Health Connect'i kontrol et.") }
                }, enabled = !status.syncing) { Text(if (status.enabled) "Sağlık izinlerini düzenle" else "Samsung Health'i bağla") }
                if (status.enabled && status.backgroundSupported && !status.backgroundAllowed) {
                    OutlinedButton(onClick = {
                        try { permissionLauncher.launch(setOf(BACKGROUND_HEALTH_PERMISSION)) }
                        catch (_: Exception) { onMessage("Arka plan sağlık izni açılamadı.") }
                    }) { Text("Arka plan eşitlemesine izin ver") }
                }
                if (status.enabled) {
                    OutlinedButton(onClick = manager::requestSync, enabled = !status.syncing) { Text("Şimdi eşitle") }
                    TextButton(onClick = { scope.launch { manager.disconnect() } }) { Text("Bağlantıyı kes") }
                }
            } else if (status.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) }
                        .onFailure { onMessage("Health Connect mağaza sayfası açılamadı.") }
                }) { Text("Health Connect'i kur / güncelle") }
            }
            Text("Samsung Health → Ayarlar → Health Connect bölümünden veri paylaşımını aç. Telefon veya cihazı belirtilmeyen ölçümler alınmaz.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(context, HealthPrivacyActivity::class.java)) }) { Text("Sağlık verileri ve gizlilik") }
            TextButton(onClick = { clearing = true }) { Text("Yerel sağlık verilerini temizle") }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false },
        title = { Text("Sağlık verilerini temizle") },
        text = { Text("İz'deki sağlık ölçümleri silinir. Yolculukların ve Samsung Health kayıtların korunur. Bağlantı açıksa sonradan gelen ölçümler yeniden eklenebilir.") },
        confirmButton = { TextButton(onClick = { clearing = false; scope.launch { manager.clearLocalData(); onMessage("Yerel sağlık verileri temizlendi.") } }) { Text("Temizle") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Vazgeç") } })
}

@Composable
internal fun JourneyHealthPanel(journey: Journey, state: DiaryState, now: Long) {
    if (journey.status != JourneyStatus.CONFIRMED) return
    val manager = (LocalContext.current.applicationContext as? IzApplication)?.healthManager
    val connection = manager?.state?.collectAsStateWithLifecycle()?.value ?: HealthConnectionState()
    val context = LocalContext.current
    val watchRepository = remember(context.applicationContext) { WatchHealthRepository(context.applicationContext) }
    val directFlow = remember(watchRepository, journey.id) { watchRepository.samplesForJourney(journey.id) }
    val direct by directFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val summary = remember(journey, direct) { WatchHealthRules.summarize(journey, direct, now) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("İz · Saat ölçümleri", style = MaterialTheme.typography.titleLarge)
            if (summary.measurementStartAt == null) Text("Bu yolculukta doğrudan saat ölçümü yok. Saatte otomatik ölçümü etkinleştir; ölçümler bilekteyken onaylanmış yolculukta alınır.")
            else {
                summary.latestHeartRateBpm?.let { bpm ->
                    val fresh = journey.endedAt == null && summary.latestHeartRateAt?.let { now - it in 0..30_000 } == true
                    Text("${if (fresh) "Canlı nabız" else "Son nabız"}: ${healthValue(bpm)} atım/dk")
                    summary.latestHeartRateAt?.let { Text("Ölçüm: ${healthTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    Text("Ortalama ${healthValue(summary.heartRateMeanBpm!!)} · En düşük ${healthValue(summary.heartRateMinBpm!!)} · En yüksek ${healthValue(summary.heartRateMaxBpm!!)}")
                    Text("${summary.heartRateSampleCount} gerçek ölçüm; boşluklar tamamlanmaz.", style = MaterialTheme.typography.bodySmall)
                }
                Text(summary.watchSteps?.let { "Saat adımları: $it" } ?: "Saat adımı ölçümü yok")
                if (summary.watchSteps != null) Text("Adım kapsamı: ${String.format(Locale.getDefault(), "%.1f", summary.stepCoverageMillis / 60_000.0)} dk · Kısmi veri", style = MaterialTheme.typography.bodySmall)
            }
            Text("Samsung Health ve telefon adımlarıyla toplanmaz. Kalori aşağıdaki Samsung Health bölümünden gelir.", style = MaterialTheme.typography.bodySmall)
        }
    }
    HealthSummaryCard(journey, state.healthSummaries.firstOrNull { it.journeyId == journey.id },
        state.healthSamples.filter { it.journeyId == journey.id }, connection, now)
}

@Composable
internal fun HealthSummaryCard(journey: Journey, summary: JourneyHealthSummary?, samples: List<JourneyHealthSample>,
    connection: HealthConnectionState, now: Long,
) {
    var expanded by remember(journey.id) { mutableStateOf(false) }
    val duration = ((journey.endedAt ?: now) - journey.startedAt).coerceAtLeast(0)
    val hr = remember(journey, samples, now) { HealthRules.observedHeartRateSamples(journey, samples, now) }
    fun missing(metric: HealthMetric) = when {
        !connection.enabled -> "Bağlantıyı Ayarlar'dan açabilirsin."
        metric !in connection.grantedMetrics -> "Okuma izni verilmedi."
        metric !in summary?.checkedMetrics.orEmpty() -> "Eşitleme bekleniyor."
        else -> "Veri bulunamadı. Samsung Health'ten sonra gelebilir."
    }
    fun coverage(millis: Long): String =
        "Kapsam: ${String.format(Locale.getDefault(), "%.1f", millis / 60_000.0)} dk" +
            if (millis < duration) " · Kısmi veri" else ""
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sağlık", style = MaterialTheme.typography.titleLarge)
            Text("Samsung Health · Saat kaynaklı ölçümler", style = MaterialTheme.typography.bodySmall)
            Text("Nabız", style = MaterialTheme.typography.titleMedium)
            if (summary?.latestHeartRateBpm != null) {
                Text("Son ölçüm: ${healthValue(summary.latestHeartRateBpm)} atım/dk")
                summary.latestHeartRateAt?.let { Text(healthTime(it), style = MaterialTheme.typography.bodySmall) }
                Text("Örnek ortalaması: ${healthValue(summary.heartRateMeanBpm!!)} · En düşük: ${healthValue(summary.heartRateMinBpm!!)} · En yüksek: ${healthValue(summary.heartRateMaxBpm!!)}")
                Text("${summary.heartRateSampleCount} ölçüm; ölçüm araları tamamlanmaz.", style = MaterialTheme.typography.bodySmall)
                if (hr.isNotEmpty()) {
                    val color = MaterialTheme.colorScheme.primary
                    val first = journey.startedAt
                    val span = duration.coerceAtLeast(1).toDouble()
                    val low = (hr.minOf { it.value } - 5)
                    val high = (hr.maxOf { it.value } + 5)
                    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "Nabız grafiği: ${hr.size} ölçüm noktası" }) {
                        hr.forEach { point ->
                            drawCircle(color, radius = 3.dp.toPx(), center = Offset(
                                4.dp.toPx() + ((point.startAt - first) / span).toFloat().coerceIn(0f, 1f) * (size.width - 8.dp.toPx()),
                                size.height - 4.dp.toPx() - ((point.value - low) / (high - low)).toFloat() * (size.height - 8.dp.toPx())))
                        }
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ölçümleri gizle" else "Nabız ölçümlerini göster") }
                    if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        hr.forEach { Text("${healthTime(it.startAt)} · ${healthValue(it.value)} atım/dk", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } else Text(missing(HealthMetric.HEART_RATE_BPM))
            HorizontalDivider()
            Text("Toplam kalori", style = MaterialTheme.typography.titleMedium)
            if (summary?.totalCaloriesKcal != null) {
                Text("${healthValue(summary.totalCaloriesKcal)} kcal")
                Text(coverage(summary.calorieCoverageMillis), style = MaterialTheme.typography.bodySmall)
                Text("Samsung Health'teki toplam enerji; aktif kalori değildir.", style = MaterialTheme.typography.bodySmall)
            } else Text(missing(HealthMetric.TOTAL_CALORIES_KCAL))
            Text("Saat adımları", style = MaterialTheme.typography.titleMedium)
            if (summary?.watchSteps != null) {
                Text("${summary.watchSteps} adım")
                Text(coverage(summary.stepCoverageMillis), style = MaterialTheme.typography.bodySmall)
            } else Text(missing(HealthMetric.STEPS))
            if (journey.transport.supportsSteps) Text("Telefon adımları ayrı tutulur; iki kaynak toplanmaz.", style = MaterialTheme.typography.bodySmall)
            summary?.lastCheckedAt?.let { Text("Son kontrol: ${healthTime(it)}", style = MaterialTheme.typography.bodySmall) }
            if (!connection.enabled && summary?.measurementStartAt != null) Text("Bağlantı kapalı; saklanan ölçümler gösteriliyor.", style = MaterialTheme.typography.bodySmall)
            connection.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
