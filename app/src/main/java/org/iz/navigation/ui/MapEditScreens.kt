@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.data.*
import org.iz.navigation.IzApplication
import org.iz.navigation.integration.*
import org.iz.navigation.integration.osm.*
import org.iz.navigation.ui.history.LocalUiEntryKey
import org.iz.navigation.ui.history.LocalUiHistory
import org.iz.navigation.ui.history.LocalUiExitGuard
import org.iz.navigation.ui.history.UiAsyncOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Root entry point. Drafts survive navigation/OAuth; publication is always an explicit reviewed action. */
@Composable
fun MapEditPage(
    onClose: () -> Unit,
    initialCorrection: Place? = null,
    initialDraftId: String? = null,
    startNew: Boolean = false,
    startSurvey: Boolean = false,
    initialCoordinate: GeoCoordinate? = null,
    onNoteFallback: (Place) -> Unit = {},
) {
    val context = LocalContext.current
    val application = remember { context.applicationContext as? IzApplication }
    val osmReady = remember { application?.osmReady }
    val repository = remember { DiaryRepository(context) }
    val auth = remember { OsmAuthManager.get(context) }
    val controller = remember { OsmMapEditController(repository, auth) }
    val surveyReads = LocalOsmSurveyReads.current
    val session by auth.state.collectAsStateWithLifecycle()
    val liveSession by auth.communitySessionSource.sessions.collectAsStateWithLifecycle()
    val isCorrectionReviewCurrent = remember(auth, session, liveSession, auth.currentCredentials()) {
        { review: OsmCorrectionReview -> auth.currentCredentials() === review.credentials }
    }
    val communityState = application?.community?.state?.collectAsStateWithLifecycle()
    val entryKey = LocalUiEntryKey.current
    val history = LocalUiHistory.current
    val exitGuard = LocalUiExitGuard.current
    val deletion = remember(entryKey) {
        OsmDestructiveConfirmation<MapEditDraft> { it.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) }
    }
    val pendingDeletion by deletion.pending.collectAsStateWithLifecycle()
    var deletionLease by remember(entryKey) { mutableStateOf<UiAsyncOwner?>(null) }
    val allDrafts by repository.mapEdits.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeSubmitter = session.user?.id?.takeIf { !session.busy && auth.currentCredentials()?.user?.id == it }
    val drafts = allDrafts.filter { it.submittedBy == null || it.submittedBy == activeSubmitter }
    val scope = rememberCoroutineScope()
    val catalog = rememberMapEditCatalog()?.getOrNull()
    val favorites by repository.osmPresetFavorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = favorites.map { it.presetId }.toSet()
    var picker by rememberSaveable { mutableStateOf(startNew) }
    var editingId by rememberSaveable { mutableStateOf(initialDraftId) }
    val correctionEntryId = rememberSaveable { UUID.randomUUID().toString() }
    var correctionEntryHandled by rememberSaveable { mutableStateOf(false) }
    var unsupportedEntry by rememberSaveable { mutableStateOf(false) }
    var initialTarget by remember { mutableStateOf<OsmCorrectionElement?>(null) }
    var scrolledToDraft by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editorMessage by remember { mutableStateOf<String?>(null) }
    val onFavorite: (String, Boolean) -> Unit = { id, enabled ->
        scope.launch {
            try { repository.setOsmPresetFavorite(id, enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { editorMessage = "Favori saklanamadı. Yeniden deneyebilirsin." }
        }
    }
    BackHandler(enabled = busy) { }
    fun action(block: suspend () -> String) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { osmReady?.await(); message = block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "İşlem tamamlanamadı. Taslağını ve bağlantını kontrol et." }
            finally { busy = false }
        }
    }
    fun openCorrection(place: Place) {
        action {
            val ref = place.osmType?.let { type -> place.osmId?.takeIf { it > 0 }?.let { OsmRef(type, it) } }
            if (ref == null || place.latitude == null || place.longitude == null) {
                unsupportedEntry = true
                correctionEntryHandled = true
                return@action "Bu mekân için doğrudan düzeltme açılamadı."
            }
            if (startSurvey) {
                val owner = auth.currentCredentials()
                val accountId = auth.state.value.user?.id
                fun current() = !auth.state.value.busy && auth.currentCredentials() === owner &&
                    auth.state.value.user?.id == accountId && (history == null || history.current.key == entryKey)
                if (!current()) return@action "OSM hesabı veya ekran değişti. Yeniden aç."
                val opened = try {
                    openSurveyDraftEntry(repository, place, correctionEntryId, accountId, ::current,
                        readTarget = surveyReads?.target ?: controller::readCorrectionTarget)
                } catch (_: OsmUnsupportedCorrection) {
                    unsupportedEntry = true
                    correctionEntryHandled = true
                    return@action "Bu OSM kaydı için İz'de doğrudan düzeltme kullanılamıyor."
                }
                initialTarget = opened.target
                correctionEntryHandled = true
                editorMessage = null
                editingId = opened.draft.id
                return@action "Yerinde gözlem taslağı açıldı."
            }
            val existing = repository.getMapEdit(correctionEntryId)
            if (existing == null) {
                val target = try {
                    controller.readCorrectionTarget(ref)
                } catch (_: OsmUnsupportedCorrection) {
                    unsupportedEntry = true
                    correctionEntryHandled = true
                    return@action "Bu OSM kaydı için İz'de doğrudan düzeltme kullanılamıyor."
                }
                val loadedCatalog = catalog ?: return@action "Tür kataloğu henüz hazır değil."
                val prepared = try { target.editorDraft(loadedCatalog, correctionEntryId, place.latitude, place.longitude) }
                    catch (_: OsmUnsupportedCorrection) {
                        unsupportedEntry = true; correctionEntryHandled = true
                        return@action "Bu nesne OSM düzenleyicisinde düzenlenebilir."
                    }
                repository.saveMapEdit(prepared)
                initialTarget = target
            }
            correctionEntryHandled = true
            editorMessage = null
            editingId = correctionEntryId
            "OSM mekânı doğrulandı."
        }
    }
    LaunchedEffect(initialCorrection, catalog) {
        if (!correctionEntryHandled && initialCorrection != null && (startSurvey || catalog != null)) openCorrection(initialCorrection)
    }
    val coordinateEntryId = rememberSaveable { UUID.randomUUID().toString() }
    var coordinateHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialCoordinate) {
        if (initialCoordinate != null && !coordinateHandled) action {
            if (repository.getMapEdit(coordinateEntryId) == null) repository.saveMapEdit(
                newMapEditDraft(initialCoordinate.latitude, initialCoordinate.longitude).copy(id = coordinateEntryId))
            coordinateHandled = true; editingId = coordinateEntryId
            "Konum seçildi. Mekân bilgilerini doldur."
        }
    }
    LaunchedEffect(initialDraftId, drafts) {
        if (!scrolledToDraft && initialDraftId != null) {
            val index = drafts.indexOfFirst { it.id == initialDraftId }
            if (index >= 0) {
                listState.scrollToItem(index)
                scrolledToDraft = true
            }
        }
    }
    suspend fun saveEditableDraft(draft: MapEditDraft): MapEditDraft {
        val session = auth.currentCredentials()
        repository.saveMapEdit(draft) { current ->
            val owner = current?.submittedBy
            owner == null || (session?.user?.id == owner && auth.currentCredentials() === session)
        }
        val saved = repository.getMapEdit(draft.id)
        check(auth.currentCredentials() === session && saved == draft.editableCopy()) {
            "Taslak veya OSM hesabı değişti. Yeniden incele."
        }
        return requireNotNull(saved)
    }
    fun deletionOwner() = OsmConfirmationOwner(entryKey, auth.currentCredentials()?.user?.id,
        application?.community?.state?.value?.cacheIdentity, auth.communitySessionSource.sessions.value, history)
    fun deletionBusy() = busy || auth.state.value.busy
    fun requestDeletion(draft: MapEditDraft) {
        val lease = exitGuard?.captureAsyncOwner(entryKey)
        if (exitGuard != null && lease == null) return
        if (deletion.request(draft, deletionOwner(), deletionBusy()) != null) deletionLease = lease
    }
    fun confirmDeletion(request: OsmDestructiveRequest<MapEditDraft>) {
        if (deletion.pending.value !== request || deletionBusy()) return
        val lease = deletionLease
        val permit = deletion.take(request, deletionOwner(), drafts.firstOrNull { it.id == request.target.id }, deletionBusy())
        if (permit == null) { message = "Kayıt değişti. Güncel durumu kontrol et."; return }
        val accepted = {
            if (exitGuard == null || exitGuard.acceptsAsyncOwner(lease, entryKey)) {
                permit.applyIfCurrent(deletionOwner(), drafts.firstOrNull { it.id == request.target.id }, deletionBusy()) { expected ->
                    action {
                        // action awaits startup recovery: recheck the owner after that suspension too.
                        if (!request.owner.matches(deletionOwner()) || auth.state.value.busy ||
                            (exitGuard != null && !exitGuard.acceptsAsyncOwner(lease, entryKey))) {
                            "Kayıt değişti. Güncel durumu kontrol et."
                        } else {
                            val session = auth.currentCredentials()
                            val removed = repository.deleteMapEditIfUnchanged(expected) {
                                (expected.submittedBy == null || session?.user?.id == expected.submittedBy) &&
                                    auth.currentCredentials() === session
                            }
                            if (removed) "Yerel taslak silindi." else "Kayıt değişti. Güncel durumu kontrol et."
                        }
                    }
                }
            }
            Unit
        }
        if (exitGuard == null) accepted() else exitGuard.requestExit(accepted)
    }
    DisposableEffect(deletion) { onDispose { deletion.invalidate() } }
    LaunchedEffect(entryKey, liveSession, session.user?.id, communityState?.value?.cacheIdentity, drafts) {
        deletion.pending.value?.let { request ->
            if (!request.owner.matches(deletionOwner()) || drafts.firstOrNull { it.id == request.target.id } != request.target)
                deletion.dismiss(request)
        }
    }
    LaunchedEffect(editingId, allDrafts, activeSubmitter) {
        val owner = allDrafts.firstOrNull { it.id == editingId }?.submittedBy
        if (owner != null && owner != activeSubmitter) {
            editingId = null
            editorMessage = null
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("OSM mekânlarım", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
            TextButton(onClick = onClose, enabled = !busy) { Text("Kapat") }
        }
        Text("Yerinde doğruladığın mekân ekleme ve düzeltme taslaklarını yönet.")
        Button(onClick = { picker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("OSM'ye mekân ekle") }
        message?.let { Text(it, modifier = Modifier.padding(bottom = 12.dp)) }
        if (initialCorrection != null && unsupportedEntry) {
            MapEditFallbackActions(initialCorrection, enabled = !busy, onNoteFallback = onNoteFallback)
        } else if (initialCorrection != null && !correctionEntryHandled && !busy) {
            TextButton(onClick = { openCorrection(initialCorrection) }) { Text("OSM mekânını yeniden kontrol et") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (drafts.isEmpty()) item { Text("Henüz bir mekân taslağı yok.") }
            items(drafts, key = { it.id }) { draft ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(draft.name.ifBlank {
                            if (draft.editorVersion == 2) mapEditPresetLabel(draft.presetSnapshot) ?: "Tür seçilmemiş OSM mekânı"
                            else draft.preset.label
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(if (draft.targetType != null) "Düzeltme · ${draft.status.mapEditLabel()}" else draft.status.mapEditLabel())
                        draft.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (draft.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) TextButton(onClick = { editorMessage = null; editingId = draft.id }, enabled = !busy) { Text("Düzenle") }
                            if (draft.status in setOf(MapEditStatus.UNKNOWN, MapEditStatus.SENT)) TextButton(onClick = {
                                action { controller.reconcile(draft.id).message }
                            }, enabled = !busy) { Text("OSM sonucunu kontrol et") }
                            draft.remoteNodeId?.let { id -> TextButton(onClick = { MediaShare.openUrl(context, "https://www.openstreetmap.org/node/$id") }) { Text("OSM'de aç") } }
                            draft.correctionRef()?.let { ref -> TextButton(onClick = {
                                MediaShare.openUrl(context, "https://www.openstreetmap.org/${ref.type.apiName()}/${ref.id}")
                            }, enabled = !busy) { Text("OSM kaydını aç") } }
                            if (draft.status == MapEditStatus.SENT && draft.localChangesetCreated && !draft.changesetClosed) TextButton(onClick = {
                                action { controller.close(draft.id).message }
                            }, enabled = !busy) { Text("Gönderimi kapat") }
                            if (draft.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) TextButton(onClick = {
                                requestDeletion(draft)
                            }, enabled = !deletionBusy(), modifier = Modifier.testTag("map-draft-delete")) { Text("Taslağı sil") }
                        }
                    }
                }
            }
        }
    }
    pendingDeletion?.let { request ->
        OsmDestructiveDialog(
            title = "Mekân taslağı silinsin mi?",
            body = "Bu cihazdaki mekân taslağı kalıcı olarak silinir. OSM’deki mekânlar değişmez.",
            confirmLabel = "Taslağı sil", tag = "map-draft-delete", enabled = !deletionBusy(),
            onDismiss = { deletion.dismiss(request) }, onConfirm = { confirmDeletion(request) },
        )
    }
    if (picker) MapEditLocationPicker(onClose = { picker = false }) { coordinate ->
        picker = false
        action {
            val draft = newMapEditDraft(latitude = coordinate.latitude, longitude = coordinate.longitude)
            repository.saveMapEdit(draft)
            editorMessage = null
            editingId = draft.id
            "Konum seçildi. Mekân bilgilerini doldur."
        }
    }
    val editing = drafts.firstOrNull { it.id == editingId && it.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) }
    fun login(draft: MapEditDraft) = action {
        saveEditableDraft(draft)
        val activity = context.findActivity()
        if (activity == null) "OSM giriş ekranı açılamadı." else {
            auth.startMapEditLogin(activity)
            "OSM hesabında harita düzenleme iznini onayla. Dönüşte önizlemeden yayımlayabilirsin."
        }
    }
    if (editing?.surveyPayload != null && editing.targetType != null) OsmSurveyEditor(
        initial = editing.editableCopy(), target = initialTarget?.takeIf { it.ref == editing.correctionRef() },
        busy = busy, canWriteMap = session.canWriteMap, authBusy = session.busy, authError = editorMessage ?: session.error,
        liveBaseline = { drafts.firstOrNull { it.id == editing.id && it.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) }
            ?.editableCopy() ?: editing.editableCopy() },
        liveBusy = { busy || auth.state.value.busy }, onClose = { editingId = null },
        onSave = { draft -> action { saveEditableDraft(draft); editingId = null; "Taslak saklandı." } },
        onReadTarget = { ref -> osmReady?.await(); surveyReads?.target?.invoke(ref) ?: controller.readCorrectionTarget(ref) },
        onReview = { draft -> osmReady?.await(); editorMessage = null; controller.previewCorrection(saveEditableDraft(draft)) },
        isReviewCurrent = isCorrectionReviewCurrent, onLogin = ::login,
        onPublish = { review -> action {
            val result = controller.sendCorrection(review)
            editorMessage = result.message.takeUnless { result.success }
            if (result.success || repository.getMapEdit(review.draft.id)?.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) editingId = null
            result.message
        } },
        onNoteFallback = onNoteFallback,
    ) else if (editing?.targetType != null) MapEditCorrectionEditor(
        initial = editing.editableCopy(), target = initialTarget?.takeIf { it.ref == editing.correctionRef() },
        busy = busy, canWriteMap = session.canWriteMap, authBusy = session.busy, authError = editorMessage ?: session.error,
        liveBaseline = { drafts.firstOrNull { it.id == editing.id && it.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) }
            ?.editableCopy() ?: editing.editableCopy() },
        liveBusy = { busy || auth.state.value.busy },
        onClose = { editingId = null },
        onSave = { draft -> action { saveEditableDraft(draft); editingId = null; "Taslak saklandı." } },
        onReadTarget = { ref -> osmReady?.await(); controller.readCorrectionTarget(ref) },
        onReview = { draft -> osmReady?.await(); editorMessage = null; controller.previewCorrection(saveEditableDraft(draft)) },
        isReviewCurrent = isCorrectionReviewCurrent,
        catalog = catalog, favoriteIds = favoriteIds, onFavorite = onFavorite,
        onLogin = ::login,
        onPublish = { review -> action {
            val result = controller.sendCorrection(review)
            editorMessage = result.message.takeUnless { result.success }
            if (result.success || repository.getMapEdit(review.draft.id)?.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) editingId = null
            result.message
        } },
        onNoteFallback = onNoteFallback,
    ) else if (editing != null) MapEditEditor(editing.editableCopy(), busy, session.canWriteMap, session.busy,
        authError = editorMessage ?: session.error,
        liveBaseline = { drafts.firstOrNull { it.id == editing.id }?.editableCopy() ?: editing.editableCopy() },
        liveBusy = { busy || auth.state.value.busy },
        onClose = { editingId = null },
        onSave = { draft -> action { saveEditableDraft(draft); editingId = null; "Taslak saklandı." } },
        onPreview = { draft -> osmReady?.await(); editorMessage = null; saveEditableDraft(draft) },
        catalog = catalog, favoriteIds = favoriteIds, onFavorite = onFavorite,
        onLogin = ::login,
        onPublish = { draft ->
            val reviewedAccount = auth.currentCredentials()
            action {
            val saved = repository.getMapEdit(draft.id)
            check(saved == draft && auth.currentCredentials() === reviewedAccount) { "Taslak veya OSM hesabı değişti. Yeniden incele." }
            val result = controller.send(requireNotNull(saved))
            editorMessage = result.message.takeUnless { result.success }
            if (result.success || repository.getMapEdit(draft.id)?.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) editingId = null
            result.message
        } },
    )
}

