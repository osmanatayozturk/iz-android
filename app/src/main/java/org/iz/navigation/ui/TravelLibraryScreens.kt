package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.iz.navigation.data.*
import org.iz.navigation.gpx.ImportedTrack

/** Opens saved input in the coordinator's editor/preview; never starts navigation itself. */
@Composable
fun TravelLibraryScreen(repository: TravelLibraryRepository, onOpenPlan: (SavedRoutePlan) -> Unit,
    onOpenTrack: (ImportedTrack) -> Unit, onDismiss: () -> Unit) {
    val plans by repository.savedPlans.collectAsStateWithLifecycle(emptyList())
    val tracks by repository.importedTracks.collectAsStateWithLifecycle(emptyList())
    val actions = rememberLibraryActions()
    TravelLibraryContent(plans, tracks, onOpenPlan, onOpenTrack,
        { plan, name -> actions.run { repository.savePlan(plan.copy(name = name, updatedAt = System.currentTimeMillis().coerceAtLeast(plan.createdAt))) } },
        { plan -> actions.run { repository.deletePlan(plan.id) } },
        { track, name -> actions.run { repository.saveTrack(track.copy(name = name)) } },
        { track -> actions.run { repository.deleteTrack(track.id) } }, onDismiss, actions.error, actions.busy)
}

