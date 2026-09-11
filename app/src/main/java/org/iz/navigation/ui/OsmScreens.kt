@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
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
import org.iz.navigation.data.*
import org.iz.navigation.integration.*
import org.iz.navigation.integration.osm.OsmAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

@Composable
internal fun OsmSearchDialog(onDismiss: () -> Unit, onSelect: (SelectedOsmPlace) -> Unit, initialQuery: String = "") {
    val context = LocalContext.current
    val service = remember { OsmPlaces(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SelectedOsmPlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("OpenStreetMap’te yer ara") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Yer adı veya adres") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = query.isNotBlank() && !busy, onClick = {
                busy = true; error = null; results = emptyList()
                val submitted = query.trim()
                scope.launch {
                    try { results = service.search(submitted); searched = true }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "Arama tamamlanamadı." }
                    finally { busy = false }
                }
            }) { Text("Ara") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (searched && results.isEmpty() && !busy && error == null) Text("Yer bulunamadı. Haritadan bir nokta seçebilirsin.")
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { place -> OutlinedCard(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) { Text(place.name); Text("${place.latitude}, ${place.longitude}", style = MaterialTheme.typography.bodySmall) }
                } }
            }
            Text("Arama Nominatim üzerinden yapılır. Sonuçlar © OpenStreetMap katkıcıları.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } })
}

internal fun ContributionKind.label(): String = when (this) {
    ContributionKind.MISSING_PLACE -> "Eksik yer"
    ContributionKind.WRONG_DETAILS -> "Yanlış ad veya konum"
    ContributionKind.CLOSED_PLACE -> "Kapanmış yer"
    ContributionKind.OTHER -> "Diğer harita hatası"
}

internal fun ContributionStatus.label(): String = when (this) {
    ContributionStatus.DRAFT -> "Taslak"
    ContributionStatus.SENDING -> "Gönderiliyor"
    ContributionStatus.SENT -> "Gönderildi"
    ContributionStatus.FAILED -> "Gönderilemedi"
    ContributionStatus.UNKNOWN -> "Sonuç kontrol edilmeli"
}

@Composable
internal fun ContributionLocationPicker(initial: SelectedOsmPlace?, placeId: String?, onDismiss: () -> Unit, onChoose: (ContributionDraft) -> Unit) {
    var coordinate by remember { mutableStateOf(initial?.let { GeoCoordinate(it.latitude, it.longitude) }) }
    var selection by remember { mutableStateOf(initial) }
    var showHelp by remember { mutableStateOf(false) }
    FullscreenMapDialog(
        title = "Gözleminin konumunu seç",
        onDismiss = onDismiss,
        actions = { TextButton(onClick = { showHelp = true }) { Text("Yardım") } },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(selection?.name ?: coordinate?.let { String.format(Locale.ROOT, "%.6f, %.6f", it.latitude, it.longitude) }
                    ?: "Henüz bir nokta seçilmedi.", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Button(enabled = coordinate != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    val pin = coordinate ?: return@Button
                    onChoose(ContributionDraft(placeId = placeId, latitude = pin.latitude, longitude = pin.longitude,
                        observedAt = System.currentTimeMillis(), kind = if (selection == null) ContributionKind.MISSING_PLACE else ContributionKind.WRONG_DETAILS,
                        text = "", osmType = selection?.osmRef?.type, osmId = selection?.osmRef?.id))
                }) { Text("Bu konum için gözlem yaz") }
            }
        },
    ) { mapModifier ->
        val marker = remember(coordinate, selection) {
            coordinate?.let { Place(name = selection?.name ?: "Bildirim konumu", latitude = it.latitude, longitude = it.longitude) }
        }
        DiaryMap(emptyList(), listOfNotNull(marker), mapModifier,
            onMapLongClick = { coordinate = it; selection = null },
            onOsmPlaceClick = { selection = it; coordinate = GeoCoordinate(it.latitude, it.longitude) },
            focusCurrentLocation = initial == null,
            expandable = false)
    }
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text("Gözleminin konumunu seç") },
        text = { Text("Bir mekâna dokun veya noktayı belirlemek için haritaya uzun bas. Kişisel günlük bilgileri bildirime eklenmez.") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Tamam") } },
    )
}