@Composable
private fun MapEditLocationPicker(onClose: () -> Unit, onChoose: (GeoCoordinate) -> Unit) {
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    val coordinate = latitude?.let { lat -> longitude?.let { GeoCoordinate(lat, it) } }
    FullscreenMapDialog(title = "Yeni mekânın konumunu seç", onDismiss = onClose, bottomBar = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Yeni OSM mekânının bulunduğu noktaya uzun bas.")
            Button(enabled = coordinate != null, onClick = { coordinate?.let(onChoose) }, modifier = Modifier.fillMaxWidth()) { Text("Bu konuma mekân ekle") }
        }
    }) { modifier ->
        val pin = coordinate?.let { Place(name = "Yeni mekân", latitude = it.latitude, longitude = it.longitude) }
        DiaryMap(emptyList(), listOfNotNull(pin), modifier, focusCurrentLocation = true, expandable = false,
            onMapLongClick = { latitude = it.latitude; longitude = it.longitude },
            onOsmPlaceClick = null)
    }
}

@Composable
internal fun MapEditEditor(initial: MapEditDraft, busy: Boolean, canWriteMap: Boolean, authBusy: Boolean,
    authError: String?, onClose: () -> Unit, onSave: (MapEditDraft) -> Unit,
    onPreview: suspend (MapEditDraft) -> MapEditDraft, onLogin: (MapEditDraft) -> Unit,
    onPublish: (MapEditDraft) -> Unit,
    liveBaseline: () -> MapEditDraft = { initial }, liveBusy: () -> Boolean = { busy || authBusy },
    catalog: org.iz.navigation.osmcatalog.OsmPresetCatalog? = rememberMapEditCatalog()?.getOrNull(),
    favoriteIds: Set<String> = emptySet(), onFavorite: (String, Boolean) -> Unit = { _, _ -> },
) {
    val prepared = remember(initial, catalog) {
        if (initial.editorVersion == 2) Result.success(initial)
        else catalog?.let { runCatching { initial.upgradeEditorDraft(it) } }
    }
    val value = prepared?.getOrNull()
    if (value != null) MapEditEditorContent(value, busy, canWriteMap, authBusy, authError,
        onClose, onSave, onPreview, onLogin, onPublish,
        liveBaseline = {
            val saved = liveBaseline()
            if (saved.editorVersion == 2) saved else saved.upgradeEditorDraft(requireNotNull(catalog))
        }, liveBusy = liveBusy, catalog = catalog, favoriteIds = favoriteIds, onFavorite = onFavorite)
    else AlertDialog(onDismissRequest = { if (!busy && !authBusy) onClose() },
        title = { Text("Taslağı hazırla") },
        text = { Text(if (prepared?.isFailure == true) "Eski taslağın türü hazırlanamadı. Daha sonra yeniden dene."
            else "Tür kataloğu hazırlanıyor…") },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy && !authBusy) { Text("Kapat") } })
}

