package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.iz.navigation.ui.history.rememberUiEditExit
import org.iz.navigation.ui.history.UiEditOwner
import org.iz.navigation.ui.history.UiEditState
import org.iz.navigation.ui.history.LocalUiEntryKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.offline.OfflinePackStatus
import org.iz.navigation.weather.PlannedRoute

internal data class PreparedLibraryRoute(val route: PlannedRoute, val fingerprint: String, val mapStatus: String)

/** Opens saved input in the coordinator's editor/preview; never starts navigation itself. */
@Composable
fun TravelLibraryScreen(repository: TravelLibraryRepository, onOpenPlan: (SavedRoutePlan) -> Unit,
    onOpenTrack: (ImportedTrack) -> Unit, onDismiss: () -> Unit) {
    val plans by repository.savedPlans.collectAsStateWithLifecycle(emptyList())
    val tracks by repository.importedTracks.collectAsStateWithLifecycle(emptyList())
    val application = LocalContext.current.applicationContext as? IzApplication
    val preparedRoutes = remember(application) { application?.preparedRoutes }
    val stored = preparedRoutes?.state?.collectAsStateWithLifecycle()?.value
    val prepared = stored?.let { value ->
        val route = value.route
        val fingerprint = value.fingerprint
        if (route == null || fingerprint == null) null else {
            val mapStatus = when (value.pack?.status) {
                OfflinePackStatus.DOWNLOADING -> "Harita indiriliyor"
                OfflinePackStatus.PAUSED -> "Harita indirmesi duraklatıldı"
                OfflinePackStatus.PARTIAL -> "Harita indirmesi yarım kaldı"
                OfflinePackStatus.ERROR -> "Harita hazırlanamadı"
                OfflinePackStatus.READY -> if (preparedRoutes?.mapReady() == true) "Çevrimdışı harita hazır" else "Harita doğrulanıyor"
                OfflinePackStatus.PREPARED, null -> "Çevrimdışı harita hazırlanmadı"
            }
            PreparedLibraryRoute(route, fingerprint, mapStatus)
        }
    }
    val actions = rememberLibraryActions()
    val actionEntry = LocalUiEntryKey.current
    UiEditOwner(UiEditState(actionEntry + "/library-work", null, 0, 0, false, actions.busy),
        currentState = { UiEditState(actionEntry + "/library-work", null, 0, 0, false, actions.busy) }, discardUnsaved = {})
    TravelLibraryContent(plans, tracks, onOpenPlan, onOpenTrack,
        { plan, name -> actions.run { repository.savePlan(plan.copy(name = name, updatedAt = System.currentTimeMillis().coerceAtLeast(plan.createdAt))) } },
        { plan -> actions.run { repository.deletePlan(plan.id) } },
        { track, name -> actions.run { repository.saveTrack(track.copy(name = name)) } },
        { track -> actions.run { repository.deleteTrack(track.id) } },
        onDismiss, actions.error, actions.busy, preparedRoute = prepared,
        preparedMap = { route, modifier ->
            WeatherRouteMap(route = route, assessment = null, preparedRoutes = preparedRoutes,
                modifier = modifier, expandable = false)
        })
}

