package org.iz.navigation.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import org.iz.navigation.wearprotocol.*
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val Pine = Color(0xFF091B15)
private val Leaf = Color(0xFFB0D998)
private val Surface = Color(0xFF163328)
private val Cream = Color(0xFFF0F4DF)
private val Quiet = Color(0xFFB5C7BB)

@Composable
internal fun WatchScreen(
    state: WatchUiState,
    now: Long,
    onStart: (WearMode) -> Unit,
    onStop: (String) -> Unit,
    onRefresh: () -> Unit,
    liveHealth: WatchLiveHealthState = WatchLiveHealthState(),
    onArmHealth: () -> Unit = {},
    onDisarmHealth: () -> Unit = {},
    onHealthSettings: () -> Unit = {},
    onRebindHealth: () -> Unit = {},
    surfaceData: WatchSurfaceData? = null,
    onSurface: (WatchSurfaceRoute) -> Unit = {},
) {
    val context = LocalContext.current
    val currentHeart = safeLiveHeart(context, WatchSurfaceData(state.phoneId, state.phoneVersion, state.snapshot, state.phoneId != null), now)
    val list = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var selectedMode by rememberSaveable { mutableStateOf(WearMode.WALK.name) }
    var confirmingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingRebind by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot
    val fresh = state.fresh(now)
    val activeId = snapshot?.journeyId?.takeIf { snapshot.recording }
    LaunchedEffect(activeId, state.phoneId) {
        if (confirmingId != activeId) confirmingId = null
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(confirmingId) { if (confirmingId != null) list.scrollToItem(2) }

    MaterialTheme(colors = Colors(primary = Leaf, onPrimary = Pine, secondary = Color(0xFFDADBA1),
        onSecondary = Pine, background = Pine, onBackground = Cream, surface = Surface, onSurface = Cream,
        error = Color(0xFFF0ADA2), onError = Pine)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().background(Pine),
            timeText = { TimeText(modifier = Modifier.scrollAway(list)) },
            positionIndicator = { PositionIndicator(scalingLazyListState = list) },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        ) {
            ScalingLazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("watch-list")
                    .onRotaryScrollEvent { event ->
                        scope.launch { list.scrollBy(event.verticalScrollPixels) }
                        true
                    }.focusRequester(focusRequester).focusable(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("İz", color = Leaf, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        Text(when {
                            state.phoneId == null -> "Telefon bağlı değil"
                            snapshot == null -> "Telefon verisi bekleniyor"
                            !fresh -> "Veri güncel değil"
                            else -> "Telefon bağlı"
                        }, color = if (fresh) Leaf else Quiet, textAlign = TextAlign.Center, fontSize = 12.sp)
                        state.phoneName?.let { Text(it, fontSize = 10.sp, color = Quiet, maxLines = 2, textAlign = TextAlign.Center) }
                    }
                }
                surfaceData?.let { data ->
                    val frame = WatchSurfacePolicy.frame(data.snapshot, data.connected, now)
                    item { Chip(onClick = { onSurface(frame.route) }, label = { Text(frame.title) },
                        secondaryLabel = { Text(frame.lines.firstOrNull().orEmpty(), maxLines = 2) },
                        modifier = Modifier.fillMaxWidth().testTag("open-surface")) }
                    if (frame.route != WatchSurfaceRoute.DAILY) item { Chip(onClick = { onSurface(WatchSurfaceRoute.DAILY) },
                        label = { Text("Bugünün özeti") }, modifier = Modifier.fillMaxWidth()) }
                }
                if (!fresh) item {
                    Note(if (state.phoneId == null) "İz açık olan eşleşmiş telefonunu yaklaştır. Kayıt telefon üzerinden yapılır."
                        else if (snapshot == null) "Başlatmadan önce telefonun güncel durumu bekleniyor."
                        else "Son ölçümler gösteriliyor. Bağlantı yenilenene kadar başlatma ve bitirme kapalı.")
                }
                state.message?.let { message -> item { Note(message, highlighted = state.needsPhone) } }
                if (state.pending != null) item {
                    Note(if (state.needsPhone) "Devam etmek için telefonda İz bildirimine dokun. Telefon onayı bekleniyor."
                        else if (now - state.pending.requestedAt >= 15_000) "Telefon sonucu henüz doğrulanmadı. Telefonu kontrol et veya durumu yenile."
                        else "İstek gönderildi. Telefonun yanıtı bekleniyor.", highlighted = true)
                }
                if (confirmingId != null) {
                    item { Text("Yolculuk bitsin mi?", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
                    item { Note(if (snapshot?.temporary == true)
                        "Geçici ölçüm duracak. Kalıcı saklamak için telefonda Sakla’yı seçmelisin."
                        else "Bu yolculuğun telefon kaydı durdurulacak.") }
                    item {
                        Chip(onClick = { confirmingId?.let(onStop); confirmingId = null },
                            label = { Text("Evet, bitir") }, enabled = confirmingId?.let { state.canStop(it, now) } == true,
                            colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("confirm-stop"))
                    }
                    item {
                        Chip(onClick = { confirmingId = null }, label = { Text("Vazgeç") },
                            colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
                    }
                } else if (snapshot?.journeyId != null) {
                    item { Text(snapshot.mode?.label() ?: "Yolculuk", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    if (!snapshot.recording) item { Note("Telefonun kaydı etkin değil. Telefonda İz'i kontrol et.") }
                    item {
                        Metric("Mesafe", String.format(Locale.forLanguageTag("tr"), "%.2f km", snapshot.distanceMeters / 1000.0), large = true)
                    }
                    item {
                        val elapsed = snapshot.elapsedMillis + if (snapshot.recording && fresh)
                            (now - snapshot.generatedAt).coerceIn(0, WearProtocol.STATE_TTL_MS) else 0
                        Metric("Süre", duration(elapsed))
                    }
                    snapshot.weather?.let { weather -> item { WeatherCard(weather, now, fresh) } }
                    if (snapshot.temporary) {
                        item {
                            val remaining = (snapshot.deadlineAt?.minus(now) ?: 0L).coerceAtLeast(0)
                            Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                val progressMeters = snapshot.candidateProgressMeters ?: snapshot.distanceMeters
                                CircularProgressIndicator(progress = (progressMeters / 500.0).toFloat().coerceIn(0f, 1f),
                                    modifier = Modifier.size(48.dp), indicatorColor = Leaf, trackColor = Pine, strokeWidth = 4.dp)
                                Spacer(Modifier.height(6.dp))
                                Text("${progressMeters.toInt().coerceIn(0, 500)} / 500 m", fontWeight = FontWeight.Bold)
                                Text("Kalan ${duration(remaining)}", fontSize = 12.sp, color = Quiet)
                                Text("15 dakikada tamamlanmazsa sıfırlanır", fontSize = 11.sp, textAlign = TextAlign.Center, color = Quiet)
                            }
                        }
                    }
                    item { Metric("Anlık hız", speed(snapshot.currentSpeedKmh)) }
                    item { Metric("Ortalama hız", speed(snapshot.averageSpeedKmh)) }
                    item { Metric("En yüksek hız", speed(snapshot.maxSpeedKmh)) }
                    if (snapshot.mode == WearMode.RUN) item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Metric("Ortalama tempo", pace(snapshot.averagePaceSecondsPerKm))
                            Text("Duraklamalar dahil", fontSize = 10.sp, color = Quiet)
                        }
                    }
                    if (snapshot.mode == WearMode.WALK || snapshot.mode == WearMode.RUN) item {
                        Metric("Telefonda ölçülen adımlar", snapshot.stepCount?.let { String.format(Locale.forLanguageTag("tr"), "%,d", it) } ?: "Ölçüm yok")
                    }
                    item {
                        Chip(onClick = { confirmingId = activeId }, label = { Text("Yolculuğu bitir") },
                            enabled = activeId?.let { state.canStop(it, now) } == true,
                            colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("stop"))
                    }
                    if (state.phoneVersion >= 2 && !snapshot.temporary) {
                        val health = snapshot.health
                        item { Text("Samsung Health · Saat", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
                        if (health == null) item { Note("Bu yolculuk için saat sağlık verisi henüz yok. Telefonda bağlantıyı kontrol et.") }
                        else {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Metric("Son ölçülen nabız", bpm(health.latestHeartRateBpm))
                                    Text("Ölçüm: ${measurementTime(health.latestHeartRateAt)}", fontSize = 10.sp, color = Quiet, textAlign = TextAlign.Center)
                                }
                            }
                            item { Metric("Ortalama nabız", bpm(health.heartRateMeanBpm)) }
                            item { Note("En düşük ${bpm(health.heartRateMinBpm)}\nEn yüksek ${bpm(health.heartRateMaxBpm)}") }
                            item { Metric("Saatte ölçülen adımlar", health.watchSteps?.let { String.format(Locale.forLanguageTag("tr"), "%,d", it) } ?: "Ölçüm yok") }
                            item { Metric("Toplam enerji · gecikmeli", health.totalCaloriesKcal?.let { String.format(Locale.forLanguageTag("tr"), "%.0f kcal", it) } ?: "Ölçüm yok") }
                            item { Note("Toplam enerji, dinlenme enerjisini de içerir.") }
                            item { Note("Ölçüm aralığı\n${measurementTime(health.measurementStartAt)}\n${measurementTime(health.measurementEndAt)}") }
                            item { Note("Adım kapsamı: ${duration(health.stepCoverageMillis)}\nEnerji kapsamı: ${duration(health.calorieCoverageMillis)}") }
                            if (health.partial) item { Note("Kısmi sağlık verisi. Eksik ölçümler sıfır sayılmaz.") }
                            item { Note("Son aktarım kontrolü\n${measurementTime(health.lastCheckedAt)}\nSaat verisi gecikmeli gelebilir.") }
                        }
                    }
                } else {
                    item { Text("Nasıl gidiyorsun?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    WearMode.entries.forEach { mode ->
                        item {
                            val chosen = selectedMode == mode.name
                            Chip(onClick = { selectedMode = mode.name },
                                label = { Text(mode.label()) },
                                secondaryLabel = if (chosen) ({ Text("Seçili") }) else null,
                                enabled = state.pending == null && state.supports(mode),
                                colors = if (chosen) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth().semantics { selected = chosen })
                        }
                    }
                    if (state.phoneVersion < 2) item { Note("Koşu ve sağlık özeti için telefondaki İz'i güncelle.") }
                    item {
                        Chip(onClick = { onStart(WearMode.valueOf(selectedMode)) }, label = { Text("Başlat") },
                            enabled = state.canStart(now, WearMode.valueOf(selectedMode)), colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth().testTag("start"))
                    }
                }
                item { Text("İz · Yolculukta saat ölçümü", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
                item { Note(liveHealth.status, highlighted = liveHealth.capturing) }
                if (liveHealth.armed) {
                    item { Note("Nabız ve adım yalnızca kesinleşen yolculuklarda ölçülür. Kayıt dışında bilek sensörü de kapalıdır. Sessiz bildirim, sonraki kayda hazır olduğunu gösterir.") }
                    if (liveHealth.latestHeartAt != null && snapshot?.recording == true && !snapshot.temporary && liveHealth.journeyId == snapshot.journeyId) item {
                        Metric(if (currentHeart != null) "Canlı nabız" else "Son nabız · eski ölçüm",
                            bpm(currentHeart))
                        Note(measurementTime(liveHealth.latestHeartAt))
                    }
                    liveHealth.steps?.takeIf { snapshot?.recording == true && !snapshot.temporary && liveHealth.journeyId == snapshot.journeyId && liveHealth.phoneId == state.phoneId }?.let { item { Metric("İz saatte ölçülen adımlar", "$it") } }
                    if (liveHealth.buffered > 0) item { Note("Telefona aktarılmayı bekleyen ${liveHealth.buffered} ölçüm") }
                    item { Chip(onClick = onDisarmHealth, label = { Text("Otomatik ölçümü kapat") },
                        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("disarm-health")) }
                } else {
                    item { Note("Bir kez aç: kayıt başlayınca ölçüm otomatik açılır, bitince sensörler kapanır. Kayıt beklerken sessiz bildirim kalır. Sistem başlatmayı engellerse saatte İz'i açabilirsin.") }
                    item { Chip(onClick = onArmHealth, label = { Text("Otomatik ölçümü aç") },
                        colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("arm-health")) }
                }
                item { Chip(onClick = onHealthSettings, label = { Text("Saat uygulama izinleri") },
                    colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                if (confirmingRebind) {
                    item { Note("Bekleyen saat ölçümleri silinecek ve telefon bağlantısı yeniden kurulacak. Telefona ulaşmış kayıtlar korunur.") }
                    item { Chip(onClick = { confirmingRebind = false; onRebindHealth() }, label = { Text("Evet, bağlantıyı sıfırla") },
                        colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("confirm-health-rebind")) }
                    item { Chip(onClick = { confirmingRebind = false }, label = { Text("Vazgeç") },
                        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                } else item { Chip(onClick = { confirmingRebind = true }, label = { Text("Telefon sağlık bağlantısını sıfırla") },
                    colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("health-rebind")) }
                item {
                    Chip(onClick = onRefresh, label = { Text("Durumu yenile") },
                        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().testTag("refresh"))
                }
                snapshot?.let { value -> item {
                    val age = ((now - value.generatedAt).coerceAtLeast(0) / 1_000).coerceAtMost(99_999)
                    Text("Son veri: ${age} sn önce", fontSize = 10.sp, textAlign = TextAlign.Center, color = Quiet)
                } }
            }
        }
    }
}

@Composable
private fun WeatherCard(weather: WearRouteWeather, now: Long, connectionFresh: Boolean) {
    val readyAndFresh = connectionFresh && weather.status == WearWeatherStatus.READY && now < weather.validUntil
    val accent = if (readyAndFresh && weather.threshold == WearWeatherThreshold.EXCEEDED) {
        MaterialTheme.colors.error
    } else if (readyAndFresh) Leaf else Quiet
    Column(
        Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(12.dp)
            .testTag("weather-card"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Yolculuk havası", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        when {
            !connectionFresh -> {
                Text("Telefon bağlantısı/verisi güncel değil", color = Quiet, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
                if (weather.headline.isNotBlank()) Text(weather.headline, color = Quiet, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = Quiet,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
                Text("Geçerlilik: ${measurementTime(weather.validUntil)}", color = Quiet,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            weather.status == WearWeatherStatus.LOADING -> {
                Text(weather.headline.ifBlank { "Hava hazırlanıyor" }, color = Quiet,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Tahmin telefonda hesaplanıyor.", color = Quiet, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
            }
            weather.status == WearWeatherStatus.ERROR -> {
                Text(weather.headline.ifBlank { "Hava verisi alınamadı" }, color = Quiet,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (weather.detail.isNotBlank()) Text(weather.detail, color = Quiet, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
                Text("Tahmin kullanılamıyor", color = Quiet, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = Quiet,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            !readyAndFresh -> {
                Text(weather.headline, color = Quiet, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Tahmin süresi doldu", color = Quiet, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = Quiet,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
            else -> {
                Text(weather.headline, color = accent, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (weather.detail.isNotBlank()) Text(weather.detail, color = Quiet, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
                if (weather.remainingMeters > 0) Text(
                    "Kalan rota: ${String.format(Locale.forLanguageTag("tr"), "%.1f km", weather.remainingMeters / 1_000.0)}",
                    color = Cream, fontSize = 11.sp, textAlign = TextAlign.Center)
                if (weather.threshold == WearWeatherThreshold.EXCEEDED) weather.hazardStartsAt?.let {
                    Text("Eşik yaklaşık ${measurementTime(it)}", color = accent, fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                }
                weather.arrivalAt?.let {
                    Text("Varış yaklaşık ${measurementTime(it)}", color = Quiet, fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                }
                Text("Tahmin: ${measurementTime(weather.calculatedAt)}", color = Quiet,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, large: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Quiet, fontSize = 11.sp, textAlign = TextAlign.Center)
        Text(value, color = Cream, fontSize = if (large) 26.sp else 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Note(text: String, highlighted: Boolean = false) {
    Text(text, color = if (highlighted) Leaf else Quiet, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp))
}

internal fun duration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

private fun speed(value: Double?): String = value?.let { String.format(Locale.forLanguageTag("tr"), "%.1f km/sa", it) } ?: "Ölçüm yok"

private fun bpm(value: Double?): String = value?.let { String.format(Locale.forLanguageTag("tr"), "%.0f atım/dk", it) } ?: "Ölçüm yok"
private fun measurementTime(value: Long?): String = value?.let {
    SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("tr")).format(Date(it))
} ?: "Ölçüm yok"
