package org.iz.navigation.ui

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.osm.OSM_GPX_MY_TRACES_URL
import org.iz.navigation.integration.osm.OSM_GPX_READ_SCOPE
import org.iz.navigation.integration.osm.OsmGpxConfirmation
import org.iz.navigation.integration.osm.OsmGpxController
import org.iz.navigation.integration.osm.OsmGpxHistoryState
import org.iz.navigation.integration.osm.OsmGpxReceipt
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.osm.OsmGpxReceiptStatus
import org.iz.navigation.integration.osm.OsmGpxStop

internal enum class OsmGpsPage(val label: String, val tag: String) {
    AVAILABLE("Yüklenebilir izler", "available"),
    HISTORY("Yüklenenler", "history"),
    UNKNOWN("Kontrol bekleyenler", "unknown"),
}

private data class OsmGpsPendingConsent(
    val confirmation: OsmGpxConfirmation,
    val selection: Set<OsmGpxReceiptKey>,
    val reviewed: OsmGpxReceipt?,
    val foregroundGeneration: Int,
    val trace: PreparedOsmGpxTrace,
    val index: Int = 0,
)

@Composable
internal fun OsmGpsScreen(
    journeys: List<Journey>,
    shortcutJourneyId: String?,
    selection: OsmGpsSelection,
    upload: OsmGpsUpload,
    page: OsmGpsPage,
    onPage: (OsmGpsPage) -> Unit,
    onLogin: (Activity) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val repository = remember(app) { DiaryRepository(app) }
    val controller = remember(app) { OsmGpxController.get(app) }
    val auth by controller.authState.collectAsStateWithLifecycle()
    val observedSession by controller.sessions.collectAsStateWithLifecycle()
    val history by controller.history.collectAsStateWithLifecycle()
    val session = observedSession?.takeIf { it.userId == auth.user?.id }
    val user = auth.user?.takeIf { session != null }
    val canRead = session?.scopes?.contains(OSM_GPX_READ_SCOPE) == true
    val readyHistory = (history as? OsmGpxHistoryState.Ready)?.takeIf { it.user == user && canRead && !auth.busy }

    var scanRevision by remember { mutableIntStateOf(0) }
    var refreshRevision by remember { mutableIntStateOf(0) }
    val eligible = remember(journeys) { journeys.filter(::osmGpsEligibleJourney) }
    var scan by remember(eligible, scanRevision) { mutableStateOf<Result<List<OsmGpsCandidate>>?>(null) }
    val summaries = scan?.getOrNull()
    val receiptKeys = remember(summaries) { summaries.orEmpty().map { it.key } }
    var lookup by remember(session, history, receiptKeys, upload.receiptRevision, refreshRevision) {
        mutableStateOf<Result<Map<OsmGpxReceiptKey, OsmGpxReceipt>>?>(null)
    }
    val receipts = lookup?.getOrNull()
    var opened by remember(session) { mutableStateOf(emptySet<OsmGpxReceipt>()) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    var consent by remember(session, page) { mutableStateOf<OsmGpsPendingConsent?>(null) }
    var preview by remember(session, page) { mutableStateOf<PreparedOsmGpxTrace?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var foregroundGeneration by remember { mutableIntStateOf(0) }

    val available = remember(summaries, receipts, readyHistory) {
        if (receipts == null || readyHistory == null) emptyList()
        else summaries.orEmpty().filter { it.key !in receipts }
    }
    val availableKeys = remember(available) { available.map { it.key }.toSet() }
    val selected = selection.keysFor(user?.id)
    val unknowns = remember(receipts) { receipts.orEmpty().values.filter { it.status == OsmGpxReceiptStatus.UNKNOWN } }
    val batch = upload.batch?.takeIf { it.accountId == user?.id }
    val canAct = !auth.busy && !upload.running && !preparing
    val pendingConsent = consent
    val validConsent = pendingConsent?.takeIf { pending ->
        val keysStillEligible = pending.reviewed?.let { receipts?.get(it.key) == it }
            ?: pending.confirmation.keys.all { it in availableKeys }
        readyHistory != null && selected == pending.selection && auth.canWriteGpx && keysStillEligible
    }

    fun owns(foreground: Int): Boolean =
        session != null && controller.sessions.value === session && !controller.authState.value.busy &&
            controller.authState.value.user?.id == session.userId && foregroundGeneration == foreground &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun openLink(url: String): Boolean = runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    }.onFailure { message = "OSM bağlantısı açılamadı." }.isSuccess

    suspend fun prepare(key: OsmGpxReceiptKey): PreparedOsmGpxTrace? {
        val journey = repository.getJourney(key.journeyId) ?: return null
        return selection.prepare(journey, repository::journeyPoints)
    }

    suspend fun preparePreview(key: OsmGpxReceiptKey): PreparedOsmGpxTrace? {
        val trace = prepare(key)?.takeIf { OsmGpxReceiptKey.of(it) == key } ?: return null
        // Decode the multipart payload off the UI thread before displaying the preview.
        return withContext(Dispatchers.IO) { trace.takeIf { it.segments.isNotEmpty() } }
    }

    fun discardConsent() {
        consent?.confirmation?.consume()
        consent = null
    }

    fun review(keys: List<OsmGpxReceiptKey>, reviewed: OsmGpxReceipt? = null) {
        val generation = foregroundGeneration
        if (!canAct || preparing || consent != null || preview != null || keys.isEmpty() ||
            readyHistory == null || !owns(generation)
        ) return
        preparing = true
        message = null
        scope.launch {
            try {
                val confirmed = controller.prepareConfirmationForKeys(keys, setOfNotNull(reviewed))
                val first = confirmed?.takeIf { it.keys == keys && it.user.id == user?.id }
                    ?.let { preparePreview(it.keys.first()) }
                if (owns(generation)) {
                    if (confirmed != null && first != null &&
                        selected == selection.keysFor(confirmed.user.id)
                    ) {
                        consent = OsmGpsPendingConsent(confirmed, selected, reviewed, generation, first)
                    } else {
                        confirmed?.consume()
                        scanRevision++
                        refreshRevision++
                        message = "Gönderim durumu değişti. Seçimini kontrol et."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owns(generation)) message = "Gönderim onayı hazırlanamadı. Yeniden dene."
            } finally {
                preparing = false
            }
        }
    }

    fun reviewTrace(pending: OsmGpsPendingConsent, index: Int) {
        val key = pending.confirmation.keys.getOrNull(index) ?: return
        if (preparing || consent !== pending || !owns(pending.foregroundGeneration)) return
        preparing = true
        scope.launch {
            try {
                val trace = preparePreview(key)
                if (consent === pending && owns(pending.foregroundGeneration)) {
                    if (trace != null) consent = pending.copy(trace = trace, index = index)
                    else {
                        discardConsent()
                        scanRevision++
                        message = "İz değişti. Gönderimi yeniden incele."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (consent === pending && owns(pending.foregroundGeneration)) {
                    discardConsent()
                    message = "İz önizlemesi hazırlanamadı."
                }
            } finally {
                preparing = false
            }
        }
    }

    fun openPreview(key: OsmGpxReceiptKey) {
        if (!canAct || preparing || preview != null || consent != null) return
        val generation = foregroundGeneration
        preparing = true
        scope.launch {
            try {
                val trace = preparePreview(key)
                if (owns(generation)) {
                    if (trace != null) preview = trace
                    else {
                        scanRevision++
                        message = "İz değişti. Güncel izi yeniden incele."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owns(generation)) message = "İz önizlemesi hazırlanamadı."
            } finally {
                preparing = false
            }
        }
    }

    val closePreviews by rememberUpdatedState {
        discardConsent()
        preview = null
    }
    DisposableEffect(lifecycle, upload) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foregroundGeneration++
                upload.stopDispatching()
                closePreviews()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            upload.stopDispatching()
        }
    }
    LaunchedEffect(pendingConsent, validConsent) {
        if (pendingConsent != null && validConsent == null && consent === pendingConsent) discardConsent()
    }
    LaunchedEffect(auth.user?.id) {
        selection.bind(auth.user?.id)
        upload.bind(auth.user?.id)
    }
    LaunchedEffect(session, canRead, auth.busy, refreshRevision) {
        if (session != null && canRead && !auth.busy) controller.refreshHistory()
    }
    LaunchedEffect(eligible, scanRevision) {
        scan = osmGpsLoad { eligible.mapNotNull { selection.summarize(it, repository::journeyPoints) } }
    }
    LaunchedEffect(session, auth.busy, history, receiptKeys, upload.receiptRevision, refreshRevision) {
        if (session == null || auth.busy || summaries == null) return@LaunchedEffect
        val result = osmGpsLoad { controller.receiptsForKeys(receiptKeys).filterValues { it.accountId == session.userId } }
        if (session === controller.sessions.value && !controller.authState.value.busy) lookup = result
    }
    LaunchedEffect(user?.id, summaries, receipts, readyHistory) {
        if (user != null && summaries != null && receipts != null && readyHistory != null) {
            selection.retain(availableKeys)
            if (shortcutJourneyId != null) {
                selection.preselect(available.firstOrNull { it.key.journeyId == shortcutJourneyId }?.key)
            }
        }
    }
    LaunchedEffect(batch?.result) {
        if (batch?.result?.stop == OsmGpxStop.STALE_CONFIRMATION) scanRevision++
    }

    Column(Modifier.fillMaxSize().testTag("osm-gps-screen")) {
        user?.let {
            Text(it.displayName, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("osm-gps-account"),
                style = MaterialTheme.typography.bodyMedium)
        }
        TabRow(selectedTabIndex = page.ordinal) {
            OsmGpsPage.entries.forEach { tab ->
                Tab(selected = page == tab, onClick = { onPage(tab) }, enabled = !preparing,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-page-${tab.tag}"),
                    text = { Text(tab.label) })
            }
        }
        if (auth.busy || history == OsmGpxHistoryState.Loading || upload.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (canRead && page != OsmGpsPage.AVAILABLE) {
            TextButton(onClick = { refreshRevision++ }, enabled = canAct && history != OsmGpxHistoryState.Loading,
                modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp).testTag("osm-gps-refresh")) {
                Text("Yenile")
            }
        }
        val listModifier = Modifier.weight(1f).padding(16.dp)
        val localFailure = page != OsmGpsPage.HISTORY && (scan?.isFailure == true || lookup?.isFailure == true)
        when {
            user == null || !canRead -> Text("İzleri görmek için bağlı OSM hesabına okuma izni ver.", listModifier)
            page == OsmGpsPage.HISTORY && readyHistory != null -> OsmGpsHistoryList(readyHistory.entries) { entry ->
                if (owns(foregroundGeneration)) openLink(entry.traceUrl(readyHistory.user))
            }
            page == OsmGpsPage.UNKNOWN && receipts != null -> OsmGpsUnknownList(unknowns, opened, canAct,
                canRetry = canAct && auth.canWriteGpx && readyHistory != null,
                onCheck = { receipt ->
                    if (owns(foregroundGeneration) && openLink(OSM_GPX_MY_TRACES_URL)) opened = opened + receipt
                },
                onRetry = { review(listOf(it.key), it) })
            history == OsmGpxHistoryState.Failed -> Text("OSM iz geçmişi yüklenemedi.", listModifier)
            localFailure -> Text("İzler ve cihazdaki gönderim durumu okunamadı.", listModifier)
            readyHistory == null || summaries == null || receipts == null -> Text("İzler yükleniyor…", listModifier)
            else -> OsmGpsCandidates(available, selected, canAct,
                onToggleAll = { selection.toggleAll(availableKeys) },
                onToggle = selection::toggle, onPreview = ::openPreview,
                rowStatus = { key -> osmGpsRowStatus(batch, key) })
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page == OsmGpsPage.AVAILABLE) Text("${selected.size} iz seçildi", Modifier.testTag("osm-gps-selection"))
            osmGpsBatchStatus(batch)?.let {
                Text(it, Modifier.testTag("osm-gps-batch-status").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
            }
            (message ?: auth.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val action = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            when {
                auth.user == null -> Button(enabled = canAct, modifier = action.testTag("osm-gps-connect"), onClick = {
                    if (!auth.clientIdConfigured) onSettings()
                    else context.findActivity()?.let(onLogin) ?: run { message = "OSM giriş ekranı açılamadı." }
                }) { Text(if (auth.clientIdConfigured) "OSM hesabını bağla" else "OSM bağlantı ayarlarını aç") }
                !canRead -> Button(enabled = canAct && user != null, modifier = action.testTag("osm-gps-read-permission"),
                    onClick = {
                        context.findActivity()?.let(controller::requestReadPermission)
                            ?: run { message = "OSM izin ekranı açılamadı." }
                    }) { Text("OSM'de iz okuma izni ver") }
                history == OsmGpxHistoryState.Failed || localFailure -> Button(enabled = canAct,
                    modifier = action.testTag("osm-gps-retry"), onClick = {
                        if (scan?.isFailure == true) scanRevision++
                        refreshRevision++
                    }) { Text("Yeniden dene") }
                page != OsmGpsPage.HISTORY && !auth.canWriteGpx -> Button(enabled = canAct && user != null,
                    modifier = action.testTag("osm-gps-permission"), onClick = {
                        context.findActivity()?.let(controller::requestPermission)
                            ?: run { message = "OSM izin ekranı açılamadı." }
                    }) { Text("OSM'de iz gönderme izni ver") }
                page == OsmGpsPage.AVAILABLE -> Button(enabled = canAct && available.any { it.key in selected },
                    modifier = action.testTag("osm-gps-review"), onClick = {
                        review(available.map { it.key }.filter { it in selected }.distinctBy { it.fileName })
                    }) { Text("Gönderimi incele") }
            }
        }
    }

    preview?.let { trace ->
        OsmGpsPreviewDialog(trace, onDismiss = { preview = null })
    }
    validConsent?.let { pending ->
        val confirmed = pending.confirmation
        OsmGpsConsentDialog(
            username = confirmed.user.displayName,
            visibility = confirmed.visibility,
            preview = OsmGpsConsentPreview(pending.trace, pending.index, confirmed.traceCount),
            enabled = canAct,
            onVisibility = { visibility ->
                if (consent === pending && !preparing && owns(pending.foregroundGeneration)) {
                    confirmed.replaceVisibility(visibility)?.let { consent = pending.copy(confirmation = it) }
                }
            },
            onPreview = { reviewTrace(pending, it) },
            onDismiss = ::discardConsent,
            onConfirm = {
                if (consent !== pending || preparing) return@OsmGpsConsentDialog
                if (owns(pending.foregroundGeneration) && confirmed.user.id == controller.authState.value.user?.id &&
                    controller.authState.value.canWriteGpx && pending.selection == selection.keysFor(confirmed.user.id)
                ) {
                    consent = null
                    upload.start(controller, confirmed, ::prepare)
                    pending.reviewed?.let { opened = opened - it }
                } else {
                    discardConsent()
                    message = "Hesap veya seçim değişti. Gönderimi yeniden incele."
                }
            },
        )
    }
}

private suspend fun <T> osmGpsLoad(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun osmGpsBatchStatus(batch: OsmGpsUpload.Batch?): String? {
    batch ?: return null
    val result = batch.result ?: return if (batch.current > 0) "${batch.current}/${batch.keys.size} iz gönderiliyor…"
        else "${batch.keys.size} iz sırada."
    val received = result.receipts.count { it.status == OsmGpxReceiptStatus.RECEIVED }
    val unknown = result.receipts.count { it.status == OsmGpxReceiptStatus.UNKNOWN }
    val counts = "$received iz gönderildi · ${result.rejectedKeys.size} reddedildi · $unknown kontrol bekliyor" +
        if (result.unattemptedCount == 0) "" else " · ${result.unattemptedCount} gönderilmedi"
    val reason = when (result.stop) {
        OsmGpxStop.COMPLETE, OsmGpxStop.UNKNOWN, OsmGpxStop.REJECTED -> null
        OsmGpxStop.RATE_LIMITED -> "OSM gönderimleri geçici olarak sınırladı." +
            (result.retryAfterMillis?.takeIf { it > 0 }?.let { " En az ${(it - 1) / 1000 + 1} sn bekle." } ?: "")
        OsmGpxStop.BACKGROUND -> "Uygulama arka plana geçti. Gönderim durdu."
        OsmGpxStop.ACCOUNT_CHANGED -> "OSM hesabı değişti. Gönderim durdu."
        OsmGpxStop.STORAGE_ERROR -> "Gönderim durumu cihazda doğrulanamadı."
        OsmGpxStop.BUSY -> "Başka bir gönderim sürüyor."
        OsmGpxStop.HISTORY_REQUIRED -> "OSM iz geçmişini yenile."
        OsmGpxStop.ALREADY_USED, OsmGpxStop.STALE_CONFIRMATION -> "Gönderimi yeniden incele."
    }
    return if (reason == null) counts else "$counts\n$reason"
}

private fun osmGpsRowStatus(batch: OsmGpsUpload.Batch?, key: OsmGpxReceiptKey): String? {
    batch ?: return null
    val index = batch.keys.indexOf(key)
    if (index < 0) return null
    val result = batch.result
    if (result == null) return when {
        index + 1 == batch.current -> "Gönderiliyor…"
        index + 1 > batch.current -> "Sırada"
        else -> null
    }
    return when (result.rejectedKeys[key]) {
        400, 413, 422 -> "OSM GPX dosyasını kabul etmedi."
        401 -> "OSM oturumu sona erdi."
        403 -> "OSM bu hesap için gönderime izin vermedi."
        429 -> "OSM gönderimleri geçici olarak sınırladı."
        null -> "Bu iz gönderilmedi."
        else -> "OSM isteği kabul etmedi."
    }
}