@Composable
internal fun TravelLibraryContent(plans: List<SavedRoutePlan>, tracks: List<ImportedTrack>,
    onOpenPlan: (SavedRoutePlan) -> Unit, onOpenTrack: (ImportedTrack) -> Unit,
    onRenamePlan: (SavedRoutePlan, String) -> Unit, onDeletePlan: (SavedRoutePlan) -> Unit,
    onRenameTrack: (ImportedTrack, String) -> Unit, onDeleteTrack: (ImportedTrack) -> Unit,
    onDismiss: () -> Unit, error: String? = null, busy: Boolean = false,
    preparedRoute: PreparedLibraryRoute? = null,
    preparedMap: @Composable (PlannedRoute, Modifier) -> Unit = { _, _ -> },
) {
    var renamePlan by remember { mutableStateOf<SavedRoutePlan?>(null) }
    var renameTrack by remember { mutableStateOf<ImportedTrack?>(null) }
    var deletePlan by remember { mutableStateOf<SavedRoutePlan?>(null) }
    var deleteTrack by remember { mutableStateOf<ImportedTrack?>(null) }
    var previewFingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val selected = preparedRoute?.takeIf { it.fingerprint == previewFingerprint }
    LibraryWindow(if (selected != null) "Hazır rota" else "Rotalarım",
        onDismiss = { if (selected != null) previewFingerprint = null else onDismiss() }, busy = busy) {
        if (selected != null) {
            Text(selected.route.stops.last().label, style = MaterialTheme.typography.titleLarge)
            Text(selected.mapStatus, color = Muted)
            preparedMap(selected.route, Modifier.fillMaxWidth().weight(1f).testTag("prepared-library-preview"))
            return@LibraryWindow
        }
        LibraryStatus(error, busy)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("routes-list"), state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preparedRoute == null && plans.isEmpty() && tracks.isEmpty()) item {
                Text("Henüz kayıtlı rota yok. Rota planından veya GPX önizlemesinden kaydedebilirsin.")
            }
            items(plans, key = { "plan-${it.id}" }) { plan ->
                val destination = plan.stops.lastOrNull()?.label?.takeIf { it.isNotBlank() } ?: "Hedef bilgisi yok"
                LibraryCard(plan.name, "Rota planı", "$destination · ${plan.stops.size} durak", "plan-${plan.id}", busy,
                    onOpen = { onOpenPlan(plan) }, onRename = { renamePlan = plan }, onDelete = { deletePlan = plan })
            }
            if (preparedRoute != null) item(key = "prepared") {
                LibraryCard(preparedRoute.route.stops.last().label, "Hazır rota", preparedRoute.mapStatus, "prepared-route", busy,
                    onOpen = { previewFingerprint = preparedRoute.fingerprint })
            }
            items(tracks, key = { "track-${it.id}" }) { track ->
                val details = remember(track) {
                    val target = track.segments.lastOrNull { it.points.isNotEmpty() }?.points?.last()
                    val endpoint = target?.let {
                        String.format(Locale.forLanguageTag("tr-TR"), "Bitiş: %.5f° / %.5f°", it.latitude, it.longitude)
                    }
                    listOfNotNull(endpoint, "${track.segments.size} bölüm · ${track.segments.sumOf { it.points.size }} konum")
                        .joinToString(" · ")
                }
                LibraryCard(track.name, "GPX izi", details, "track-${track.id}", busy,
                    onOpen = { onOpenTrack(track) }, onRename = { renameTrack = track }, onDelete = { deleteTrack = track })
            }
        }
    }
    renamePlan?.let { plan -> LibraryNameDialog("Rota adını değiştir", plan.name, { renamePlan = null }) { name -> onRenamePlan(plan, name); renamePlan = null } }
    renameTrack?.let { track -> LibraryNameDialog("GPX adını değiştir", track.name, { renameTrack = null }) { name -> onRenameTrack(track, name); renameTrack = null } }
    deletePlan?.let { plan -> LibraryDeleteDialog(plan.name, "Yalnız kaydedilmiş rota planı silinir.", { deletePlan = null }) { onDeletePlan(plan); deletePlan = null } }
    deleteTrack?.let { track -> LibraryDeleteDialog(track.name, "Yalnız kütüphanedeki GPX kopyası silinir.", { deleteTrack = null }) { onDeleteTrack(track); deleteTrack = null } }
}

@Composable
fun JourneyCollectionsScreen(repository: TravelLibraryRepository, snapshot: DiarySnapshot,
    onOpenJourney: (Journey) -> Unit, onDismiss: () -> Unit,
    analysis: org.iz.navigation.power.AnalysisState = org.iz.navigation.power.AnalysisState()) {
    val collections by repository.collections.collectAsStateWithLifecycle(emptyList())
    val memberships by repository.memberships.collectAsStateWithLifecycle(emptyList())
    val actions = rememberLibraryActions()
    val actionEntry = LocalUiEntryKey.current
    UiEditOwner(UiEditState(actionEntry + "/library-work", null, 0, 0, false, actions.busy),
        currentState = { UiEditState(actionEntry + "/library-work", null, 0, 0, false, actions.busy) }, discardUnsaved = {})
    JourneyCollectionsContent(collections, memberships, snapshot,
        { name -> actions.run { repository.saveCollection(JourneyCollection(name = name)) } },
        { collection, name -> actions.run { repository.saveCollection(collection.copy(name = name, updatedAt = System.currentTimeMillis().coerceAtLeast(collection.createdAt))) } },
        { collection -> actions.run { repository.deleteCollection(collection.id) } },
        { id, ids -> actions.run { repository.setCollectionJourneys(id, ids) } }, onOpenJourney, onDismiss, actions.error, actions.busy, analysis)
}

