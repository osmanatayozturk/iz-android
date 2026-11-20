package org.iz.navigation.watch

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import org.iz.navigation.wearprotocol.*

internal enum class WatchPage(val id: String, val title: String) {
    DAILY("wdaily", "Bugün"), START("wstart", "Vasıta ve kayıt"), RECORD("wrecord", "Yolculuk kaydı"),
    NAV("wnav", "Yönlendirme"), WEATHER("wweather", "Yolculuk havası"), SUMMARY("wsummary", "Yolculuk özeti"),
    END("wend", "Kayıt bitsin mi?"), HEALTH("whealth", "Saat ölçümleri"), SETTINGS("wsettings", "Bağlantı ve ayarlar")
}

private fun WatchSurfaceRoute.page() = when (this) {
    WatchSurfaceRoute.DAILY -> WatchPage.DAILY
    WatchSurfaceRoute.RECORDING -> WatchPage.RECORD
    WatchSurfaceRoute.NAVIGATION -> WatchPage.NAV
    WatchSurfaceRoute.SUMMARY -> WatchPage.SUMMARY
}

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
    onRebindPhone: () -> Unit = {},
    surfaceData: WatchSurfaceData? = null,
    entry: WatchSurfaceRoute? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    // A cached report may explain a disconnected screen. It never authorizes a command.
    val cached = surfaceData?.takeIf { state.snapshotAfter == null &&
        (state.phoneId == null || state.phoneId == it.phoneId && state.phoneVersion == it.version) }
    val snapshot = state.snapshot ?: cached?.snapshot
    val connected = if (state.snapshot != null) state.phoneId != null else cached?.connected == true
    val fresh = WatchSurfacePolicy.phoneState(snapshot, connected, now) == WatchPhoneState.CURRENT
    val frame = WatchSurfacePolicy.frame(snapshot, connected, now)
    val root = entry?.page() ?: when (frame.route) {
        WatchSurfaceRoute.NAVIGATION -> WatchPage.NAV
        WatchSurfaceRoute.RECORDING -> WatchPage.RECORD
        else -> WatchPage.DAILY
    }
    var history by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val page = history.lastOrNull()?.let(WatchPage::valueOf) ?: root
    fun open(next: WatchPage) {
        if (next == page) return
        val current = history.ifEmpty { listOf(page.name) }
        val prior = current.indexOf(next.name)
        history = ArrayList(if (prior >= 0) current.take(prior + 1) else current + next.name)
    }
    fun back() { history = ArrayList(history.dropLast(1)) }
    val canBack = history.size > 1
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = canBack || resetConfirm) { if (resetConfirm) resetConfirm = false else back() }

    val holder = rememberSaveableStateHolder()
    var mode by rememberSaveable { mutableStateOf(WearMode.MOTORCYCLE.name) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmPhone by rememberSaveable { mutableStateOf<String?>(null) }
    var stopRequested by rememberSaveable { mutableStateOf(false) }
    var summaryFor by rememberSaveable { mutableStateOf<String?>(null) }
    val activeId = state.snapshot?.takeIf { it.recording }?.journeyId
    val active = snapshot?.journeyId != null
    val nav = frame.route == WatchSurfaceRoute.NAVIGATION
    val phoneLabel = WatchSurfacePolicy.phoneLabel(snapshot, connected, now)
    val age = snapshot?.let { WatchSurfacePolicy.phoneAge(it, now) }

    LaunchedEffect(page, activeId, state.phoneId, state.pending, state.snapshot?.generatedAt, fresh) {
        if (page == WatchPage.START && state.fresh(now) && activeId != null) {
            history = arrayListOf(if (nav) WatchPage.NAV.name else WatchPage.RECORD.name)
        }
        if (page == WatchPage.END && state.fresh(now) && state.pending == null &&
            (state.phoneId != confirmPhone || activeId != confirmId)) {
            val showSummary = stopRequested && state.phoneId == confirmPhone && state.snapshot?.journeyId == null
            if (showSummary) summaryFor = confirmId
            history = arrayListOf(if (showSummary) WatchPage.SUMMARY.name else root.name)
            confirmId = null
            stopRequested = false
        }
        if (page != WatchPage.SETTINGS) resetConfirm = false
    }

    holder.SaveableStateProvider(page.id) {
        WatchPageLayout(page.id, page.title, WatchPalette.from(snapshot?.paletteId),
            if (entry != null) "surface-detail" else "watch-list") {
            if (page == WatchPage.START && !state.fresh(now)) item {
                val reason = if (state.phoneId == null) "Kaydı başlatmak için telefon bağlantısı gerekiyor."
                    else "Kaydı başlatmak için telefondan güncel durum bekleniyor."
                Note(listOfNotNull(reason, age).joinToString(" · "), highlighted = true)
            } else if (page != WatchPage.SETTINGS && !fresh) item {
                Note(listOfNotNull(phoneLabel, age).joinToString(" · "), highlighted = true)
            }
            if (state.pending != null || page == WatchPage.START || page == WatchPage.END || page == WatchPage.SETTINGS) {
                state.message?.let { item { Note(it, highlighted = state.needsPhone) } }
                state.pending?.let { request -> item {
                    Note(when {
                        state.needsPhone -> "Devam etmek için telefonda İz bildirimini aç. Onay bekleniyor."
                        now - request.requestedAt >= 15_000 -> "Sonuç bekleniyor. Telefonda İz’i kontrol et veya durumu yenile."
                        else -> "İstek gönderildi. Telefonun yanıtı bekleniyor."
                    }, highlighted = true)
                } }
            }
            when (page) {
                WatchPage.DAILY -> {
                    val daily = WatchSurfacePolicy.day(snapshot, now)
                    item { WatchValue(daily?.samsungSteps?.let { "$it adım" }
                        ?: if (daily != null) WatchSurfacePolicy.missing(daily.stepsStatus) else "Özet bekleniyor") }
                    if (fresh) item { Note(listOfNotNull(phoneLabel, age).joinToString(" · ")) }
                    if (nav) item { WatchAction("Yönlendirmeyi aç", { open(WatchPage.NAV) }, primary = true) }
                    item { WatchAction(if (active) "Kaydı aç" else "Kaydı başlat",
                        { open(if (active) WatchPage.RECORD else WatchPage.START) }, primary = !nav, tag = "open-record") }
                    item { WatchAction("Son yolculuk", { summaryFor = null; open(WatchPage.SUMMARY) }, tag = "completed-summary") }
                    item { WatchAction("Bağlantı ve ayarlar", { open(WatchPage.SETTINGS) }) }
                    if (daily != null) dailyDetails(daily)
                }
                WatchPage.START -> {
                    item { WatchValue(WearMode.valueOf(mode).label()) }
                    if (state.fresh(now)) item { Note(listOfNotNull(phoneLabel, age).joinToString(" · ")) }
                    item { WatchAction("Telefonda kaydı başlat", { onStart(WearMode.valueOf(mode)) },
                        primary = true, enabled = state.canStart(now, WearMode.valueOf(mode)), tag = "start") }
                    (listOf(WearMode.MOTORCYCLE) + WearMode.entries.filter { it != WearMode.MOTORCYCLE }).forEach { value ->
                        val chosen = value.name == mode
                        item { WatchAction(value.label() + if (chosen) " · Seçili" else "", { mode = value.name },
                            enabled = state.pending == null && state.supports(value), modifier = Modifier.semantics { selected = chosen }) }
                    }
                    if (state.phoneId != null && state.phoneVersion < 2) item { Note("Koşu ve sağlık özeti için telefondaki İz'i güncelle.") }
                    item { WatchAction("Durumu yenile", onRefresh, tag = "refresh") }
                }
                WatchPage.RECORD -> {
                    item { WatchValue(when {
                        !active -> "Etkin kayıt yok"
                        !fresh -> "Son kayıt durumu"
                        snapshot?.recording != true -> "Kayıt etkin değil"
                        snapshot.temporary -> "Algılama sürüyor"
                        else -> "Kayıt sürüyor"
                    }) }
                    if (snapshot != null && active) {
                        if (fresh) age?.let { item { Note(it) } }
                        snapshot.mode?.let { item { Note(it.label()) } }
                        if (snapshot.statsDeferred) {
                            snapshot.startedAt?.let { start ->
                                val observed = if (fresh && snapshot.recording) now else snapshot.generatedAt
                                if (observed >= start) item { Note("Başlangıçtan beri ${duration(observed - start)}") }
                            }
                            item { Note("Mesafe yolculuk sonunda hazırlanacak.") }
                        } else {
                            item { Note("${duration(snapshot.elapsedMillis)} · ${WatchSurfacePolicy.km(snapshot.distanceMeters)}") }
                            if (snapshot.temporary) {
                                val progress = snapshot.candidateProgressMeters
                                    ?: snapshot.distanceMeters.takeIf { state.phoneVersion < 4 }
                                progress?.let { item { Note("${it.coerceIn(0.0, 500.0).toInt()} / 500 m") } }
                                if (fresh) snapshot.deadlineAt?.let { item { Note("Kalan ${duration(it - now)}") } }
                            }
                        }
                        item { WatchAction("Yolculuğu bitir", {
                            confirmId = activeId
                            confirmPhone = state.phoneId
                            stopRequested = false
                            open(WatchPage.END)
                        }, enabled = activeId?.let { state.canStop(it, now) } == true, tag = "stop") }
                    } else item { WatchAction("Vasıta ve kayıt", { open(WatchPage.START) }, primary = true) }
                    if (nav) item { WatchAction("Yönlendirmeyi aç", { open(WatchPage.NAV) }) }
                    item { WatchAction("Ölçümler ve kaynak", { open(WatchPage.HEALTH) }) }
                    item { WatchAction("Yolculuk havası", { open(WatchPage.WEATHER) }) }
                    item { WatchAction("Bağlantı ve ayarlar", { open(WatchPage.SETTINGS) }) }
                    item { WatchAction("Bugün", { open(WatchPage.DAILY) }) }
                }
                WatchPage.NAV -> {
                    if (nav) {
                        item { WatchValue(frame.title) }
                        frame.lines.take(2).filter { it.isNotBlank() }.forEach { item { Note(it) } }
                    } else item { WatchValue("Etkin yönlendirme yok") }
                    item { WatchAction(if (active) "Yolculuk kaydı" else "Vasıta ve kayıt",
                        { open(if (active) WatchPage.RECORD else WatchPage.START) }, primary = true) }
                    item { WatchAction("Kayıt ve ayarlar", { onOpenSettings?.invoke() ?: open(WatchPage.SETTINGS) }, tag = "surface-controls") }
                    item { WatchAction("Yolculuk havası", { open(WatchPage.WEATHER) }) }
                    if (nav) frame.lines.drop(2).filter { it.isNotBlank() }.forEach { item { Note(it) } }
                    item { WatchAction("Bugün", { open(WatchPage.DAILY) }) }
                }
                WatchPage.WEATHER -> {
                    snapshot?.weather?.let { weather -> item { WeatherCard(weather, now, fresh,
                        WatchSurfacePolicy.ownerAdvisory(snapshot)?.paused == true) } }
                        ?: item { Note("Telefondan bu yolculuk için hava bilgisi bekleniyor.") }
                    age?.let { item { Note(it) } }
                    item { WatchAction("Sürüşe dön", { if (canBack) back() else open(if (nav) WatchPage.NAV else WatchPage.RECORD) }) }
                }
                WatchPage.SUMMARY -> {
                    val deferred = WatchSurfacePolicy.statisticsDeferred(snapshot)
                    val summary = snapshot?.completed?.takeIf { !deferred && (summaryFor == null || summaryFor == it.journeyId) }
                    if (summary != null) {
                        item { Note(if (fresh) "İstatistikler hazır" else "Son hazırlanmış yolculuk") }
                        completedDetails(summary)
                    } else item { WatchValue(if (deferred || summaryFor != null || snapshot == null) "Özet hazırlanıyor" else "Hazır yolculuk özeti yok") }
                    item { WatchAction("Bugüne dön", { open(WatchPage.DAILY) }, primary = true) }
                }
                WatchPage.END -> {
                    item { Note(if (snapshot?.temporary == true) "Geçici ölçüm duracak. Saklamak için telefonda Sakla’yı seç."
                        else "Bu yolculuğun telefon kaydı durdurulacak.") }
                    snapshot?.mode?.let { item { Note(it.label()) } }
                    confirmId?.let { id -> item { WatchAction("Evet, bitir", { stopRequested = true; onStop(id) },
                        primary = true, enabled = state.phoneId == confirmPhone && state.canStop(id, now), tag = "confirm-stop") } }
                    item { WatchAction("Vazgeç", { back() }, enabled = state.pending == null) }
                    if (!fresh) item { WatchAction("Durumu yenile", onRefresh) }
                }
                WatchPage.HEALTH -> {
                    item { WatchValue(if (liveHealth.armed) "Otomatik ölçüm açık" else "Otomatik ölçüm kapalı") }
                    if (liveHealth.status.isNotBlank()) item { Note(liveHealth.status, highlighted = liveHealth.capturing) }
                    if (liveHealth.capturing && liveHealth.phoneId == state.phoneId && activeId != null &&
                        liveHealth.journeyId == activeId) item {
                        Note(liveHealth.latestSampleAt?.let { "Saat sensörü · Son ölçüm ${WatchSurfacePolicy.age(it, now)}" }
                            ?: "Saat sensöründen ilk ölçüm bekleniyor.")
                    }
                    item { Note("Saat sensörleri kesinleşen kayıtta nabız ve adımı ölçer. İz, kayıtlar arasında sensörleri kapatır.") }
                    if (snapshot != null && active && !snapshot.statsDeferred && state.phoneVersion < 6) {
                        snapshot.averagePaceSecondsPerKm?.let { item { Note(pace(it)) } }
                        snapshot.stepCount?.let { item { Measurement("Telefonda ölçülen adımlar", it.toString()) } }
                        snapshot.health?.let { report ->
                            item { Note("Samsung Health · Saat") }
                            item { Measurement("Saatte ölçülen adımlar", report.watchSteps?.toString() ?: "Ölçüm yok") }
                            item { Measurement("Toplam enerji · gecikmeli", report.totalCaloriesKcal?.let { "${it.toInt()} kcal" } ?: "Ölçüm yok") }
                            if (report.partial) item { Note("Kısmi sağlık verisi. Eksik ölçümler sıfır sayılmaz.") }
                            report.lastCheckedAt?.let { item { Note("Son aktarım kontrolü ${WatchSurfacePolicy.age(it, now)}. Saat verisi gecikmeli gelebilir.") } }
                        }
                    }
                    if (liveHealth.buffered > 0) item { Note("Telefona aktarılmayı bekleyen ${liveHealth.buffered} ölçüm") }
                    if (liveHealth.armed) item { WatchAction("Otomatik ölçümü kapat", onDisarmHealth, tag = "disarm-health") }
                    else item { WatchAction("Otomatik ölçümü aç", onArmHealth, primary = true, tag = "arm-health") }
                    item { Note("Otomatik ölçüm için saatte sensör, fiziksel etkinlik ve arka plan ölçüm izni gerekir. Konumu telefon kaydeder.") }
                    item { WatchAction("Saat uygulama izinleri", onHealthSettings) }
                }
                WatchPage.SETTINGS -> {
                    item { WatchValue(phoneLabel) }
                    state.phoneName?.let { item { Note(it) } }
                    age?.let { item { Note(it) } }
                    item { WatchAction("Durumu yenile", onRefresh, primary = true, tag = "refresh") }
                    item { WatchAction("Ölçümler ve izinler", { open(WatchPage.HEALTH) }) }
                    item { WatchAction(if (active) "Yolculuk kaydı" else "Vasıta ve kayıt",
                        { open(if (active) WatchPage.RECORD else WatchPage.START) }) }
                    if (resetConfirm) {
                        item { Note("İz bu telefona ait bağlantı bilgisini ve aktarılmamış saat ölçümlerini silecek. Telefona ulaşmış kayıtlar korunur.") }
                        item { WatchAction("Evet, bağlantıyı sıfırla", { resetConfirm = false; onRebindPhone() },
                            primary = true, enabled = state.pending == null, tag = "confirm-health-rebind") }
                        item { WatchAction("Vazgeç", { resetConfirm = false }) }
                    } else item { WatchAction("Telefon eşleşmesini sıfırla", { resetConfirm = true }, enabled = state.pending == null, tag = "health-rebind") }
                }
            }
            if (canBack && page != WatchPage.END && page != WatchPage.WEATHER) item { WatchAction("Geri", ::back) }
        }
    }
}