@Composable
private fun OsmPopup(onDismiss: () -> Unit, busy: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    ) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
internal fun ContributionEditor(initial: ContributionDraft, connected: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onSave: (ContributionDraft) -> Unit, onPublish: (ContributionDraft) -> Unit, onLogin: (ContributionDraft) -> Unit,
    authError: String? = null) {
    var text by rememberSaveable(initial.id) { mutableStateOf(initial.text) }
    var kind by rememberSaveable(initial.id) { mutableStateOf(initial.kind) }
    var preview by rememberSaveable(initial.id) { mutableStateOf(false) }
    val draft = initial.copy(text = text.trim(), kind = kind)
    val valid = draft.text.isNotBlank() && draft.text.length <= 2000
    OsmPopup(onDismiss = onDismiss, busy = busy) {
            Text(if (preview) "Herkese açık bildirim" else "Haritaya bir gözlem ekle", style = MaterialTheme.typography.headlineSmall)
            authError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (preview) {
                Text(kind.label(), style = MaterialTheme.typography.titleMedium)
                Text(draft.text)
                Text("Konum: ${draft.latitude}, ${draft.longitude}")
                Text("Bu metin ve konum OSM hesabınla yayımlanacak. Harita gönüllüleri bildirimi inceleyebilir. Özel günlük notların ve fotoğrafların gönderilmez.", color = Muted)
                if (connected) Button(onClick = { onPublish(draft) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) { Text("OSM’ye gönder") }
                else Button(onClick = { onLogin(draft) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("OSM hesabını bağla") }
                TextButton(onClick = { preview = false }, enabled = !busy) { Text("Düzenlemeye dön") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContributionKind.entries.forEach { option -> FilterChip(kind == option, { kind = option }, { Text(option.label()) }, enabled = !busy) }
                }
                OutlinedTextField(text, { text = it }, label = { Text("Yerinde ne gözlemledin?") }, supportingText = { Text("${text.length}/2000 · Doğrulanabilir harita bilgisi yaz.") },
                    isError = text.length > 2000, minLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("osm-observation"))
                Text("Konum: ${draft.latitude}, ${draft.longitude}", style = MaterialTheme.typography.bodySmall)
                Text("Gözlem: ${SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(initial.observedAt))}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { preview = true }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) { Text("Gönderimi önizle") }
                OutlinedButton(onClick = { onSave(draft) }, enabled = text.length <= 2000 && !busy, modifier = Modifier.fillMaxWidth()) { Text("Taslağı sakla") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Kapat") }
    }
}

@Composable
internal fun ContributionsScreen(drafts: List<ContributionDraft>, busy: Boolean, onNew: () -> Unit,
    onEdit: (ContributionDraft) -> Unit, onCheck: (ContributionDraft) -> Unit, onDelete: (ContributionDraft) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("OSM katkılarım", style = MaterialTheme.typography.headlineMedium); Text("Gittiğin yerlerde gördüklerin haritayı geliştirsin.", color = Muted) }
        item { Button(onClick = onNew, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Yeni harita bildirimi") } }
        if (drafts.isEmpty()) item { Text("Henüz bir bildirim yok. Eksik veya yanlış bir yeri gözlemleyerek başlayabilirsin.") }
        items(drafts.sortedByDescending { it.observedAt }, key = { it.id }) { draft ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.kind.label(), style = MaterialTheme.typography.titleMedium)
                Text(draft.status.label() + (draft.remoteStatus?.let { " · ${when (it) { "closed" -> "Çözüldü"; "hidden" -> "Gizlendi"; "open" -> "Açık"; else -> it }}" } ?: ""), color = Forest)
                Text(draft.text.ifBlank { "Açıklama bekleyen taslak" })
                Text("${draft.latitude}, ${draft.longitude}", style = MaterialTheme.typography.bodySmall)
                draft.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (draft.status == ContributionStatus.UNKNOWN) Text("Tekrar göndermeden önce OSM’de sonucu kontrol et. Bağlantı kesildiği için yayın durumu kesinleşmedi.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.status in listOf(ContributionStatus.DRAFT, ContributionStatus.FAILED)) TextButton(onClick = { onEdit(draft) }, enabled = !busy) { Text("Düzenle") }
                    if (draft.status in listOf(ContributionStatus.UNKNOWN, ContributionStatus.SENT)) TextButton(onClick = { onCheck(draft) }, enabled = !busy) { Text("Durumu kontrol et") }
                    draft.remoteNoteId?.let { id -> TextButton(onClick = { MediaShare.openUrl(context, "https://www.openstreetmap.org/note/$id") }) { Text("OSM’de aç") } }
                    TextButton(onClick = { onDelete(draft) }, enabled = !busy && draft.status != ContributionStatus.SENDING) { Text("Listemden sil") }
                }
            } }
        }
    }
}

@Composable
internal fun OsmSettingsPanel(onContributions: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val auth = remember { OsmAuthManager.get(context) }
    val state by auth.state.collectAsStateWithLifecycle()
    var showServices by remember { mutableStateOf(false) }
    var showClient by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("OpenStreetMap", style = MaterialTheme.typography.titleLarge)
        Text("Harita, Nominatim araması ve OSM katkıları. İnternet olmadan yolculuk kaydı sürer.", color = Muted)
        Text(state.user?.let { "Bağlı hesap: ${it.displayName}" } ?: "Haritayı hesapsız kullanabilirsin. Bildirim göndermek için hesabını bağla.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.user == null) OutlinedButton(enabled = !state.busy, onClick = {
            runCatching { auth.startLogin(context.findActivity() ?: error("Giriş ekranı açılamadı.")) }.onFailure { onMessage(it.message ?: "Giriş başlatılamadı.") }
        }) { Text("OSM hesabını bağla") }
        else TextButton(onClick = { auth.disconnect() }, enabled = !state.busy) { Text("Hesap bağlantısını kes") }
        if (!state.clientIdConfigured) Text("OSM uygulama kimliği henüz tanımlanmadı. Harita ve yerel taslaklar kullanılabilir.", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onContributions) { Text("Katkılarım") }
        TextButton(onClick = { showServices = true }) { Text("Harita servisleri") }
        TextButton(onClick = { showClient = true }) { Text("OSM uygulama kimliği") }
        TextButton(onClick = { MediaShare.openUrl(context, "https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap katkıcıları · Lisans") }
    }
    if (showClient) AlertDialog(onDismissRequest = { showClient = false }, title = { Text("OSM uygulama bağlantısı") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Uygulama için kaydedilen public OAuth istemci kimliğini gir. Bu alan hesap şifren değildir.")
            OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true)
            Text("Dönüş adresi: org.iz.navigation:/oauth2redirect\nİzinler: read_prefs, write_notes\nConfidential seçeneği kapalı olmalı.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { MediaShare.openUrl(context, "https://www.openstreetmap.org/oauth2/applications/new") }) { Text("OSM uygulama kaydını aç") }
        }
    }, confirmButton = { TextButton(enabled = clientId.isNotBlank(), onClick = {
        runCatching { auth.setClientId(clientId.trim()); showClient = false }.onFailure { onMessage(it.message ?: "Kimlik kaydedilemedi.") }
    }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = { showClient = false }) { Text("Vazgeç") } })
    if (showServices) OsmServiceEditor(onDismiss = { showServices = false }, onSaved = { showServices = false; onMessage("Harita servisleri güncellendi.") })
}

