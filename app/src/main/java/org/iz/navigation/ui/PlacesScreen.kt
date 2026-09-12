package org.iz.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.iz.navigation.data.Place
import kotlin.math.abs

@Composable
internal fun PlacesScreen(state: DiaryState, onPlace: (Place) -> Unit, onAdd: () -> Unit,
    onReorder: suspend (List<String>, List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var original by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var draft by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val sorting = original != null
    fun cancel() { original = null; draft = emptyList(); error = null }
    BackHandler(sorting && !saving) { cancel() }
    val byId = remember(state.places) { state.places.associateBy { it.id } }
    val visible = if (sorting) draft.mapNotNull(byId::get) else state.places.filter { it.name.contains(query, ignoreCase = true) }
    fun move(id: String, direction: Int) {
        if (!sorting || saving) return
        val from = draft.indexOf(id)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, draft.lastIndex)
        if (from == to) return
        draft = draft.toMutableList().apply { add(to, removeAt(from)) }
        error = null
        val targetIndex = to + 1 // The header is one lazy item.
        if (list.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) scope.launch { list.animateScrollToItem(targetIndex) }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.testTag("places-list")) {
        item(key = "places-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Biriktirdiğin yerler", style = MaterialTheme.typography.headlineMedium)
                Text("Bir kahve molası, bir manzara, bir anı.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("places-search"),
                    enabled = !sorting, placeholder = { Text("Günlüğünde yer ara") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(18.dp))
                if (sorting) {
                    Text("Tutamacı sürükle veya okları kullan. Bu sıra rota noktası seçiminde de kullanılır.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            val before = original ?: return@Button
                            val requested = draft.toList()
                            saving = true; error = null
                            scope.launch {
                                try { onReorder(before, requested); cancel() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "Sıralama kaydedilemedi. Yeniden deneyebilirsin." }
                                finally { saving = false }
                            }
                        }, enabled = !saving, modifier = Modifier.testTag("places-sort-save")) { Text(if (saving) "Kaydediliyor…" else "Kaydet") }
                        OutlinedButton(onClick = ::cancel, enabled = !saving, modifier = Modifier.testTag("places-sort-cancel")) { Text("Vazgeç") }
                    }
                    if (state.places.map { it.id } != original) Text("Yer listesi değişti. Kaydetmeden önce iptal edip sıralamayı yeniden aç.", color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedButton(onClick = {
                        original = state.places.map { it.id }; draft = original.orEmpty(); error = null
                    }, enabled = query.isBlank() && state.places.size > 1, modifier = Modifier.testTag("places-sort")) {
                        Icon(Icons.Outlined.Sort, null); Spacer(Modifier.width(8.dp)); Text("Sırala")
                    }
                    if (query.isNotBlank()) Text("Sıralamak için aramayı temizle.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("places-sort-error")) }
                if (visible.isEmpty()) Text(if (query.isBlank()) "Burada senin yerlerin olacak. Haritadan bir nokta seç veya bir yer kaydet." else "Aramana uygun yer bulunamadı.")
            }
        }
        itemsIndexed(visible, key = { _, place -> place.id }) { index, place ->
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White,
                modifier = Modifier.fillMaxWidth().testTag("place-row-${place.id}").then(if (sorting) Modifier else Modifier.clickable { onPlace(place) })) {
                Row(Modifier.fillMaxWidth().padding(if (sorting) 8.dp else 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sorting) PlaceOrderHandle(place, !saving, { move(place.id, it) })
                    else Icon(Icons.Outlined.Place, null, tint = Forest, modifier = Modifier.size(48.dp).background(Leaf, RoundedCornerShape(14.dp)).padding(12.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (sorting) "${index + 1}. sıra" else "${state.visits.count { it.placeId == place.id }} ziyaret · ${if (place.osmId == null) "Kişisel yer" else "OSM ile eşleşti"}",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    if (sorting) {
                        IconButton(onClick = { move(place.id, -1) }, enabled = !saving && index > 0,
                            modifier = Modifier.testTag("place-up-${place.id}")) { Icon(Icons.Outlined.KeyboardArrowUp, "${place.name}: yukarı taşı") }
                        IconButton(onClick = { move(place.id, 1) }, enabled = !saving && index < visible.lastIndex,
                            modifier = Modifier.testTag("place-down-${place.id}")) { Icon(Icons.Outlined.KeyboardArrowDown, "${place.name}: aşağı taşı") }
                    } else Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        if (!sorting) item(key = "add-place") { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("Bir yer kaydet") } }
    }
}

@Composable
private fun PlaceOrderHandle(place: Place, enabled: Boolean, onMove: (Int) -> Unit) {
    val move by rememberUpdatedState(onMove)
    val allowed by rememberUpdatedState(enabled)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Icon(Icons.Outlined.DragHandle, null, tint = Forest,
        modifier = Modifier.size(48.dp).testTag("place-drag-${place.id}")
            .semantics { contentDescription = "${place.name}: sıralamak için sürükle" }
            .pointerInput(place.id, threshold) {
                detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { dragged = 0f }, onDragCancel = { dragged = 0f }) { change, amount ->
                    if (allowed) {
                        change.consume(); dragged += amount
                        while (abs(dragged) >= threshold) {
                            val direction = if (dragged > 0) 1 else -1
                            move(direction); dragged -= threshold * direction
                        }
                    }
                }
            })
}