@Composable
internal fun JourneyCollectionsContent(collections: List<JourneyCollection>, memberships: List<CollectionMembership>,
    snapshot: DiarySnapshot, onCreate: (String) -> Unit, onRename: (JourneyCollection, String) -> Unit,
    onDelete: (JourneyCollection) -> Unit, onSetJourneys: (String, List<String>) -> Unit,
    onOpenJourney: (Journey) -> Unit, onDismiss: () -> Unit, error: String? = null, busy: Boolean = false,
    analysis: org.iz.navigation.power.AnalysisState = org.iz.navigation.power.AnalysisState()) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<JourneyCollection?>(null) }
    var deleting by remember { mutableStateOf<JourneyCollection?>(null) }
    var selecting by remember { mutableStateOf<JourneyCollection?>(null) }
    val collectionsListState = rememberLazyListState()
    val state = remember(snapshot, collections, memberships) { snapshot.copy(collections = collections, memberships = memberships) }
    val selected = collections.firstOrNull { it.id == selectedId }
    LibraryWindow(selected?.name ?: "Geziler", { if (selectedId != null) selectedId = null else onDismiss() }, busy = busy) {
        LibraryStatus(error, busy)
        if (selected == null) {
            Button(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Yeni gezi") }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = collectionsListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (collections.isEmpty()) item { Text("Henüz gezi yok.") }
                items(collections, key = { it.id }) { collection ->
                    val summary = remember(collection.id, state, analysis) { preparedCollectionSummary(collection.id, state, analysis) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(collection.name, style = MaterialTheme.typography.titleMedium)
                            CollectionTotals(summary, if (analysis.phase == org.iz.navigation.power.AnalysisPhase.READY) "Ayrıntılar hazırlanıyor" else analysis.label)
                            OutlinedButton(onClick = { selectedId = collection.id }, modifier = Modifier.testTag("open-collection-${collection.id}")) { Text("Geziyi aç") }
                        }
                    }
                }
            }
        } else {
            val journeys = remember(selected.id, state) { TravelLibraryRules.collectionJourneys(selected.id, state) }
            val summary = remember(selected.id, state, analysis) { preparedCollectionSummary(selected.id, state, analysis) }
            CollectionTotals(summary, if (analysis.phase == org.iz.navigation.power.AnalysisPhase.READY) "Ayrıntılar hazırlanıyor" else analysis.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selecting = selected }, enabled = !busy) { Text("Yolculuk seç") }
                TextButton(onClick = { renaming = selected }, enabled = !busy) { Text("Adı değiştir") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (journeys.isEmpty()) item { Text("Henüz yolculuk eklenmedi.") }
                items(journeys, key = { it.id }) { journey ->
                    OutlinedCard(onClick = { onOpenJourney(journey) },
                        modifier = Modifier.fillMaxWidth().testTag("open-journey-${journey.id}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(journey.title.ifBlank { "Adsız yolculuk" }, style = MaterialTheme.typography.titleMedium)
                            Text(journey.transport.label(), style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
            }
            TextButton(onClick = { deleting = selected }, enabled = !busy) {
                Text("Geziyi sil", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (creating) LibraryNameDialog("Yeni gezi", "", { creating = false }) { onCreate(it); creating = false }
    renaming?.let { collection -> LibraryNameDialog("Gezi adını değiştir", collection.name, { renaming = null }) { name -> onRename(collection, name); renaming = null } }
    deleting?.let { collection -> LibraryDeleteDialog(collection.name, "Yolculuklar ve fotoğraflar günlükte kalır.", { deleting = null }) {
        onDelete(collection); deleting = null; selectedId = null
    } }
    selecting?.let { collection ->
        CollectionJourneyPicker(collection, snapshot.journeys.filter(TravelLibraryRules::isEligible),
            TravelLibraryRules.collectionJourneys(collection.id, state).map { it.id }, { selecting = null }) {
            onSetJourneys(collection.id, it); selecting = null
        }
    }
}

@Composable
private fun CollectionJourneyPicker(collection: JourneyCollection, eligible: List<Journey>, initial: List<String>,
    onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val baseline = remember(initial) { initial.distinct() }
    var ordered by remember(collection.id) { mutableStateOf(baseline) }
    val byId = remember(eligible) { eligible.associateBy { it.id } }
    val back = rememberUiEditExit("collection-members-${collection.id}", ordered, baseline,
        currentWorking = { ordered }, discardUnsaved = { ordered = baseline }, exit = onDismiss)

    fun move(id: String, offset: Int) {
        val index = ordered.indexOf(id)
        val target = index + offset
        if (index >= 0 && target in ordered.indices) {
            ordered = ordered.toMutableList().apply { add(target, removeAt(index)) }
        }
    }

    LibraryWindow("${collection.name}: yolculuklar", back) {
        Text("Tamamlanmış yolculukları seç ve gezi sırasına diz.", style = MaterialTheme.typography.bodyMedium, color = Muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ordered, key = { "selected-$it" }) { id ->
                val journey = byId[id]
                val title = journey?.title?.ifBlank { "Adsız yolculuk" } ?: "Artık seçilemeyen yolculuk"
                val index = ordered.indexOf(id)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(true, { ordered = ordered - id },
                                Modifier.testTag("select-journey-$id").semantics { contentDescription = "$title: seçimden çıkar" })
                            Text(title, Modifier.weight(1f))
                        }
                        Row {
                            TextButton(onClick = { move(id, -1) }, enabled = index > 0,
                                modifier = Modifier.testTag("move-up-$id").semantics { contentDescription = "$title: yukarı taşı" }) { Text("Yukarı") }
                            TextButton(onClick = { move(id, 1) }, enabled = index < ordered.lastIndex,
                                modifier = Modifier.testTag("move-down-$id").semantics { contentDescription = "$title: aşağı taşı" }) { Text("Aşağı") }
                        }
                    }
                }
            }
            val selectedIds = ordered.toSet()
            items(eligible.filter { it.id !in selectedIds }, key = { "available-${it.id}" }) { journey ->
                val title = journey.title.ifBlank { "Adsız yolculuk" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(false, { checked -> if (checked && journey.id !in ordered) ordered = ordered + journey.id },
                        Modifier.testTag("select-journey-${journey.id}").semantics { contentDescription = "$title: yolculuğu seç" })
                    Text(title)
                }
            }
            if (eligible.isEmpty()) item { Text("Eklenebilecek tamamlanmış yolculuk yok.") }
        }
        Button(onClick = { if (ordered.all { it in byId }) onSave(ordered) }, enabled = ordered.all { it in byId },
            modifier = Modifier.fillMaxWidth()) { Text("Seçimi kaydet") }
    }
}

private fun preparedCollectionSummary(id: String, snapshot: DiarySnapshot,
    analysis: org.iz.navigation.power.AnalysisState): CollectionSummary? {
    val journeys = TravelLibraryRules.collectionJourneys(id, snapshot)
    if (analysis.pending || analysis.phase == org.iz.navigation.power.AnalysisPhase.WAITING_END) return null
    val stats = journeys.map { journey ->
        analysis.ready(journey.id)?.takeIf { it.matches(journey) }?.stats ?: return null
    }
    return CollectionSummary(journeys.size, stats.sumOf { it.distanceMeters }, stats.sumOf { it.elapsedMillis },
        stats.sumOf { it.movingMillis }, stats.sumOf { it.stoppedMillis })
}

@Composable
private fun CollectionTotals(summary: CollectionSummary?, waitingLabel: String) {
    if (summary == null) { Text(waitingLabel); return }
    Text(String.format(Locale.getDefault(), "%d yolculuk · %.1f km · %d dk toplam", summary.journeyCount, summary.distanceMeters / 1000.0, summary.elapsedMillis / 60_000))
    Text("Hareket ${summary.movingMillis / 60_000} dk · Duraklama ${summary.stoppedMillis / 60_000} dk", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LibraryWindow(title: String, onDismiss: () -> Unit, busy: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TravelScreenHeader(title, onDismiss, enabled = !busy)
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(name: String, kind: String, detail: String, key: String, busy: Boolean,
    onOpen: () -> Unit, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kind, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            LibraryCardActions(key, busy, onOpen, onRename, onDelete)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCardActions(key: String, busy: Boolean, onOpen: () -> Unit,
    onRename: (() -> Unit)?, onDelete: (() -> Unit)?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onOpen, modifier = Modifier.testTag("open-$key"), enabled = !busy) { Text("Rotayı aç") }
        onRename?.let {
            TextButton(onClick = it, modifier = Modifier.testTag("rename-$key"), enabled = !busy) { Text("Adı değiştir") }
        }
        onDelete?.let {
            TextButton(onClick = it, modifier = Modifier.testTag("delete-$key"), enabled = !busy) {
                Text("Sil", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LibraryNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = runCatching { TravelLibraryRules.validName(name.trim()) }.isSuccess
    val back = rememberUiEditExit("library-name", name, initial, currentWorking = { name },
        discardUnsaved = { name = initial }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it }, label = { Text("Ad") }, singleLine = true, modifier = Modifier.testTag("library_name"),
            supportingText = { Text("En fazla 300 karakter") }, isError = name.isNotEmpty() && !valid)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun LibraryDeleteDialog(name: String, detail: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("$name silinsin mi?") }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = onDelete) { Text("Sil") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun LibraryStatus(error: String?, busy: Boolean) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private class LibraryActions(val launch: (suspend () -> Unit) -> Unit) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Değişiklik kaydedilemedi." }
            finally { busy = false }
        }
    }
}

@Composable
private fun rememberLibraryActions(): LibraryActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { LibraryActions { action -> scope.launch { action() }; Unit } }
}
