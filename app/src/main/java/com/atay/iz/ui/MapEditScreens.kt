@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.atay.iz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.atay.iz.data.*
import com.atay.iz.IzApplication
import com.atay.iz.integration.*
import com.atay.iz.integration.osm.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Root entry point. Drafts survive navigation/OAuth; publication is always an explicit reviewed action. */
@Composable
fun MapEditPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val osmReady = remember { (context.applicationContext as? IzApplication)?.osmReady }
    val repository = remember { DiaryRepository(context) }
    val auth = remember { OsmAuthManager.get(context) }
    val settings = remember { OsmServiceSettings(context) }
    val controller = remember { OsmMapEditController(repository, auth) { settings.read().overpass } }
    val session by auth.state.collectAsStateWithLifecycle()
    val drafts by repository.mapEdits.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var picker by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("OSM mekânlarım", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
            TextButton(onClick = onClose, enabled = !busy) { Text("Kapat") }
        }
        Text("Yerinde gördüğün eksik mekânı doğrudan açık haritaya ekle.")
        Button(onClick = { picker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("OSM'ye mekân ekle") }
        message?.let { Text(it, modifier = Modifier.padding(bottom = 12.dp)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (drafts.isEmpty()) item { Text("Henüz bir mekân taslağı yok.") }
            items(drafts, key = { it.id }) { draft ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(draft.name.ifBlank { draft.preset.label }, style = MaterialTheme.typography.titleMedium)
                        Text(draft.status.mapEditLabel())
                        draft.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (draft.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) TextButton(onClick = { editorMessage = null; editingId = draft.id }, enabled = !busy) { Text("Düzenle") }
                            if (draft.status in setOf(MapEditStatus.UNKNOWN, MapEditStatus.SENT)) TextButton(onClick = {
                                action { controller.reconcile(draft.id).message }
                            }, enabled = !busy) { Text("OSM sonucunu kontrol et") }
                            draft.remoteNodeId?.let { id -> TextButton(onClick = { MediaShare.openUrl(context, "https://www.openstreetmap.org/node/$id") }) { Text("OSM'de aç") } }
                            if (draft.status == MapEditStatus.SENT && !draft.changesetClosed) TextButton(onClick = {
                                action { controller.close(draft.id).message }
                            }, enabled = !busy) { Text("Gönderimi kapat") }
                            if (draft.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) TextButton(onClick = {
                                action { repository.deleteMapEdit(draft.id); "Yerel taslak silindi." }
                            }, enabled = !busy) { Text("Taslağı sil") }
                        }
                    }
                }
            }
        }
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
    if (editing != null) MapEditEditor(editing.editableCopy(), busy, session.canWriteMap, session.busy,
        authError = editorMessage ?: session.error,
        onClose = { editingId = null },
        onSave = { draft -> action { repository.saveMapEdit(draft); editingId = null; "Taslak saklandı." } },
        onPreview = { draft -> osmReady?.await(); editorMessage = null; repository.saveMapEdit(draft); controller.nearby(draft) },
        onLogin = { draft -> action {
            repository.saveMapEdit(draft)
            val activity = context.findActivity()
            if (activity == null) "OSM giriş ekranı açılamadı." else {
                auth.startMapEditLogin(activity)
                "OSM hesabında harita düzenleme iznini onayla. Dönüşte önizlemeden yayımlayabilirsin."
            }
        } },
        onPublish = { draft, reviewed -> action {
            repository.saveMapEdit(draft)
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
    Dialog(onDismissRequest = { if (!locked) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false,
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
                TextButton(onClick = onClose, enabled = !locked) { Text("Kapat") }
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