@Composable
private fun MapEditEditorContent(initial: MapEditDraft, busy: Boolean, canWriteMap: Boolean, authBusy: Boolean,
    authError: String?, onClose: () -> Unit, onSave: (MapEditDraft) -> Unit,
    onPreview: suspend (MapEditDraft) -> MapEditDraft, onLogin: (MapEditDraft) -> Unit,
    onPublish: (MapEditDraft) -> Unit,
    liveBaseline: () -> MapEditDraft = { initial }, liveBusy: () -> Boolean = { busy || authBusy },
    catalog: org.iz.navigation.osmcatalog.OsmPresetCatalog? = rememberMapEditCatalog()?.getOrNull(),
    favoriteIds: Set<String> = emptySet(), onFavorite: (String, Boolean) -> Unit = { _, _ -> },
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var phone by rememberSaveable(initial.id) { mutableStateOf(initial.phone) }
    var snapshot by rememberSaveable(initial.id) { mutableStateOf(initial.presetSnapshot) }
    var survey by rememberSaveable(initial.id) { mutableStateOf(initial.surveyConfirmed) }
    val originalDate = remember(initial.id) { Instant.ofEpochMilli(initial.observedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
    var observationDate by rememberSaveable(initial.id) { mutableStateOf(originalDate) }
    var preview by remember { mutableStateOf<MapEditDraft?>(null) }
    var previewAccount by remember { mutableStateOf<OsmCredentials?>(null) }
    var querying by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val observedAt = runCatching {
        val date = LocalDate.parse(observationDate)
        require(!date.isAfter(LocalDate.now()))
        if (observationDate == originalDate) initial.observedAt else date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    var draft = initial.copy(name = name.trim(), phone = phone.trim(),
        nameAction = if (name.isBlank()) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.SET,
        phoneAction = if (phone.isBlank()) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.SET,
        surveyConfirmed = survey, observedAt = observedAt ?: initial.observedAt)
    snapshot?.let { draft = draft.selectPreset(org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot.fromJson(it)) }
    val valid = observedAt != null && runCatching { validateMapEditForPublish(draft) }.isSuccess
    val saveValid = observedAt != null && runCatching { validateMapEdit(draft) }.isSuccess
    val locked = busy || querying || authBusy || submitted
    val auth = remember(context) { OsmAuthManager.get(context) }
    val account by auth.communitySessionSource.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(account) { preview = null; reviewed = false }
    LaunchedEffect(busy) { if (!busy) submitted = false }
    val exit = OsmDraftExitOwner("osm-map-edit/" + initial.id,
        accountKey = { auth.communitySessionSource.sessions.value?.let { "${it.userId}:${it.generation}" } },
        working = { listOf(name, phone, snapshot, survey, observationDate) },
        baseline = { liveBaseline().let { saved ->
            listOf(saved.name, saved.phone, saved.presetSnapshot, saved.surveyConfirmed,
                Instant.ofEpochMilli(saved.observedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        } }, busy = { liveBusy() || querying || submitted },
        discard = { liveBaseline().let { saved ->
            name = saved.name; phone = saved.phone; snapshot = saved.presetSnapshot; survey = saved.surveyConfirmed
            observationDate = Instant.ofEpochMilli(saved.observedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } })
    Dialog(onDismissRequest = { if (!locked) { if (preview != null) { preview = null; reviewed = false } else exit(onClose) } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !locked, dismissOnClickOutside = !locked)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (preview != null) "OSM'de yayımlamadan önce" else "Yeni OSM mekânı", style = MaterialTheme.typography.headlineSmall)
                (error ?: authError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val prepared = preview
                if (prepared != null) {
                    Text("OSM hesabı · ${previewAccount?.user?.displayName ?: "Oturum açılmadı"}")
                    Text("Ad: ${prepared.name.ifBlank { "Adsız" }}")
                    Text("Tür: ${mapEditPresetLabel(prepared.presetSnapshot) ?: "Tür seçilmemiş"}")
                    Text("Telefon: ${prepared.phone.ifBlank { "Yok" }}")
                    Text("Konum: ${prepared.latitude}, ${prepared.longitude}")
                    Text("OSM etiket ayrıntıları", style = MaterialTheme.typography.titleSmall)
                    prepared.publicTags().forEach { (key, value) -> Text("$key: $value") }
                    Text("Bu bilgiler ve konum OSM hesabınla herkese açık haritaya eklenecek. Kaynak: yerinde gözlem.")
                    CorrectionConfirmationRow("Yayımlanacak bilgileri ve OSM hesabımı kontrol ettim.",
                        "osm-map-publish-reviewed", reviewed, !locked) { reviewed = it }
                    if (canWriteMap) Button(onClick = {
                        if (!submitted && auth.currentCredentials() === previewAccount) {
                            submitted = true; preview = null; onPublish(prepared)
                        } else { preview = null; reviewed = false; error = "OSM oturumu değişti. Yeniden incele." }
                    }, enabled = !locked && reviewed, modifier = Modifier.fillMaxWidth().testTag("osm-map-publish")) { Text("OSM'de yayımla") }
                    else Button(onClick = { onLogin(prepared) }, enabled = !locked && reviewed, modifier = Modifier.fillMaxWidth()) { Text("OSM düzenleme iznini etkinleştir") }
                    TextButton(onClick = { preview = null; reviewed = false }, enabled = !locked) { Text("Düzenlemeye dön") }
                } else {
                    if (initial.legacyAddressIntent) {
                        Text("Eski taslaktaki adres değişiklikleri korunuyor ve yayımlamadan önce gösterilecek.")
                        if (initial.street.isNotBlank()) Text("addr:street: ${initial.street}")
                        if (initial.houseNumber.isNotBlank()) Text("addr:housenumber: ${initial.houseNumber}")
                    }
                    OutlinedTextField(name, { name = it; survey = false }, label = { Text("Ad (isteğe bağlı)") },
                        isError = name.codePointCount(0, name.length) > 255, modifier = Modifier.fillMaxWidth().testTag("osm-map-name"), enabled = !locked)
                    Text("Tür (zorunlu)", style = MaterialTheme.typography.labelLarge)
                    MapEditPresetSelector(catalog, org.iz.navigation.osmcatalog.OsmPresetGeometry.POINT, snapshot,
                        favoriteIds, !locked, { snapshot = it.toJson(); survey = false }, onFavorite)
                    if (catalog == null) Text("Tür kataloğu yüklenemedi veya henüz hazır değil. Taslağını saklayabilirsin.")
                    OutlinedTextField(phone, { phone = it; survey = false }, label = { Text("Telefon (isteğe bağlı)") },
                        isError = phone.codePointCount(0, phone.length) > 255, modifier = Modifier.fillMaxWidth().testTag("osm-map-phone"), enabled = !locked)
                    OutlinedTextField(observationDate, { observationDate = it; survey = false }, label = { Text("Gözlem tarihi") },
                        supportingText = { Text("Yıl-ay-gün. Bu tarih cihazında saklanır.") }, isError = observedAt == null,
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !locked)
                    CorrectionConfirmationRow("Bu konumu ve bilgileri yerinde doğruladım.", "osm-map-survey", survey, !locked) { survey = it }
                    Button(enabled = valid && !locked, modifier = Modifier.fillMaxWidth(), onClick = {
                        querying = true; error = null; reviewed = false
                        val reviewedDraft = draft
                        val credentials = auth.currentCredentials()
                        scope.launch {
                            try {
                                val normalized = onPreview(reviewedDraft)
                                check(normalized == reviewedDraft.editableCopy()) { "Taslak değişti." }
                                if (auth.currentCredentials() === credentials) { previewAccount = credentials; preview = normalized }
                                else error = "OSM oturumu değişti. Yeniden incele."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Taslak önizlemeye hazırlanamadı. Taslağını saklayıp yeniden deneyebilirsin." }
                            finally { querying = false }
                        }
                    }) { Text("Önizle") }
                }
                if (preview == null) OutlinedButton(enabled = !locked && saveValid, onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) { Text("Taslağı sakla") }
                if (locked) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Yayımlanıyor veya işlem hazırlanıyor…") }
                TextButton(onClick = { exit(onClose) }, enabled = !locked) { Text("Kapat") }
            }
        }
    }
}

private fun MapEditStatus.mapEditLabel() = when (this) {
    MapEditStatus.DRAFT -> "Taslak"
    MapEditStatus.SENDING -> "Gönderiliyor"
    MapEditStatus.SENT -> "OSM'de yayımlandı"
    MapEditStatus.FAILED -> "Gönderilemedi"
    MapEditStatus.UNKNOWN -> "Sonuç kontrol edilmeli"
}

/** Shared presentation only; callers own target validation and the one-shot destructive action. */
@Composable
internal fun OsmDestructiveDialog(
    title: String, body: String, confirmLabel: String, tag: String, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(tag + "-dialog"),
        title = { Text(title) },
        text = { Text(body, Modifier.verticalScroll(rememberScrollState()).testTag(tag + "-body")) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled,
            modifier = Modifier.testTag(tag + "-confirm")) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss,
            modifier = Modifier.testTag(tag + "-cancel")) { Text("Vazgeç") } },
    )
}
