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
    onNoteFallback: (Place) -> Unit = {},
) {
    val context = LocalContext.current
    val application = remember { context.applicationContext as? IzApplication }
    val osmReady = remember { application?.osmReady }
    val repository = remember { DiaryRepository(context) }
    val auth = remember { OsmAuthManager.get(context) }
    val settings = remember { OsmServiceSettings(context) }
    val controller = remember { OsmMapEditController(repository, auth) { settings.read().overpass } }
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
            val existing = repository.getMapEdit(correctionEntryId)
            if (existing == null) {
                val target = try {
                    controller.readCorrectionTarget(ref)
                } catch (_: OsmUnsupportedCorrection) {
                    unsupportedEntry = true
                    correctionEntryHandled = true
                    return@action "Bu OSM kaydı için İz'de doğrudan düzeltme kullanılamıyor."
                }
                repository.saveMapEdit(MapEditDraft(id = correctionEntryId, latitude = place.latitude,
                    longitude = place.longitude, preset = target.preset(), targetType = ref.type, targetId = ref.id))
                initialTarget = target
            }
            correctionEntryHandled = true
            editorMessage = null
            editingId = correctionEntryId
            "OSM mekânı doğrulandı."
        }
    }
    LaunchedEffect(initialCorrection) {
        if (!correctionEntryHandled && initialCorrection != null) openCorrection(initialCorrection)
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
    suspend fun saveEditableDraft(draft: MapEditDraft) {
        val session = auth.currentCredentials()
        repository.saveMapEdit(draft) { current ->
            val owner = current?.submittedBy
            owner == null || (session?.user?.id == owner && auth.currentCredentials() === session)
        }
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
                        Text(draft.name.ifBlank { draft.preset.label }, style = MaterialTheme.typography.titleMedium)
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
                            if (draft.status == MapEditStatus.SENT && !draft.changesetClosed) TextButton(onClick = {
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
            val draft = MapEditDraft(latitude = coordinate.latitude, longitude = coordinate.longitude)
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
    if (editing?.targetType != null) MapEditCorrectionEditor(
        initial = editing.editableCopy(), target = initialTarget?.takeIf { it.ref == editing.correctionRef() },
        busy = busy, canWriteMap = session.canWriteMap, authBusy = session.busy, authError = editorMessage ?: session.error,
        liveBaseline = { drafts.firstOrNull { it.id == editing.id && it.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) }
            ?.editableCopy() ?: editing.editableCopy() },
        liveBusy = { busy || auth.state.value.busy },
        onClose = { editingId = null },
        onSave = { draft -> action { saveEditableDraft(draft); editingId = null; "Taslak saklandı." } },
        onReadTarget = { ref -> osmReady?.await(); controller.readCorrectionTarget(ref) },
        onReview = { draft -> osmReady?.await(); editorMessage = null; saveEditableDraft(draft); controller.previewCorrection(draft) },
        isReviewCurrent = isCorrectionReviewCurrent,
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
        onPreview = { draft -> osmReady?.await(); editorMessage = null; saveEditableDraft(draft); controller.nearby(draft) },
        onLogin = ::login,
        onPublish = { draft, reviewed -> action {
            saveEditableDraft(draft)
            val result = controller.send(draft.id, reviewed)
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
    var existing by rememberSaveable { mutableStateOf(false) }
    val coordinate = latitude?.let { lat -> longitude?.let { GeoCoordinate(lat, it) } }
    FullscreenMapDialog(title = "Yeni mekânın konumunu seç", onDismiss = onClose, bottomBar = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (existing) "Burada bir OSM mekânı zaten var. Eksik mekânın tam konumuna uzun bas." else "Eksik mekânın bulunduğu noktaya uzun bas.")
            Button(enabled = coordinate != null && !existing, onClick = { coordinate?.let(onChoose) }, modifier = Modifier.fillMaxWidth()) { Text("Bu konuma mekân ekle") }
        }
    }) { modifier ->
        val pin = coordinate?.let { Place(name = "Yeni mekân", latitude = it.latitude, longitude = it.longitude) }
        DiaryMap(emptyList(), listOfNotNull(pin), modifier, focusCurrentLocation = true, expandable = false,
            onMapLongClick = { latitude = it.latitude; longitude = it.longitude; existing = false },
            onOsmPlaceClick = { latitude = it.latitude; longitude = it.longitude; existing = it.osmRef != null })
    }
}

@Composable
internal fun MapEditEditor(initial: MapEditDraft, busy: Boolean, canWriteMap: Boolean, authBusy: Boolean,
    authError: String?, onClose: () -> Unit, onSave: (MapEditDraft) -> Unit,
    onPreview: suspend (MapEditDraft) -> List<OsmDuplicateCandidate>, onLogin: (MapEditDraft) -> Unit,
    onPublish: (MapEditDraft, Set<OsmRef>) -> Unit) {
    MapEditEditor(initial, busy, canWriteMap, authBusy, authError, onClose, onSave, onPreview, onLogin,
        onPublish, liveBaseline = { initial }, liveBusy = { busy || authBusy })
}

@Composable
internal fun MapEditEditor(initial: MapEditDraft, busy: Boolean, canWriteMap: Boolean, authBusy: Boolean,
    authError: String?, onClose: () -> Unit, onSave: (MapEditDraft) -> Unit,
    onPreview: suspend (MapEditDraft) -> List<OsmDuplicateCandidate>, onLogin: (MapEditDraft) -> Unit,
    onPublish: (MapEditDraft, Set<OsmRef>) -> Unit,
    liveBaseline: () -> MapEditDraft, liveBusy: () -> Boolean) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var street by rememberSaveable(initial.id) { mutableStateOf(initial.street) }
    var number by rememberSaveable(initial.id) { mutableStateOf(initial.houseNumber) }
    var preset by rememberSaveable(initial.id) { mutableStateOf(initial.preset) }
    var survey by rememberSaveable(initial.id) { mutableStateOf(initial.surveyConfirmed) }
    val originalDate = remember(initial.id) { Instant.ofEpochMilli(initial.observedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
    var observationDate by rememberSaveable(initial.id) { mutableStateOf(originalDate) }
    var preview by remember { mutableStateOf(false) }
    var querying by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<OsmDuplicateCandidate>>(emptyList()) }
    var reviewed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val observedAt = runCatching {
        val date = LocalDate.parse(observationDate)
        require(!date.isAfter(LocalDate.now()))
        if (observationDate == originalDate) initial.observedAt else date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    val draft = initial.copy(name = name.trim(), street = street.trim(), houseNumber = number.trim(), preset = preset,
        surveyConfirmed = survey, observedAt = observedAt ?: initial.observedAt)
    val valid = observedAt != null && runCatching { validateMapEditForPublish(draft) }.isSuccess
    val locked = busy || querying || authBusy
    val auth = remember(context) { OsmAuthManager.get(context) }
    val exit = OsmDraftExitOwner("osm-map-edit/" + initial.id,
        accountKey = { auth.communitySessionSource.sessions.value?.let { it.userId.toString() + ":" + it.generation } },
        working = { listOf(name.trim(), street.trim(), number.trim(), preset, survey, observationDate) },
        baseline = { liveBaseline().let { saved ->
            listOf(saved.name, saved.street, saved.houseNumber, saved.preset, saved.surveyConfirmed,
                Instant.ofEpochMilli(saved.observedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        } },
        busy = { liveBusy() || querying },
        discard = { liveBaseline().let { saved ->
            name = saved.name; street = saved.street; number = saved.houseNumber; preset = saved.preset
            survey = saved.surveyConfirmed
            observationDate = Instant.ofEpochMilli(saved.observedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } })
    Dialog(onDismissRequest = { if (!locked) { if (preview) { preview = false; reviewed = false } else exit(onClose) } }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !locked, dismissOnClickOutside = !locked)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (preview) "Mekânı yayımlamadan önce" else "Yeni OSM mekânı", style = MaterialTheme.typography.headlineSmall)
                (error ?: authError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (preview) {
                    Text(draft.name.ifBlank { "İsimsiz ${draft.preset.label.lowercase(Locale.forLanguageTag("tr"))}" }, style = MaterialTheme.typography.titleMedium)
                    Text("Kategori: ${preset.label}\nKonum: ${draft.latitude}, ${draft.longitude}")
                    if (street.isNotBlank() || number.isNotBlank()) Text("Adres: $street $number")
                    Text("Bu bilgiler ve konum OSM hesabınla herkese açık haritaya eklenecek. Kaynak: yerinde gözlem.")
                    Text(if (candidates.isEmpty()) "Aynı kategoride yakında bir kayıt bulunmadı." else "Yakında veya bu konumu kapsayan alanlarda mevcut kayıtlar var:")
                    candidates.forEach { candidate ->
                        TextButton(onClick = { MediaShare.openUrl(context, "https://www.openstreetmap.org/${candidate.ref.type.name.lowercase(Locale.ROOT)}/${candidate.ref.id}") }) {
                            Text("${candidate.name} · OSM'de incele")
                        }
                    }
                    Row {
                        Checkbox(reviewed, { reviewed = it }, enabled = !locked, modifier = Modifier.testTag("osm-map-duplicates-reviewed"))
                        Text("Mevcut kayıtları kontrol ettim; eklediğim mekân bunlardan farklı.", Modifier.padding(top = 12.dp))
                    }
                    if (canWriteMap) Button(onClick = { onPublish(draft, candidates.map { it.ref }.toSet()) },
                        enabled = !locked && reviewed && valid, modifier = Modifier.fillMaxWidth().testTag("osm-map-publish")) { Text("OSM'de yayımla") }
                    else Button(onClick = { onLogin(draft) }, enabled = !locked && reviewed && valid,
                        modifier = Modifier.fillMaxWidth()) { Text("OSM düzenleme iznini etkinleştir") }
                    TextButton(onClick = { preview = false; reviewed = false }, enabled = !locked) { Text("Düzenlemeye dön") }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MapPlacePreset.entries.forEach { option -> FilterChip(preset == option, { preset = option; survey = false }, { Text(option.label) }, enabled = !locked) }
                    }
                    OutlinedTextField(name, { name = it; survey = false }, label = { Text("Gerçek adı (varsa)") },
                        supportingText = { Text("Adı olmayan yer için boş bırak. En fazla 255 karakter.") }, isError = name.codePointCount(0, name.length) > 255,
                        modifier = Modifier.fillMaxWidth().testTag("osm-map-name"), enabled = !locked)
                    OutlinedTextField(street, { street = it; survey = false }, label = { Text("Cadde / sokak (varsa)") },
                        isError = street.codePointCount(0, street.length) > 255, modifier = Modifier.fillMaxWidth(), enabled = !locked)
                    OutlinedTextField(number, { number = it; survey = false }, label = { Text("Kapı numarası (varsa)") },
                        isError = number.codePointCount(0, number.length) > 255, modifier = Modifier.fillMaxWidth(), enabled = !locked)
                    OutlinedTextField(observationDate, { observationDate = it; survey = false }, label = { Text("Gözlem tarihi") },
                        supportingText = { Text("Yıl-ay-gün, örneğin 2026-09-10. Bu tarih cihazında saklanır.") },
                        isError = observedAt == null, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !locked)
                    Row {
                        Checkbox(survey, { survey = it }, enabled = !locked, modifier = Modifier.testTag("osm-map-survey"))
                        Text("Bu konumu ve bilgileri yerinde doğruladım.", Modifier.padding(top = 12.dp))
                    }
                    Button(enabled = valid && !locked, modifier = Modifier.fillMaxWidth(), onClick = {
                        querying = true; error = null; reviewed = false
                        scope.launch {
                            try { candidates = onPreview(draft); preview = true }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Mevcut mekânlar kontrol edilemedi. Taslağı saklayıp daha sonra tekrar deneyebilirsin." }
                            finally { querying = false }
                        }
                    }) { Text("Mevcut yerleri kontrol et ve önizle") }
                }
                OutlinedButton(enabled = !locked && observedAt != null && runCatching { validateMapEdit(draft) }.isSuccess, onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) { Text("Taslağı sakla") }
                if (locked) LinearProgressIndicator(Modifier.fillMaxWidth())
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