@Composable
private fun OsmServiceEditor(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { OsmServiceSettings(context) }
    val saved = remember { settings.read() }
    var tiles by remember { mutableStateOf(saved.tileUrl) }
    var search by remember { mutableStateOf(saved.nominatim) }
    var nearby by remember { mutableStateOf(saved.overpass) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Harita servisleri") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Kişisel kullanım için çevrimiçi OSM servisleri. Arama yalnızca Ara düğmesiyle çalışır; çevrimdışı harita paketi indirilmez.")
            OutlinedTextField(tiles, { tiles = it }, label = { Text("Harita adresi · {z}/{x}/{y}") })
            OutlinedTextField(search, { search = it }, label = { Text("Nominatim arama adresi") })
            OutlinedTextField(nearby, { nearby = it }, label = { Text("Overpass sorgu adresi") })
            Text("Bu servislere arama metni veya seçilen harita alanı gönderilir. OSM hesap bilgileri gönderilmez.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { val defaults = OsmServiceEndpoints(); tiles = defaults.tileUrl; search = defaults.nominatim; nearby = defaults.overpass }) { Text("Varsayılanları kullan") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { settings.save(OsmServiceEndpoints(tileUrl = tiles.trim(), nominatim = search.trim(), overpass = nearby.trim())) }
            .onSuccess { onSaved() }.onFailure { error = it.message ?: "Geçerli HTTPS adresleri gir." }
    }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
internal fun GpxExportSheet(journey: Journey, points: List<TrackPoint>, onDismiss: () -> Unit, onExport: (String) -> Unit) {
    val all = remember(journey, points) { runCatching { gpxSegments(journey, points).flatMap { segment -> segment.mapIndexed { index, point -> if (index == 0) point.copy(breakBefore = true) else point } } }.getOrDefault(emptyList()) }
    var start by remember { mutableIntStateOf(0) }
    var end by remember { mutableIntStateOf(0) }
    val prepared = remember(journey, points, start, end) { runCatching { gpxSegments(journey, points, start, end) } }
    val previewPoints = prepared.getOrNull()?.flatMap { segment -> segment.mapIndexed { index, point -> if (index == 0) point.copy(breakBefore = true) else point } }.orEmpty()
    OsmPopup(onDismiss = onDismiss) {
            Text("Rotayı GPX olarak dışa aktar", style = MaterialTheme.typography.headlineSmall)
            Text("Başlangıcı ve bitişi kısaltabilirsin. Notlar ve fotoğraflar dosyaya eklenmez. Dosya otomatik yayımlanmaz.", color = Muted)
            if (all.size >= 2) {
                DiaryMap(previewPoints, emptyList(), Modifier.fillMaxWidth().height(230.dp))
                Text("Başlangıçtan çıkarılan: ${DiaryRules.distanceMeters(all.take(start + 1)).toInt()} m")
                Slider(start.toFloat(), { start = it.toInt().coerceIn(0, all.size - end - 2) }, valueRange = 0f..(all.size - end - 2).coerceAtLeast(1).toFloat(), enabled = all.size - end > 2)
                Text("Bitişten çıkarılan: ${DiaryRules.distanceMeters(all.takeLast(end + 1)).toInt()} m")
                Slider(end.toFloat(), { end = it.toInt().coerceIn(0, all.size - start - 2) }, valueRange = 0f..(all.size - start - 2).coerceAtLeast(1).toFloat(), enabled = all.size - start > 2)
                Text("${previewPoints.size} konum · ${prepared.getOrNull()?.size ?: 0} rota bölümü", style = MaterialTheme.typography.bodySmall)
            }
            prepared.exceptionOrNull()?.let { Text(it.message ?: "Dışa aktarılabilir rota bulunamadı.", color = MaterialTheme.colorScheme.error) }
            Button(enabled = prepared.isSuccess, onClick = { onExport(buildGpx(journey, points, start, end)) }, modifier = Modifier.fillMaxWidth()) { Text("GPX dosyasını kaydet") }
            TextButton(onClick = onDismiss) { Text("Kapat") }
    }
}
