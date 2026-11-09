package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.iz.navigation.ui.history.rememberUiEditExit
import org.iz.navigation.ui.history.LocalUiOperationBusy
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.*
import java.util.Locale

@Composable
fun PlaceDetailsEditor(place: Place, osm: SelectedOsmPlace?, onSearch: () -> Unit, onDismiss: () -> Unit, onSave: (Place) -> Unit) {
    val operationBusy = LocalUiOperationBusy.current
    var name by remember(place.id) { mutableStateOf(place.name) }
    var latitude by remember(place.id) { mutableStateOf(place.latitude?.toString().orEmpty()) }
    var longitude by remember(place.id) { mutableStateOf(place.longitude?.toString().orEmpty()) }
    var reference by remember { mutableStateOf(place.osmType?.let { type -> place.osmId?.let { OsmRef(type, it) } }) }
    var source by remember { mutableStateOf(place.source) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(osm) {
        if (osm != null) { name = osm.name; latitude = osm.latitude.toString(); longitude = osm.longitude.toString(); reference = osm.osmRef; source = if (osm.osmRef == null) PlaceSource.USER else PlaceSource.OSM }
    }
    val originalReference = place.osmType?.let { type -> place.osmId?.let { OsmRef(type, it) } }
    val working = { listOf(name, latitude, longitude, reference, source) }
    val baseline = listOf(place.name, place.latitude?.toString().orEmpty(), place.longitude?.toString().orEmpty(), originalReference, place.source)
    val back = rememberUiEditExit("place-${place.id}", working(), baseline, currentWorking = working,
        discardUnsaved = { name = place.name; latitude = place.latitude?.toString().orEmpty(); longitude = place.longitude?.toString().orEmpty(); reference = originalReference; source = place.source }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text("Yeri düzenle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { if (!operationBusy()) name = it }, enabled = !operationBusy(), label = { Text("Bu yere verdiğin ad") })
            OutlinedTextField(latitude, { if (!operationBusy()) { latitude = it; reference = null; source = PlaceSource.USER } }, enabled = !operationBusy(), label = { Text("Enlem (isteğe bağlı)") })
            OutlinedTextField(longitude, { if (!operationBusy()) { longitude = it; reference = null; source = PlaceSource.USER } }, enabled = !operationBusy(), label = { Text("Boylam (isteğe bağlı)") })
            OutlinedButton(onClick = onSearch) { Text("OSM’de yer eşleştir") }
            Text(reference?.let { "OSM: ${it.type.name.lowercase(Locale.ROOT)}/${it.id}" } ?: "Kişisel yer", style = MaterialTheme.typography.bodySmall)
            if (reference != null) TextButton(onClick = { reference = null; source = PlaceSource.USER }) { Text("OSM eşlemesini kaldır") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        val lat = latitude.replace(',', '.').toDoubleOrNull(); val lon = longitude.replace(',', '.').toDoubleOrNull()
        if ((latitude.isNotBlank() || longitude.isNotBlank()) && (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)) error = "Geçerli enlem ve boylamı birlikte gir."
        else onSave(place.copy(name = name.trim(), latitude = lat, longitude = lon, osmType = reference?.type, osmId = reference?.id, source = source))
    }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
internal fun PlaceEditor(
    pin: GeoCoordinate?, osm: SelectedOsmPlace?, places: List<Place>, onDismiss: () -> Unit,
    onSearch: () -> Unit, onLocate: () -> Unit, assignmentKind: DestinationShortcutKind? = null,
    saveError: String? = null, onMapNote: (() -> Unit)? = null, onOsmCorrection: (() -> Unit)? = null,
    onSave: (String, String, Place?) -> Unit,
) {
    val operationBusy = LocalUiOperationBusy.current
    val exitGuard = org.iz.navigation.ui.history.LocalUiExitGuard.current
    val assigning = assignmentKind != null
    val initialName = remember { osm?.name.orEmpty() }
    val initialPin = remember { pin }
    val initialReference = remember { osm?.osmRef }
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Place?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(osm) {
        if (osm != null) {
            name = osm.name
            selected = osm.osmRef?.let { ref -> places.firstOrNull { it.osmType == ref.type && it.osmId == ref.id } }
        }
    }
    val initialSelected = remember { initialReference?.let { ref -> places.firstOrNull { it.osmType == ref.type && it.osmId == ref.id }?.id } }
    val working = { listOf(name, note, selected?.id, pin, osm?.osmRef) }
    val baseline = listOf(initialName, "", initialSelected, initialPin, initialReference)
    val back = rememberUiEditExit("new-place", working(), baseline, currentWorking = working,
        discardUnsaved = { name = initialName; note = ""; selected = places.firstOrNull { it.id == initialSelected } }, exit = onDismiss)
    val validPin = pin != null && pin.latitude in -90.0..90.0 && pin.longitude in -180.0..180.0
    val canAssign = selected?.isValidDestinationShortcut() ?: (validPin && name.trim().length in 1..300)
    AlertDialog(onDismissRequest = back,
        title = { Text(assignmentKind?.let(::shortcutAssignmentTitle) ?: "Bir yer kaydet") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (places.isNotEmpty()) Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !assigning || !operationBusy()) {
                    Text(selected?.name ?: "Kayıtlı yerlerimden seç")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem({ Text("Yeni yer oluştur") }, { selected = null; expanded = false })
                    places.forEach { place -> DropdownMenuItem({ Text(place.name) }, { selected = place; expanded = false }) }
                }
            }
            if (selected == null) {
                OutlinedTextField(name, { if (!operationBusy()) name = it }, enabled = !operationBusy(), label = { Text("Bu yere verdiğin ad") }, singleLine = true)
                TextButton(onClick = onLocate, enabled = !assigning || !operationBusy()) { Text("Bulunduğum konumu kullan") }
                val locationHint = if (assigning) "Adres için konum seç." else "Konum isteğe bağlı. Haritaya uzun basarak da seçebilirsin."
                Text(pin?.let { String.format(Locale.ROOT, "Seçilen konum: %.5f, %.5f", it.latitude, it.longitude) }
                    ?: locationHint, style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(onClick = onSearch, enabled = !assigning || !operationBusy()) {
                    Text(if (osm == null) "OSM’de yer eşleştir" else "OSM eşlemesini değiştir")
                }
                osm?.let { Text("OSM: ${it.name}", style = MaterialTheme.typography.bodySmall, color = Muted) }
                onMapNote?.let { openNote ->
                    OutlinedButton(onClick = { if (exitGuard == null) openNote() else exitGuard.requestExit(openNote) },
                        enabled = !operationBusy()) { Text("Harita notu yaz") }
                }
                onOsmCorrection?.let { correction ->
                    OutlinedButton(onClick = correction, enabled = !operationBusy()) { Text("OSM mekânını düzelt") }
                }
            }
            if (!assigning) OutlinedTextField(note, { if (!operationBusy()) note = it }, enabled = !operationBusy(),
                label = { Text("Bu ziyarete özel not") }, minLines = 2)
            saveError?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(name, note, selected) },
            enabled = if (assigning) canAssign && !operationBusy() else selected != null || name.isNotBlank(),
            modifier = if (assigning) Modifier.heightIn(min = 48.dp).testTag("shortcut-assignment-editor-save") else Modifier) {
            Text(if (assigning) "Adresi kaydet" else "Ziyareti kaydet")
        }
    }, dismissButton = {
        TextButton(onClick = if (assigning) back else onDismiss, enabled = !assigning || !operationBusy()) { Text("Vazgeç") }
    })
}

@Composable
internal fun VisitEditor(visit: Visit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val operationBusy = LocalUiOperationBusy.current
    var note by remember(visit.id) { mutableStateOf(visit.note) }
    val back = rememberUiEditExit("visit-${visit.id}", note, visit.note,
        currentWorking = { note }, discardUnsaved = { note = visit.note }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text("Ziyaret notun") }, text = {
        OutlinedTextField(note, { if (!operationBusy()) note = it }, enabled = !operationBusy(), label = { Text("Yalnızca sana özel") }, minLines = 4)
    }, confirmButton = { TextButton(onClick = { onSave(note.trim()) }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
