package org.iz.navigation.ui

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.osm.OSM_GPX_MY_TRACES_URL
import org.iz.navigation.integration.osm.OsmGpxConfirmation
import org.iz.navigation.integration.osm.OsmGpxController
import org.iz.navigation.integration.osm.OsmGpxReceipt
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.osm.OsmGpxReceiptStatus
import org.iz.navigation.integration.osm.OsmGpxStop
import org.iz.navigation.osmcommunity.CommunitySession

private data class OsmGpsPendingConsent(
    val confirmation: OsmGpxConfirmation,
    val selected: List<PreparedOsmGpxTrace>,
    val session: CommunitySession,
    val foregroundGeneration: Int,
)

@Composable
internal fun OsmGpsScreen(
    journeys: List<Journey>,
    shortcutJourneyId: String?,
    selection: OsmGpsSelection,
    upload: OsmGpsUpload,
    onLogin: (Activity) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val repository = remember(app) { DiaryRepository(app) }
    val controller = remember(app) { OsmGpxController.get(app) }
    val authState by controller.authState.collectAsStateWithLifecycle()
    val session by controller.sessions.collectAsStateWithLifecycle()
    val user = authState.user?.takeIf { it.id == session?.userId }
    val candidates = remember(journeys) { journeys.filter(::osmGpsEligibleJourney) }
    val currentCandidates by rememberUpdatedState(candidates)
    val stored = remember(session, user?.id) { mutableStateMapOf<OsmGpxReceiptKey, Result<OsmGpxReceipt?>>() }
    var reviewed by remember(session) { mutableStateOf(emptySet<OsmGpxReceipt>()) }
    var linksOpened by remember(session) { mutableStateOf(emptySet<OsmGpxReceipt>()) }
    var retryReceipts by remember { mutableStateOf(0) }
    var permissionRequestedFor by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var preparingConsent by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf<OsmGpsPendingConsent?>(null) }
    var foregroundGeneration by remember { mutableStateOf(0) }
    val selected = selection.traces(candidates)
    val sameAccountBatch = upload.batch?.takeIf { user != null && it.accountId == user.id }

    fun receipt(trace: PreparedOsmGpxTrace): OsmGpxReceipt? {
        val accountId = user?.id ?: return null
        val key = OsmGpxReceiptKey.of(trace)
        return (sameAccountBatch?.result?.receipts?.firstOrNull { it.key == key } ?: stored[key]?.getOrNull())
            ?.takeIf { it.accountId == accountId }
    }
    fun readyToSend(trace: PreparedOsmGpxTrace): Boolean {
        if (user == null || stored[OsmGpxReceiptKey.of(trace)]?.isSuccess != true) return false
        val previous = receipt(trace)
        return previous == null || previous.status == OsmGpxReceiptStatus.UNKNOWN && previous in reviewed
    }
    fun openLink(url: String): Boolean = runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    }.onFailure { message = "OSM bağlantısı açılamadı." }.isSuccess

    val sendable = selected.filter(::readyToSend)
    val pendingLookup = user != null && selected.any { !stored.containsKey(OsmGpxReceiptKey.of(it)) }
    val shortcutPending = candidates.any { it.id == shortcutJourneyId } &&
        !selection.shortcutHandled && !selection.shortcutBlocked
    val excluded = (if (user != null && !pendingLookup) selected.size - sendable.size else 0) +
        (if (selection.shortcutBlocked) 1 else 0)
    val expectedSession = session

    suspend fun loadReceipts(traces: List<PreparedOsmGpxTrace>) {
        val accountId = user?.id ?: return
        if (expectedSession == null || traces.isEmpty()) return
        try {
            val receipts = controller.receipts(traces)
            if (expectedSession === controller.sessions.value) {
                for (trace in traces) {
                    val key = OsmGpxReceiptKey.of(trace)
                    stored[key] = Result.success(receipts[key]?.takeIf { it.accountId == accountId })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            for (trace in traces) stored[OsmGpxReceiptKey.of(trace)] = Result.failure(failure)
        }
    }

    DisposableEffect(lifecycle, upload) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foregroundGeneration++
                upload.stopDispatching()
                consent = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            upload.stopDispatching()
        }
    }
    LaunchedEffect(expectedSession, selected, authState.busy) { consent = null }
    LaunchedEffect(expectedSession, user?.id, selected, upload.receiptRevision, retryReceipts) {
        loadReceipts(selected)
    }
    LaunchedEffect(permissionRequestedFor, authState) {
        val requested = permissionRequestedFor
        val state = controller.authState.value
        if (requested != null && !state.busy) {
            permissionRequestedFor = null
            if (requested == state.user?.id && !state.canWriteGpx) {
                message = "İzin verilmedi. Seçimin duruyor."
            }
        }
    }

    Column(Modifier.fillMaxSize().testTag("osm-gps-screen")) {
        if (authState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        user?.let { Text(it.displayName, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("osm-gps-account"),
            style = MaterialTheme.typography.bodyMedium) }
        OsmGpsCandidates(candidates, shortcutJourneyId, selection, repository::journeyPoints,
            enabled = !upload.running && !preparingConsent,
            canSelect = { trace -> user == null || readyToSend(trace) }) { trace ->
            val key = OsmGpxReceiptKey.of(trace)
            val selectedHere = trace in selected
            LaunchedEffect(expectedSession, user?.id, key, selectedHere, upload.receiptRevision, retryReceipts) {
                if (!selectedHere) loadReceipts(listOf(trace))
            }
            val previous = receipt(trace)
            when {
                sameAccountBatch != null && sameAccountBatch.result == null && key in sameAccountBatch.keys -> {
                    val index = sameAccountBatch.keys.indexOf(key) + 1
                    val text = when {
                        index == sameAccountBatch.current -> "${index}/${sameAccountBatch.keys.size} iz gönderiliyor…"
                        index < sameAccountBatch.current -> "Gönderim sonucu bekleniyor."
                        else -> "Sırada"
                    }
                    Text(text, Modifier.testTag("osm-gps-result-${trace.journeyId}"))
                }
                previous?.status == OsmGpxReceiptStatus.RECEIVED && user != null -> {
                    Text("OSM izi aldı. İşlenmesi zaman alabilir.", Modifier.testTag("osm-gps-result-${trace.journeyId}"))
                    previous.traceUrl(user)?.let { url ->
                        TextButton(onClick = { openLink(url) }, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("osm-gps-open-${trace.journeyId}")) { Text("OSM'de aç") }
                    }
                }
                previous?.status == OsmGpxReceiptStatus.UNKNOWN -> {
                    Text("Sonuç belirsiz. Yeniden göndermeden önce OSM'de izlerini kontrol et.",
                        Modifier.testTag("osm-gps-result-${trace.journeyId}"))
                    Text(trace.fileName, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (openLink(OSM_GPX_MY_TRACES_URL)) linksOpened = linksOpened + previous },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-check-${trace.journeyId}")) {
                        Text("OSM'de izlerimi kontrol et")
                    }
                    if (previous in linksOpened && previous !in reviewed) {
                        TextButton(enabled = !upload.running && !preparingConsent, onClick = { reviewed = reviewed + previous },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-unknown-reviewed-${trace.journeyId}")) {
                            Text("İzi bulamadım, yeniden gönderebilirim")
                        }
                    }
                }
                sameAccountBatch != null && key in sameAccountBatch.keys -> {
                    Text(osmGpsRowFailure(sameAccountBatch, key), Modifier.testTag("osm-gps-result-${trace.journeyId}"))
                }
                stored[key]?.isFailure == true -> {
                    Text("Cihazdaki gönderim durumu okunamadı.")
                    TextButton(onClick = { retryReceipts++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Yeniden dene") }
                }
            }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val count = if (user == null || pendingLookup) "${selected.size} iz seçildi" else "${sendable.size} iz gönderilebilir"
            Text(if (excluded == 0) count else "$count · $excluded iz dışarıda", Modifier.testTag("osm-gps-selection"),
                style = MaterialTheme.typography.bodyMedium)
            val status = osmGpsBatchStatus(upload.batch, sameAccountBatch)
            if (status != null) Text(status, Modifier.testTag("osm-gps-batch-status"), style = MaterialTheme.typography.bodySmall)
            (message ?: authState.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val canAct = !authState.busy && !upload.running && !preparingConsent
            when {
                authState.user == null -> Button(onClick = {
                    if (!authState.clientIdConfigured) onSettings()
                    else context.findActivity()?.let(onLogin) ?: run { message = "OSM giriş ekranı açılamadı." }
                },
                    enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-connect")) {
                    Text(if (authState.clientIdConfigured) "OSM hesabını bağla" else "OSM bağlantı ayarlarını aç")
                }
                !authState.canWriteGpx -> Button(enabled = canAct && user != null, onClick = {
                    val activity = context.findActivity()
                    if (activity == null) message = "OSM izin ekranı açılamadı."
                    else {
                        message = null
                        permissionRequestedFor = user?.id
                        controller.requestPermission(activity)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-permission")) {
                    Text("OSM'de iz gönderme izni ver")
                }
                else -> Button(enabled = canAct && !pendingLookup && !shortcutPending && sendable.isNotEmpty(), onClick = {
                    if (preparingConsent || consent != null || expectedSession == null ||
                        expectedSession !== controller.sessions.value) return@Button
                    preparingConsent = true
                    message = null
                    val generation = foregroundGeneration
                    scope.launch {
                        try {
                            val prepared = controller.prepareConfirmation(sendable, reviewed)
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                foregroundGeneration == generation &&
                                expectedSession === controller.sessions.value && !controller.authState.value.busy &&
                                selected == selection.traces(currentCandidates)) {
                                consent = prepared?.takeIf { it.traces == sendable && it.user.id == user?.id }?.let {
                                    OsmGpsPendingConsent(it, selected, expectedSession, generation)
                                }
                                if (consent == null) {
                                    retryReceipts++
                                    message = "Gönderim durumu değişti. Seçimini kontrol et."
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "Gönderim onayı hazırlanamadı. Yeniden dene."
                        } finally {
                            preparingConsent = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-review")) {
                    Text("Gönderimi incele")
                }
            }
        }
    }

    consent?.takeIf { it.session === expectedSession && it.selected == selected && !authState.busy }?.let { pending ->
        val confirmation = pending.confirmation
        OsmGpsConsentDialog(confirmation.user.displayName, confirmation.traceCount, onDismiss = { consent = null }, onConfirm = {
            if (consent !== pending) return@OsmGpsConsentDialog
            consent = null
            val currentAuth = controller.authState.value
            if (pending.session === controller.sessions.value && !currentAuth.busy && currentAuth.canWriteGpx &&
                currentAuth.user?.id == confirmation.user.id && pending.selected == selection.traces(currentCandidates) &&
                pending.foregroundGeneration == foregroundGeneration && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                upload.start(controller, confirmation)
                reviewed = emptySet()
                linksOpened = emptySet()
            } else message = "Hesap veya seçim değişti. Gönderimi yeniden incele."
        })
    }
}

private fun osmGpsBatchStatus(batch: OsmGpsUpload.Batch?, visible: OsmGpsUpload.Batch?): String? {
    if (batch == null) return null
    if (visible == null) return if (batch.result == null || batch.result.stop == OsmGpxStop.ACCOUNT_CHANGED)
        "OSM hesabı değişti. Kalan izler gönderilmedi." else null
    val result = visible.result ?: return if (visible.current > 0) "${visible.current}/${visible.keys.size} iz gönderiliyor…"
        else "${visible.keys.size} iz sırada."
    val received = result.receipts.count { it.status == OsmGpxReceiptStatus.RECEIVED }
    val unknown = result.receipts.count { it.status == OsmGpxReceiptStatus.UNKNOWN }
    val counts = "$received iz alındı · $unknown sonuç belirsiz · ${visible.keys.size - received - unknown} iz gönderilmedi"
    val reason = when (result.stop) {
        OsmGpxStop.COMPLETE, OsmGpxStop.UNKNOWN, OsmGpxStop.REJECTED -> null
        OsmGpxStop.RATE_LIMITED -> "OSM gönderimleri geçici olarak sınırladı. Kalan izler gönderilmedi." +
            (result.retryAfterMillis?.takeIf { it > 0 }?.let { " En az ${(it - 1) / 1000 + 1} sn bekle." } ?: "")
        OsmGpxStop.BACKGROUND -> "Gönderim durdu. Kalan izler gönderilmedi."
        OsmGpxStop.ACCOUNT_CHANGED -> "OSM hesabı değişti. Kalan izler gönderilmedi."
        OsmGpxStop.STORAGE_ERROR -> "Gönderim durumu cihazda doğrulanamadı."
        OsmGpxStop.BUSY -> "Başka bir gönderim sürüyor."
        OsmGpxStop.ALREADY_USED, OsmGpxStop.STALE_CONFIRMATION -> "Gönderimi yeniden incele."
    }
    return if (reason == null) counts else "$counts\n$reason"
}

private fun osmGpsRowFailure(batch: OsmGpsUpload.Batch, key: OsmGpxReceiptKey): String {
    val result = requireNotNull(batch.result)
    val lastAttempt = batch.keys.lastIndex - result.unattemptedCount
    if (batch.keys.getOrNull(lastAttempt) != key || result.stop != OsmGpxStop.REJECTED) return "Bu iz gönderilmedi."
    val reason = when (result.httpStatus) {
        401 -> "OSM oturumu sona erdi."
        403 -> "OSM bu hesap için gönderime izin vermedi."
        400, 413 -> "OSM GPX dosyasını kabul etmedi."
        else -> "OSM isteği kabul etmedi."
    }
    return "İz gönderilemedi. $reason"
}
