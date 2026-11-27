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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Journey
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.osm.OSM_GPX_MY_TRACES_URL
import org.iz.navigation.integration.osm.OSM_GPX_READ_SCOPE
import org.iz.navigation.integration.osm.OsmGpxController
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxHistoryState
import org.iz.navigation.integration.osm.OsmGpxPlan
import org.iz.navigation.integration.osm.OsmGpxReceipt
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.osm.OsmGpxReceiptStatus
import org.iz.navigation.integration.osm.OsmGpxStop
import org.iz.navigation.integration.osm.OsmGpxVisibility

internal enum class OsmGpsPage(val label: String, val tag: String) {
    AVAILABLE("Adaylar", "available"),
    HISTORY("Yüklenenler", "history"),
    UNKNOWN("Belirsiz yüklemeler", "unknown"),
}

private data class OsmGpsPendingConsent(
    val plan: OsmGpxPlan,
    val selection: Set<OsmGpxReceiptKey>,
    val history: OsmGpxHistoryState.Ready,
    val foregroundGeneration: Int,
    val index: Int = 0,
)

private data class OsmGpsCandidateScan(
    val available: List<OsmGpsCandidate>,
    val blocked: List<OsmGpsBlockedCandidate>,
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
    val controller = remember(app) { OsmGpxController.get(app) }
    val auth by controller.authState.collectAsStateWithLifecycle()
    val observedSession by controller.sessions.collectAsStateWithLifecycle()
    val history by controller.history.collectAsStateWithLifecycle()
    val session = observedSession?.takeIf { it.userId == auth.user?.id && !auth.busy }
    val user = auth.user?.takeIf { session != null }
    val canRead = session?.scopes?.contains(OSM_GPX_READ_SCOPE) == true
    val readyHistory = (history as? OsmGpxHistoryState.Ready)?.takeIf { it.user == user && canRead }

    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var foregroundGeneration by remember { mutableIntStateOf(0) }
    var work by remember(session, page) { mutableStateOf<Job?>(null) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    var consent by remember(session, page) { mutableStateOf<OsmGpsPendingConsent?>(null) }
    var preview by remember(session, page) { mutableStateOf<PreparedOsmGpxTrace?>(null) }
    var visibilitySelection by remember(session, page, readyHistory) { mutableStateOf(emptySet<Long>()) }
    var visibilityReview by remember(session, page, readyHistory) { mutableStateOf<List<OsmGpxHistoryEntry>?>(null) }
    var visibilityResults by remember(session) { mutableStateOf(emptyMap<Long, String>()) }
    var changingVisibility by remember(session, page) { mutableStateOf(false) }

    val coverage = readyHistory?.takeIf { it.coverageReady && started }
    val eligible = remember(journeys, shortcutJourneyId) {
        journeys.filter { osmGpsEligibleJourney(it) && (shortcutJourneyId == null || it.id == shortcutJourneyId) }
    }
    var scan by remember(session, coverage, eligible, scanRevision, upload.receiptRevision) {
        mutableStateOf<OsmGpsCandidateScan?>(null)
    }
    val available = scan?.available
    val availableKeys = remember(available) { available.orEmpty().map { it.key }.toSet() }
    val selected = selection.keysFor(user?.id)
    val requestedIds = remember(available, selected) {
        available.orEmpty().filter { it.key in selected }.map { it.key.journeyId }
    }
    var planned by remember(session, coverage, selected, requestedIds, eligible, scanRevision, upload.receiptRevision) {
        mutableStateOf<Result<OsmGpxPlan>?>(null)
    }
    val plan = planned?.getOrNull()?.takeIf { it.user == user }
    var unknowns by remember(session, history, upload.receiptRevision, scanRevision) {
        mutableStateOf<Result<List<OsmGpxReceipt>>?>(null)
    }

    val batch = upload.batch?.takeIf { it.accountId == user?.id }
    val canAct = started && !auth.busy && !upload.running && work == null
    val canEditVisibility = canAct && auth.canWriteGpx && readyHistory != null
    val visibilityNotes = readyHistory?.visibilityNotes.orEmpty() + visibilityResults

    fun owns(generation: Int): Boolean =
        session != null && generation == foregroundGeneration &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            controller.sessions.value === session && controller.authState.value.let {
                !it.busy && it.user?.id == session.userId
            }

    fun ownsHistory(expected: OsmGpxHistoryState.Ready, generation: Int): Boolean =
        owns(generation) && controller.history.value === expected

    fun canSelectVisibility(entry: OsmGpxHistoryEntry, snapshot: OsmGpxHistoryState.Ready): Boolean =
        entry.canChangeToTrackable && entry.id !in snapshot.visibilityBlocked

    fun launchWork(block: suspend CoroutineScope.() -> Unit) {
        if (work != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (work === coroutineContext[Job]) work = null
            }
        }
        work = job
        job.start()
    }

    fun openLink(url: String): Boolean = runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    }.onFailure { message = "OSM bağlantısı açılamadı." }.isSuccess

    fun refreshHistory() {
        val generation = foregroundGeneration
        if (!canAct || !canRead || !owns(generation)) return
        val previous = controller.history.value
        if (previous !is OsmGpxHistoryState.Failed && previous !== readyHistory) return
        consent = null
        preview = null
        visibilityReview = null
        visibilitySelection = emptySet()
        message = null
        launchWork {
            controller.refreshHistory()
            val refreshed = controller.history.value as? OsmGpxHistoryState.Ready
            if (refreshed != null && refreshed !== previous && refreshed.user == user && ownsHistory(refreshed, generation)) {
                visibilityResults = visibilityResults + refreshed.visibilityNotes
            }
        }
    }

    suspend fun decodePreview(trace: PreparedOsmGpxTrace): Boolean =
        withContext(Dispatchers.Default) { trace.segments.isNotEmpty() }

    fun openPreview(key: OsmGpxReceiptKey) {
        val snapshot = coverage ?: return
        val generation = foregroundGeneration
        if (!canAct || !ownsHistory(snapshot, generation)) return
        message = null
        launchWork {
            val trace = osmGpsLoad {
                controller.preparePlan(listOf(key.journeyId))?.takeIf { it.user == user }
                    ?.traces?.singleOrNull()?.takeIf { OsmGpxReceiptKey.of(it) == key && decodePreview(it) }
            }.getOrNull()
            if (ownsHistory(snapshot, generation)) {
                if (trace != null) preview = trace
                else {
                    scanRevision++
                    message = "İz değişti veya önizleme hazırlanamadı. Güncel izi yeniden incele."
                }
            }
        }
    }

    fun ownsConsent(pending: OsmGpsPendingConsent): Boolean =
        consent === pending && pending.history.coverageReady &&
            ownsHistory(pending.history, pending.foregroundGeneration) &&
            controller.authState.value.canWriteGpx &&
            selection.keysFor(pending.plan.user.id) == pending.selection

    fun review() {
        val snapshot = coverage ?: return
        val shown = plan ?: return
        val generation = foregroundGeneration
        val first = shown.traces.firstOrNull() ?: return
        if (!canAct || !ownsHistory(snapshot, generation) || !controller.authState.value.canWriteGpx ||
            selection.keysFor(shown.user.id) != selected
        ) return
        message = null
        launchWork {
            val decoded = osmGpsLoad { decodePreview(first) }.getOrNull() == true
            if (ownsHistory(snapshot, generation) && selection.keysFor(shown.user.id) == selected) {
                if (decoded) consent = OsmGpsPendingConsent(shown, selected, snapshot, generation)
                else message = "İz önizlemesi hazırlanamadı. Yeniden dene."
            }
        }
    }

    fun reviewTrace(pending: OsmGpsPendingConsent, index: Int) {
        val trace = pending.plan.traces.getOrNull(index) ?: return
        if (!canAct || !ownsConsent(pending)) return
        launchWork {
            val decoded = osmGpsLoad { decodePreview(trace) }.getOrNull() == true
            if (ownsConsent(pending)) {
                if (decoded) consent = pending.copy(index = index)
                else {
                    consent = null
                    message = "İz önizlemesi hazırlanamadı. Yeniden incele."
                }
            }
        }
    }

    fun submit(pending: OsmGpsPendingConsent) {
        if (!canAct || !ownsConsent(pending)) return
        launchWork {
            val confirmation = osmGpsLoad { controller.prepareConfirmation(pending.plan) }.getOrNull()
            val reviewedKeys = pending.plan.traces.map { OsmGpxReceiptKey.of(it) }
            if (ownsConsent(pending) && confirmation != null && confirmation.user == pending.plan.user &&
                confirmation.keys == reviewedKeys && confirmation.visibility == OsmGpxVisibility.TRACKABLE
            ) {
                consent = null
                upload.start(controller, confirmation)
            } else {
                confirmation?.consume()
                if (owns(pending.foregroundGeneration) && consent === pending) {
                    consent = null
                    scanRevision++
                    message = "Plan değişti. Güncel izleri yeniden önizle ve onayla."
                }
            }
        }
    }

    fun submitVisibility(entries: List<OsmGpxHistoryEntry>) {
        val snapshot = readyHistory ?: return
        val generation = foregroundGeneration
        val ids = entries.map { it.id }
        if (!canEditVisibility || !ownsHistory(snapshot, generation) || !controller.authState.value.canWriteGpx ||
            visibilityReview !== entries ||
            ids.toSet() != visibilitySelection || entries.isEmpty() ||
            entries.any { !canSelectVisibility(it, snapshot) || it !in snapshot.entries }
        ) return
        visibilityReview = null
        visibilitySelection = emptySet()
        visibilityResults = visibilityResults - ids.toSet()
        changingVisibility = true
        message = null
        launchWork {
            val job = coroutineContext[Job]
            try {
                val result = osmGpsLoad {
                    controller.changeToTrackable(snapshot.user.id, ids, canContinue = {
                        job?.isActive == true && controller.sessions.value === session &&
                            controller.authState.value.let { !it.busy && it.canWriteGpx && it.user?.id == snapshot.user.id }
                    })
                }.getOrNull()
                if (owns(generation)) {
                    if (result != null) {
                        visibilityResults = visibilityResults + result.filterKeys { it in ids }
                        message = "Güncel görünürlüğü görmek için geçmişi yeniden kontrol et."
                    } else message = "Görünürlük değişikliği doğrulanamadı. Geçmişi yeniden kontrol et."
                }
            } finally {
                changingVisibility = false
            }
        }
    }

    val onStop by rememberUpdatedState {
        started = false
        foregroundGeneration++
        if (changingVisibility) message = "Kalan görünürlük değişiklikleri durduruldu. Son durumu geçmişten yeniden kontrol et."
        work?.cancel()
        upload.stopDispatching()
        consent = null
        preview = null
        visibilityReview = null
    }
    DisposableEffect(lifecycle, upload) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) started = true
            if (event == Lifecycle.Event.ON_STOP) onStop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            upload.stopDispatching()
        }
    }
    DisposableEffect(session, page) {
        onDispose {
            work?.cancel()
            upload.stopDispatching()
        }
    }
    LaunchedEffect(auth.user?.id) {
        selection.bind(auth.user?.id)
        upload.bind(auth.user?.id)
    }
    LaunchedEffect(controller, session, canRead, started) {
        if (started && canRead && owns(foregroundGeneration)) controller.ensureHistory()
    }
    LaunchedEffect(session, coverage, eligible, scanRevision, upload.receiptRevision, page) {
        if (coverage == null || page != OsmGpsPage.AVAILABLE) return@LaunchedEffect
        val generation = foregroundGeneration
        val candidates = mutableListOf<OsmGpsCandidate>()
        val blocked = mutableListOf<OsmGpsBlockedCandidate>()
        for (journey in eligible) {
            if (!ownsHistory(coverage, generation)) return@LaunchedEffect
            val candidate = osmGpsLoad { controller.preparePlan(listOf(journey.id)) }.getOrNull()
            if (candidate == null || candidate.user != user || candidate.traces.size > 1) {
                blocked += OsmGpsBlockedCandidate(journey.id, journey.title, journey.startedAt)
            } else {
                candidate.traces.firstOrNull()?.let { trace ->
                    candidates += OsmGpsCandidate(OsmGpxReceiptKey.of(trace), journey.title, journey.startedAt, trace.distanceMeters)
                }
            }
        }
        if (ownsHistory(coverage, generation)) scan = OsmGpsCandidateScan(candidates, blocked)
    }
    LaunchedEffect(user?.id, available) {
        if (user != null && available != null && selection.keysFor(user.id) == selected) {
            selection.retain(availableKeys)
            if (shortcutJourneyId != null) selection.preselect(available.firstOrNull()?.key)
        }
    }
    LaunchedEffect(session, coverage, selected, requestedIds, eligible, scanRevision, upload.receiptRevision, page) {
        if (coverage == null || requestedIds.isEmpty() || page != OsmGpsPage.AVAILABLE) return@LaunchedEffect
        val generation = foregroundGeneration
        val result = osmGpsLoad { checkNotNull(controller.preparePlan(requestedIds)) }
        if (ownsHistory(coverage, generation) && user != null && selection.keysFor(user.id) == selected) planned = result
    }
    LaunchedEffect(session, history, upload.receiptRevision, scanRevision, page, started) {
        if (!started || session == null || page != OsmGpsPage.UNKNOWN || !canRead) return@LaunchedEffect
        val generation = foregroundGeneration
        val result = osmGpsLoad { controller.unknownReceipts() }
        if (owns(generation)) unknowns = result
    }
    LaunchedEffect(batch?.result) {
        when (batch?.result?.stop) {
            OsmGpxStop.CHANGED, OsmGpxStop.STALE_CONFIRMATION -> {
                consent = null
                scanRevision++
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().testTag("osm-gps-screen")) {
        user?.let {
            Text(it.displayName, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("osm-gps-account"),
                style = MaterialTheme.typography.bodyMedium)
        }
        TabRow(selectedTabIndex = page.ordinal) {
            OsmGpsPage.entries.forEach { tab ->
                Tab(selected = page == tab, onClick = { onPage(tab) }, enabled = work == null && !upload.running,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-page-${tab.tag}"),
                    text = { Text(tab.label) })
            }
        }
        if (auth.busy || history == OsmGpxHistoryState.Loading || upload.running || work != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (canRead && (readyHistory != null || history is OsmGpxHistoryState.Failed)) {
            TextButton(onClick = ::refreshHistory, enabled = canAct,
                modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp).testTag("osm-gps-refresh")) {
                Text("Yeniden kontrol et")
            }
        }
        readyHistory?.takeUnless { it.coverageReady }?.let {
            Text(
                it.issue ?: "Bazı OSM izlerinin kapsamı doğrulanamadı. İşlemenin bitmesini bekle veya geçmişi yeniden kontrol et.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("osm-gps-coverage-issue"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val listModifier = Modifier.weight(1f).padding(16.dp)
        when {
            user == null || !canRead -> Text("İzleri görmek için bağlı OSM hesabına okuma izni ver.", listModifier)
            page == OsmGpsPage.HISTORY && readyHistory != null -> OsmGpsHistoryList(
                readyHistory.entries, selected = visibilitySelection, editingEnabled = canEditVisibility,
                results = visibilityNotes, blocked = readyHistory.visibilityBlocked, onToggle = { id ->
                    if (canEditVisibility && ownsHistory(readyHistory, foregroundGeneration) &&
                        readyHistory.entries.any { it.id == id && canSelectVisibility(it, readyHistory) }
                    ) visibilitySelection = if (id in visibilitySelection) visibilitySelection - id else visibilitySelection + id
                },
                onOpen = { entry -> if (owns(foregroundGeneration)) openLink(entry.traceUrl(readyHistory.user)) },
            )
            page == OsmGpsPage.UNKNOWN && unknowns?.isSuccess == true -> OsmGpsUnknownList(
                unknowns?.getOrNull().orEmpty(), canAct,
                onCheck = { if (owns(foregroundGeneration)) openLink(OSM_GPX_MY_TRACES_URL) },
            )
            page == OsmGpsPage.UNKNOWN && unknowns?.isFailure == true -> Text("Belirsiz yüklemeler okunamadı.", listModifier)
            history == OsmGpxHistoryState.Loading -> Text("OSM iz geçmişi kontrol ediliyor…", listModifier)
            history is OsmGpxHistoryState.Failed -> Text((history as OsmGpxHistoryState.Failed).issue,
                listModifier.testTag("osm-gps-history-issue"))
            readyHistory == null -> Text("OSM iz geçmişi hazırlanıyor…", listModifier)
            page == OsmGpsPage.UNKNOWN -> Text("Belirsiz yüklemeler okunuyor…", listModifier)
            !readyHistory.coverageReady -> Spacer(listModifier)
            available == null -> Text("Paylaşılabilecek izler hesaplanıyor…", listModifier)
            else -> OsmGpsCandidates(available, selected, canAct,
                onToggleAll = { selection.toggleAll(availableKeys) },
                onToggle = selection::toggle, onPreview = ::openPreview,
                rowStatus = { key -> osmGpsRowStatus(batch, key) },
                blocked = scan?.blocked.orEmpty())
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page == OsmGpsPage.AVAILABLE && coverage != null && selected.isNotEmpty()) {
                val text = when {
                    planned == null -> "Seçili izler hesaplanıyor…"
                    plan == null ->
                        "Seçimdeki izler doğrulanamadı. Geçmişi yeniden kontrol et veya daha az yolculuk seç."
                    plan.traces.isEmpty() -> "Seçimde paylaşılabilecek yeni yönlü bölüm bulunamadı."
                    else -> "${plan.traces.size} iz paylaşılabilir · ${osmGpsDistance(plan.traces.sumOf { it.distanceMeters })}"
                }
                Text(text, Modifier.testTag("osm-gps-selection"))
            }
            osmGpsBatchStatus(batch)?.let {
                Text(it, Modifier.testTag("osm-gps-batch-status").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
            }
            (message ?: auth.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val action = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            when {
                changingVisibility -> TextButton(modifier = action.testTag("osm-gps-visibility-stop"), onClick = {
                    work?.cancel()
                    message = "Kalan değişiklikler durduruldu. Son durumu geçmişten yeniden kontrol et."
                }) { Text("Değişiklikleri durdur") }
                auth.user == null -> Button(enabled = canAct, modifier = action.testTag("osm-gps-connect"), onClick = {
                    if (!auth.clientIdConfigured) onSettings()
                    else context.findActivity()?.let(onLogin) ?: run { message = "OSM giriş ekranı açılamadı." }
                }) { Text(if (auth.clientIdConfigured) "OSM hesabını bağla" else "OSM bağlantı ayarlarını aç") }
                !canRead -> Button(enabled = canAct && user != null, modifier = action.testTag("osm-gps-read-permission"),
                    onClick = {
                        context.findActivity()?.let(controller::requestReadPermission)
                            ?: run { message = "OSM izin ekranı açılamadı." }
                    }) { Text("OSM'de iz okuma izni ver") }
                !auth.canWriteGpx && (page == OsmGpsPage.AVAILABLE ||
                    readyHistory?.entries?.any { it.canChangeToTrackable } == true) ->
                    Button(enabled = canAct && user != null, modifier = action.testTag("osm-gps-permission"), onClick = {
                        context.findActivity()?.let(controller::requestPermission)
                            ?: run { message = "OSM izin ekranı açılamadı." }
                    }) { Text("OSM'de iz gönderme izni ver") }
                page == OsmGpsPage.HISTORY && visibilitySelection.isNotEmpty() && readyHistory != null ->
                    Button(enabled = canEditVisibility, modifier = action.testTag("osm-gps-visibility-review"), onClick = {
                        if (ownsHistory(readyHistory, foregroundGeneration)) {
                            val chosen = readyHistory.entries.filter {
                                it.id in visibilitySelection && canSelectVisibility(it, readyHistory)
                            }
                            if (chosen.size == visibilitySelection.size) visibilityReview = chosen
                        }
                    }) { Text("Görünürlük değişikliğini incele · ${visibilitySelection.size} iz") }
                page == OsmGpsPage.AVAILABLE -> Button(enabled = canAct && !plan?.traces.isNullOrEmpty(),
                    modifier = action.testTag("osm-gps-review"), onClick = ::review) {
                    Text(if (plan == null) "Önizle" else "Önizle · ${plan.traces.size} iz")
                }
            }
        }
    }

    if (coverage != null) preview?.let { trace -> OsmGpsPreviewDialog(trace, onDismiss = { preview = null }) }
    consent?.takeIf(::ownsConsent)?.let { pending ->
        val traces = pending.plan.traces
        OsmGpsConsentDialog(
            username = pending.plan.user.displayName,
            preview = OsmGpsConsentPreview(traces[pending.index], pending.index, traces.size, traces.sumOf { it.distanceMeters }),
            enabled = canAct,
            onPreview = { reviewTrace(pending, it) },
            onDismiss = { consent = null },
            onConfirm = { submit(pending) },
        )
    }
    visibilityReview?.let { entries ->
        val owner = readyHistory?.user ?: return@let
        if (canEditVisibility) OsmGpsVisibilityConfirmation(
            username = owner.displayName,
            entries = entries,
            onConfirm = { submitVisibility(entries) },
            onDismiss = { visibilityReview = null },
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
        OsmGpxStop.ALREADY_USED, OsmGpxStop.STALE_CONFIRMATION, OsmGpxStop.CHANGED ->
            "Plan değişti. Gönderimi yeniden önizle ve onayla."
    }
    return if (reason == null) counts else "$counts\n$reason"
}

private fun osmGpsRowStatus(batch: OsmGpsUpload.Batch?, key: OsmGpxReceiptKey): String? {
    batch ?: return null
    val index = batch.keys.indexOfFirst { it.journeyId == key.journeyId }
    if (index < 0) return null
    val result = batch.result
    if (result == null) return when {
        index + 1 == batch.current -> "Gönderiliyor…"
        index + 1 > batch.current -> "Sırada"
        else -> null
    }
    val sentKey = batch.keys[index]
    when (result.receipts.firstOrNull { it.key == sentKey }?.status) {
        OsmGpxReceiptStatus.RECEIVED -> return "Onaylanan iz gönderildi."
        OsmGpxReceiptStatus.UNKNOWN -> return "Gönderim sonucu kontrol bekliyor."
        null -> Unit
    }
    return when (result.rejectedKeys[sentKey]) {
        400, 413, 422 -> "OSM GPX dosyasını kabul etmedi."
        401 -> "OSM oturumu sona erdi."
        403 -> "OSM bu hesap için gönderime izin vermedi."
        429 -> "OSM gönderimleri geçici olarak sınırladı."
        null -> "Bu iz gönderilmedi."
        else -> "OSM isteği kabul etmedi."
    }
}