@Composable
internal fun TravelLibraryContent(plans: List<SavedRoutePlan>, tracks: List<ImportedTrack>,
    onOpenPlan: (SavedRoutePlan) -> Unit, onOpenTrack: (ImportedTrack) -> Unit,
    onRenamePlan: (SavedRoutePlan, String) -> Unit, onDeletePlan: (SavedRoutePlan) -> Unit,
    onRenameTrack: (ImportedTrack, String) -> Unit, onDeleteTrack: (ImportedTrack) -> Unit,
    onDismiss: () -> Unit, error: String? = null, busy: Boolean = false) {
    var renamePlan by remember { mutableStateOf<SavedRoutePlan?>(null) }
    var renameTrack by remember { mutableStateOf<ImportedTrack?>(null) }
    var deletePlan by remember { mutableStateOf<SavedRoutePlan?>(null) }
    var deleteTrack by remember { mutableStateOf<ImportedTrack?>(null) }
    LibraryWindow("Rotalarım", onDismiss) {
        Text("Kaydettiğin planları düzenlemek veya GPX izlerini incelemek için aç.", style = MaterialTheme.typography.bodyMedium)
        LibraryStatus(error, busy)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (plans.isEmpty() && tracks.isEmpty()) item {
                Text("Henüz kayıtlı rota yok. Rota planından veya GPX önizlemesinden kaydedebilirsin.")
            }
            items(plans, key = { "plan-${it.id}" }) { plan ->
                LibraryCard(plan.name, "Rota planı", "${plan.stops.size} durak", "plan-${plan.id}", busy,
                    { onOpenPlan(plan) }, { renamePlan = plan }, { deletePlan = plan })
            }
            items(tracks, key = { "track-${it.id}" }) { track ->
                LibraryCard(track.name, "GPX izi", "${track.segments.size} bölüm · ${track.segments.sumOf { it.points.size }} konum", "track-${track.id}", busy,
                    { onOpenTrack(track) }, { renameTrack = track }, { deleteTrack = track })
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
    onOpenJourney: (Journey) -> Unit, onDismiss: () -> Unit) {
    val collections by repository.collections.collectAsStateWithLifecycle(emptyList())
    val memberships by repository.memberships.collectAsStateWithLifecycle(emptyList())
    val actions = rememberLibraryActions()
    JourneyCollectionsContent(collections, memberships, snapshot,
        { name -> actions.run { repository.saveCollection(JourneyCollection(name = name)) } },
        { collection, name -> actions.run { repository.saveCollection(collection.copy(name = name, updatedAt = System.currentTimeMillis().coerceAtLeast(collection.createdAt))) } },
        { collection -> actions.run { repository.deleteCollection(collection.id) } },
        { id, ids -> actions.run { repository.setCollectionJourneys(id, ids) } }, onOpenJourney, onDismiss, actions.error, actions.busy)
}

@Composable
internal fun JourneyCollectionsContent(collections: List<JourneyCollection>, memberships: List<CollectionMembership>,
    snapshot: DiarySnapshot, onCreate: (String) -> Unit, onRename: (JourneyCollection, String) -> Unit,
    onDelete: (JourneyCollection) -> Unit, onSetJourneys: (String, List<String>) -> Unit,
    onOpenJourney: (Journey) -> Unit, onDismiss: () -> Unit, error: String? = null, busy: Boolean = false) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<JourneyCollection?>(null) }
    var deleting by remember { mutableStateOf<JourneyCollection?>(null) }
    var selecting by remember { mutableStateOf<JourneyCollection?>(null) }
    val state = remember(snapshot, collections, memberships) { snapshot.copy(collections = collections, memberships = memberships) }
    val selected = collections.firstOrNull { it.id == selectedId }
    LibraryWindow(selected?.name ?: "Geziler", onDismiss) {
        LibraryStatus(error, busy)
        if (selected == null) {
            Text("Tamamlanan yolculuklarını bir gezide bir araya getir. Aynı yolculuk birden fazla gezide yer alabilir.")
            Button(onClick = { creating = true }, enabled = !busy) { Text("Yeni gezi") }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (collections.isEmpty()) item { Text("Henüz gezi yok.") }
                items(collections, key = { it.id }) { collection ->
                    val summary = remember(collection.id, state) { TravelLibraryRules.collectionSummary(collection.id, state) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(collection.name, style = MaterialTheme.typography.titleMedium)
                            CollectionTotals(summary)
                            OutlinedButton(onClick = { selectedId = collection.id }, modifier = Modifier.testTag("open-collection-${collection.id}")) { Text("Geziyi aç") }
                        }
                    }
                }
            }
        } else {
            val journeys = remember(selected.id, state) { TravelLibraryRules.collectionJourneys(selected.id, state) }
            val summary = remember(selected.id, state) { TravelLibraryRules.collectionSummary(selected.id, state) }
            TextButton(onClick = { selectedId = null }) { Text("Tüm geziler") }
            CollectionTotals(summary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selecting = selected }, enabled = !busy) { Text("Yolculuk seç") }
                TextButton(onClick = { renaming = selected }, enabled = !busy) { Text("Adı değiştir") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (journeys.isEmpty()) item { Text("Henüz yolculuk eklenmedi.") }
                items(journeys, key = { it.id }) { journey ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(journey.title.ifBlank { "Adsız yolculuk" }, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { onOpenJourney(journey) }, modifier = Modifier.testTag("open-journey-${journey.id}")) { Text("Yolculuk ayrıntıları") }
                        }
                    }
                }
            }
            TextButton(onClick = { deleting = selected }, enabled = !busy) { Text("Geziyi sil") }
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
    var ordered by remember(collection.id) { mutableStateOf(initial.distinct()) }
    val byId = eligible.associateBy { it.id }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${collection.name}: yolculuklar", style = MaterialTheme.typography.titleLarge)
                Text("Yalnız tamamlanmış ve kalıcı yolculuklar seçilebilir. Yukarı ve aşağı düğmeleri gezi sırasını değiştirir.")
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ordered, key = { "selected-$it" }) { id ->
                        val journey = byId[id]
                        val title = journey?.title?.ifBlank { "Adsız yolculuk" } ?: "Artık seçilemeyen yolculuk"
                        val index = ordered.indexOf(id)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(true, { ordered = ordered - id }, Modifier.testTag("select-journey-$id").semantics { contentDescription = "$title yolculuğunu seç" })
                                    Text(title, Modifier.weight(1f))
                                }
                                Row {
                                    TextButton(onClick = { ordered = ordered.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0,
                                        modifier = Modifier.testTag("move-up-$id").semantics { contentDescription = "$title: yukarı taşı" }) { Text("Yukarı") }
                                    TextButton(onClick = { ordered = ordered.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < ordered.lastIndex,
                                        modifier = Modifier.testTag("move-down-$id").semantics { contentDescription = "$title: aşağı taşı" }) { Text("Aşağı") }
                                }
                            }
                        }
                    }
                    val selectedIds = ordered.toSet()
                    items(eligible.filter { it.id !in selectedIds }, key = { "available-${it.id}" }) { journey ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(false, { checked -> if (checked) ordered = ordered + journey.id }, Modifier.testTag("select-journey-${journey.id}").semantics { contentDescription = "${journey.title.ifBlank { "Adsız yolculuk" }} yolculuğunu seç" })
                            Text(journey.title.ifBlank { "Adsız yolculuk" })
                        }
                    }
                    if (eligible.isEmpty()) item { Text("Eklenebilecek tamamlanmış yolculuk yok.") }
                }
                Button(onClick = { onSave(ordered.toList()) }, enabled = ordered.all { it in byId }, modifier = Modifier.fillMaxWidth()) { Text("Seçimi kaydet") }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        }
    }
}

@Composable
private fun CollectionTotals(summary: CollectionSummary) {
    Text(String.format(Locale.getDefault(), "%d yolculuk · %.1f km · %d dk toplam", summary.journeyCount, summary.distanceMeters / 1000.0, summary.elapsedMillis / 60_000))
    Text("Hareket ${summary.movingMillis / 60_000} dk · Duraklama ${summary.stoppedMillis / 60_000} dk", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LibraryWindow(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Kapat") }
                }
                content()
            }
        }
    }
}

@Composable
private fun LibraryCard(name: String, kind: String, detail: String, key: String, busy: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kind, style = MaterialTheme.typography.labelLarge)
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.testTag("open-$key"), enabled = !busy) { Text("Aç") }
                TextButton(onClick = onRename, modifier = Modifier.testTag("rename-$key"), enabled = !busy) { Text("Adı değiştir") }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("delete-$key"), enabled = !busy) { Text("Sil") }
            }
        }
    }
}

@Composable
private fun LibraryNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = runCatching { TravelLibraryRules.validName(name.trim()) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
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
