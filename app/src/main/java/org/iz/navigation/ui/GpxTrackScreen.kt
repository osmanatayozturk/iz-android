package org.iz.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Transport
import org.iz.navigation.gpx.*
import org.iz.navigation.integration.GpxStartCandidate
import org.iz.navigation.integration.GpxTrackMap
import org.iz.navigation.integration.gpxStartCandidates
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherEngine
import java.util.Locale

private data class GpxStartRequest(val track: ImportedTrack, val selection: TrackFollowSelection, val transport: Transport)
private data class GpxPreview(val distance: Double, val points: Int)

/** A callback-only phone surface: opening, choosing or leaving never starts/stops a session. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun GpxTrackScreen(
    track: ImportedTrack?, active: TrackFollowState?, fix: NavigationFix?, gpsStale: Boolean,
    currentTransport: Transport?, existingNavigation: Boolean, interrupted: Boolean,
    busy: Boolean = false, message: String? = null,
    onImport: () -> Unit, onSave: (ImportedTrack) -> Unit,
    onStart: (ImportedTrack, TrackFollowSelection, Transport, Boolean) -> Unit,
    onStop: () -> Unit, onDismiss: () -> Unit, onDismissInterrupted: () -> Unit,
    replacementKey: String? = null,
) {
    val displayed = track ?: active?.track
    val matchingActive = active?.takeIf { it.track.id == displayed?.id }
    val initial = matchingActive?.selection ?: TrackFollowSelection()
    var segmentIndex by rememberSaveable(displayed?.id) { mutableIntStateOf(initial.segmentIndex) }
    var reversed by rememberSaveable(displayed?.id) { mutableStateOf(initial.reversed) }
    var startPointIndex by rememberSaveable(displayed?.id) { mutableIntStateOf(initial.startPointIndex) }
    var startFraction by rememberSaveable(displayed?.id) { mutableDoubleStateOf(initial.startFraction) }
    var chosenTransport by rememberSaveable { mutableStateOf(Transport.WALK.name) }
    val transport = currentTransport?.takeIf { it != Transport.UNKNOWN } ?: Transport.valueOf(chosenTransport)
    val selection = TrackFollowSelection(segmentIndex, reversed, startPointIndex, startFraction)
    var segmentDialog by remember { mutableStateOf(false) }
    var transportDialog by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by rememberSaveable(displayed?.id) { mutableStateOf(displayed?.name.orEmpty()) }
    var pendingStart by remember { mutableStateOf<GpxStartRequest?>(null) }
    var candidates by remember { mutableStateOf<List<GpxStartCandidate>>(emptyList()) }
    var pickRequest by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selectionMessage by remember { mutableStateOf<String?>(null) }
    val currentBusy by rememberUpdatedState(busy)
    val currentTrack by rememberUpdatedState(displayed)
    val scope = rememberCoroutineScope()
    val mapFocus = remember { BringIntoViewRequester() }
    fun select(value: TrackFollowSelection) {
        pickRequest++
        selecting = false
        candidates = emptyList()
        selectionMessage = null
        segmentIndex = value.segmentIndex
        reversed = value.reversed
        startPointIndex = value.startPointIndex
        startFraction = value.startFraction
    }
    LaunchedEffect(displayed?.id, matchingActive?.selection) {
        matchingActive?.selection?.let(::select)
    }
    LaunchedEffect(displayed) {
        pickRequest++
        selecting = false
        candidates = emptyList()
        selectionMessage = null
        segmentDialog = false
        saveDialog = false
    }
    // The coordinator supplies session identity; ordinary progress and GPS changes keep this dialog.
    LaunchedEffect(displayed?.id, currentTransport, existingNavigation, replacementKey, active?.track?.id, active?.selection) {
        pendingStart = null
        if (currentTransport != null) transportDialog = false
    }
    val preview by produceState<GpxPreview?>(null, displayed, selection) {
        value = null
        if (displayed != null) value = withContext(Dispatchers.Default) {
            runCatching {
                val points = selectedTrackPoints(displayed, selection)
                GpxPreview(points.zipWithNext().sumOf { (a, b) -> WeatherEngine.distanceMeters(a, b) }, points.size)
            }.getOrNull()
        }
    }
    val poorGps = gpsStale || fix == null || !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f
    val editingActive = matchingActive != null && matchingActive.selection != selection
    val enabled = !busy && !selecting
    BackHandler(onBack = onDismiss)

    Surface(Modifier.fillMaxSize().testTag("gpx-screen"), color = Paper) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-close")) { Text("Kapat") }
            Text("GPX çizgi takibi", style = MaterialTheme.typography.headlineMedium)
            if (interrupted) Card(Modifier.fillMaxWidth().testTag("gpx-interrupted")) {
                Column(Modifier.padding(12.dp)) {
                    Text("Önceki GPX takibi kesildi.", style = MaterialTheme.typography.titleMedium)
                    Text("Başlangıcı kontrol edip yeniden başlatabilirsin.")
                    TextButton(onClick = onDismissInterrupted, modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-dismiss-interrupted")) { Text("Bildirimi kapat") }
                }
            }
            message?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (displayed == null) {
                Text("Bir GPX dosyası aç, bölümünü seç ve özgün çizgisini takip et.")
                Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-import")) { Text("GPX dosyası aç") }
            } else {
                Text(displayed.name, style = MaterialTheme.typography.titleLarge)
                Text("${displayed.segments.size} bölüm · ${displayed.segments.sumOf { it.points.size }} nokta", color = Muted)
                if (active != null) {
                    GpxActiveCard(active, poorGps)
                    if (matchingActive == null) Text("Şu anda başka bir iz takip ediliyor. Bu ekran seçtiğin izin önizlemesi.", color = Muted)
                    else if (editingActive) Text("Yeni başlangıç önizlemesi. Devam eden takip henüz değişmedi.", color = Muted)
                    OutlinedButton(onClick = onStop, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-stop")) { Text("GPX takibini durdur") }
                    if (matchingActive?.progress?.status == TrackFollowStatus.SEGMENT_COMPLETE && matchingActive.selection.segmentIndex < displayed.segments.lastIndex) {
                        OutlinedButton(onClick = { select(TrackFollowSelection(segmentIndex = matchingActive.selection.segmentIndex + 1)) },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-next-segment")) { Text("Sonraki bölümü önizle") }
                    }
                    if (matchingActive?.progress?.status == TrackFollowStatus.NEEDS_START_POINT) {
                        Text("İlerleme beklemede. Haritada doğru kolu ve başlangıcı seçip yeniden başlat.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { scope.launch { mapFocus.bringIntoView() } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-reselect")) { Text("Başlangıcı yeniden seç") }
                    }
                }
                OutlinedButton(onClick = { segmentDialog = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-segment")) {
                    Text(segmentLabel(displayed, segmentIndex))
                }
                OutlinedButton(onClick = { select(selection.copy(reversed = !reversed, startPointIndex = 0, startFraction = 0.0)) },
                    enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-reverse")) {
                    Text(if (reversed) "Yön: ters · Dosya yönüne dön" else "Yön: dosya yönü · Ters çevir")
                }
                GpxTrackMap(displayed, selection, fix, poorGps, enabled, onChooseStart = { coordinate ->
                    val token = ++pickRequest
                    selecting = true
                    selectionMessage = null
                    scope.launch {
                        val options = withContext(Dispatchers.Default) { gpxStartCandidates(displayed, selection, coordinate) }
                        if (token == pickRequest && !currentBusy && currentTrack == displayed) {
                            when (options.size) {
                                0 -> selectionMessage = "Bölüm sonundan önce bir nokta seç."
                                1 -> select(options.single().selection)
                                else -> candidates = options
                            }
                        }
                        if (token == pickRequest) selecting = false
                    }
                }, modifier = Modifier.fillMaxWidth().bringIntoViewRequester(mapFocus))
                if (selecting) Text("Başlangıç seçenekleri hazırlanıyor…", color = Muted)
                selectionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (startPointIndex != 0 || startFraction != 0.0) {
                    Text("Seçilen noktadan başlanacak. Önceki çizgi mesafeye katılmaz.", color = Muted)
                    TextButton(onClick = { select(selection.copy(startPointIndex = 0, startFraction = 0.0)) }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-reset-start")) { Text("Bölüm başından başla") }
                }
                Text(preview?.let { "Takip edilecek çizgi: ${gpxDistance(it.distance)} · ${it.points} nokta" }
                    ?: "Bölüm ve başlangıç hazırlanıyor…", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { transportDialog = true }, enabled = enabled && currentTransport == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-transport")) { Text("Ulaşım: ${transport.label()}") }
                if (currentTransport != null) Text("Açık oturumun ulaşım türü korunur. Bu ekran günlük kaydını değiştirmez.", color = Muted)
                Text("GPX çizgisinin yol erişimi doğrulanmaz; çizgi değiştirilmez. Dönüş talimatı verilmez.", style = MaterialTheme.typography.bodySmall, color = Muted)
                Button(onClick = {
                    val request = GpxStartRequest(displayed, selection, transport)
                    if (existingNavigation || active != null) pendingStart = request
                    else onStart(request.track, request.selection, request.transport, false)
                }, enabled = enabled && preview != null && preview!!.distance > 0.0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-start")) {
                    Text(if (matchingActive != null) "Seçilen başlangıçla yeniden başlat" else "Çizgiyi takip et")
                }
                OutlinedButton(onClick = { saveName = displayed.name; saveDialog = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-save")) { Text("İzi sakla…") }
                TextButton(onClick = onImport, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-import")) { Text("Başka GPX dosyası aç") }
            }
        }
    }
    if (segmentDialog && displayed != null) AlertDialog(onDismissRequest = { segmentDialog = false },
        title = { Text("Takip edilecek bölüm") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(displayed.segments) { index, _ ->
                    TextButton(onClick = { select(TrackFollowSelection(segmentIndex = index)); segmentDialog = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-segment-$index")) {
                        Text(segmentLabel(displayed, index))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { segmentDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kapat") } })
    if (transportDialog) AlertDialog(onDismissRequest = { transportDialog = false }, title = { Text("Ulaşım türü") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            transportDisplayOrder.forEach { mode ->
                TextButton(onClick = { chosenTransport = mode.name; transportDialog = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(mode.label()) }
            }
        }
    }, confirmButton = { TextButton(onClick = { transportDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Vazgeç") } })
    if (saveDialog && displayed != null) AlertDialog(onDismissRequest = { saveDialog = false }, title = { Text("İze bir ad ver") },
        text = { OutlinedTextField(value = saveName, onValueChange = { if (it.length <= 300) saveName = it },
            label = { Text("İzin adı") }, singleLine = true, modifier = Modifier.testTag("gpx-save-name")) },
        confirmButton = { TextButton(onClick = { saveDialog = false; onSave(displayed.copy(name = saveName.trim())) },
            enabled = !busy && saveName.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-confirm-save")) { Text("Sakla") } },
        dismissButton = { TextButton(onClick = { saveDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Vazgeç") } })
    pendingStart?.let { request -> AlertDialog(onDismissRequest = { pendingStart = null },
        title = { Text(if (active != null) "GPX takibini yeniden başlat?" else "Yönlendirmeden GPX takibine geç?") },
        text = { Text("Devam eden ${if (active != null) "GPX takibi" else "yol yönlendirmesi"} sona erecek. “${request.track.name}”, seçilen bölüm ve başlangıçla takip edilecek. Varsa açık günlük kaydı devam eder.") },
        confirmButton = { TextButton(onClick = {
            pendingStart = null; onStart(request.track, request.selection, request.transport, true)
        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-confirm-replace")) { Text("GPX takibine geç") } },
        dismissButton = { TextButton(onClick = { pendingStart = null }, modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-cancel-replace")) { Text("Vazgeç") } }) }
    if (candidates.isNotEmpty()) AlertDialog(onDismissRequest = { candidates = emptyList() }, title = { Text("Hangi geçişten başlamak istiyorsun?") },
        text = {
            Column {
                Text("Çizgi bu noktadan birden fazla kez geçiyor. İz üzerindeki sıraya göre seç.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) { itemsIndexed(candidates) { index, candidate ->
                    TextButton(onClick = { select(candidate.selection) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gpx-start-candidate-$index")) {
                        Text("Bölüm ${candidate.selection.segmentIndex + 1} · baştan ${gpxDistance(candidate.alongMeters)} · ${gpxDistance(candidate.distanceMeters)} uzakta")
                    }
                } }
            }
        }, confirmButton = { TextButton(onClick = { candidates = emptyList() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Vazgeç") } })
}

@Composable private fun GpxActiveCard(active: TrackFollowState, poorGps: Boolean) {
    val status = if (active.progress.status == TrackFollowStatus.SEGMENT_COMPLETE) "Bölüm tamamlandı."
    else if (poorGps) "Konum eski veya yeterince hassas değil. İlerleme beklemede." else when (active.progress.status) {
        TrackFollowStatus.WAITING_FOR_GPS -> "Güncel ve hassas konum bekleniyor."
        TrackFollowStatus.TRACKING -> "Çizgi takip ediliyor."
        TrackFollowStatus.OFF_TRACK -> "Çizginin dışındasın."
        TrackFollowStatus.NEEDS_START_POINT -> "Başlangıç noktasını yeniden seç."
        TrackFollowStatus.SEGMENT_COMPLETE -> "Bölüm tamamlandı."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aktif: ${active.track.name}", style = MaterialTheme.typography.titleMedium)
            Text(segmentLabel(active.track, active.selection.segmentIndex), color = Muted)
            Text("Kalan bölüm: ${gpxDistance(active.progress.remainingMeters)}", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("gpx-progress"))
            Text(status, modifier = Modifier.testTag("gpx-status"))
            if (!poorGps && active.progress.status != TrackFollowStatus.WAITING_FOR_GPS) {
                active.progress.distanceFromLineMeters?.let { Text("Çizgiye uzaklık: ${gpxDistance(it)}") }
            }
        }
    }
}

private fun segmentLabel(track: ImportedTrack, index: Int): String {
    val segment = track.segments.getOrNull(index) ?: return "Bölüm seç"
    val group = segment.trackName.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
    return "$group${segment.name.ifBlank { "Bölüm ${index + 1}" }} (${index + 1}/${track.segments.size})"
}

private fun gpxDistance(value: Double): String = when {
    !value.isFinite() || value < 0 -> "—"
    value < 1000 -> "${value.toInt()} m"
    else -> String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", value / 1000)
}
