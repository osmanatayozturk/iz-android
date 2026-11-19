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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlaceDetailsEditor(place: Place, osm: SelectedOsmPlace?, onSearch: () -> Unit, onDismiss: () -> Unit, onSave: (Place) -> Unit) {
    val operationBusy = LocalUiOperationBusy.current
    var name by remember(place.id) { mutableStateOf(place.name) }
    var latitude by remember(place.id) { mutableStateOf(place.latitude?.toString().orEmpty()) }
    var longitude by remember(place.id) { mutableStateOf(place.longitude?.toString().orEmpty()) }
    var reference by remember(place.id) { mutableStateOf(place.osmType?.let { type -> place.osmId?.let { OsmRef(type, it) } }) }
    var source by remember(place.id) { mutableStateOf(place.source) }
    var error by remember(place.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(place.id, osm) {
        if (osm != null) { name = osm.name; latitude = osm.latitude.toString(); longitude = osm.longitude.toString(); reference = osm.osmRef; source = if (osm.osmRef == null) PlaceSource.USER else PlaceSource.OSM }
    }
    val originalReference = place.osmType?.let { type -> place.osmId?.let { OsmRef(type, it) } }
    val working = { listOf(name, latitude, longitude, reference, source) }
    val baseline = listOf(place.name, place.latitude?.toString().orEmpty(), place.longitude?.toString().orEmpty(), originalReference, place.source)
    val busy = operationBusy()
    val back = rememberUiEditExit("place-${place.id}", working(), baseline, busy = busy,
        currentWorking = working, currentBusy = operationBusy,
        discardUnsaved = { name = place.name; latitude = place.latitude?.toString().orEmpty(); longitude = place.longitude?.toString().orEmpty(); reference = originalReference; source = place.source }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text("Yeri düzenle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { if (!operationBusy()) name = it }, Modifier.fillMaxWidth(),
                enabled = !busy, label = { Text("Bu yere verdiğin ad") }, singleLine = true)
            HorizontalDivider()
            Text("Konum", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(latitude, { if (!operationBusy()) { latitude = it; reference = null; source = PlaceSource.USER } },
                Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Enlem (isteğe bağlı)") }, singleLine = true)
            OutlinedTextField(longitude, { if (!operationBusy()) { longitude = it; reference = null; source = PlaceSource.USER } },
                Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Boylam (isteğe bağlı)") }, singleLine = true)
            OutlinedButton(onClick = onSearch, enabled = !busy) { Text("OSM'de yer eşleştir") }
            reference?.let {
                Text("OSM: ${it.type.name.lowercase(Locale.ROOT)}/${it.id}", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { reference = null; source = PlaceSource.USER }) { Text("OSM eşlemesini kaldır") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(enabled = name.isNotBlank() && !busy, onClick = {
        val lat = latitude.replace(',', '.').toDoubleOrNull(); val lon = longitude.replace(',', '.').toDoubleOrNull()
        if ((latitude.isNotBlank() || longitude.isNotBlank()) && (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)) error = "Geçerli enlem ve boylamı birlikte gir."
        else onSave(place.copy(name = name.trim(), latitude = lat, longitude = lon, osmType = reference?.type, osmId = reference?.id, source = source))
    }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Vazgeç") } })
}

@Composable
internal fun PlaceEditor(
    pin: GeoCoordinate?, osm: SelectedOsmPlace?, places: List<Place>, onDismiss: () -> Unit,
    onSearch: () -> Unit, onLocate: () -> Unit, assignmentKind: DestinationShortcutKind? = null,
    saveError: String? = null, onMapNote: (() -> Unit)? = null, onOsmCorrection: (() -> Unit)? = null,
    onOsmSurvey: (() -> Unit)? = null,
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
    val busy = operationBusy()
    val back = rememberUiEditExit("new-place", working(), baseline, busy = busy, currentWorking = working,
        currentBusy = operationBusy,
        discardUnsaved = { name = initialName; note = ""; selected = places.firstOrNull { it.id == initialSelected } }, exit = onDismiss)
    val validPin = pin != null && pin.latitude in -90.0..90.0 && pin.longitude in -180.0..180.0
    val canAssign = selected?.isValidDestinationShortcut() ?: (validPin && name.trim().length in 1..300)
    AlertDialog(onDismissRequest = back,
        title = { Text(assignmentKind?.let(::shortcutAssignmentTitle) ?: "Bir yer kaydet") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (places.isNotEmpty()) Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                    Text(selected?.name ?: "Kayıtlı yerlerimden seç")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem({ Text("Yeni yer oluştur") }, { selected = null; expanded = false })
                    places.forEach { place -> DropdownMenuItem({ Text(place.name) }, { selected = place; expanded = false }) }
                }
            }
            if (selected == null) {
                OutlinedTextField(name, { if (!operationBusy()) name = it }, Modifier.fillMaxWidth(),
                    enabled = !busy, label = { Text("Bu yere verdiğin ad") }, singleLine = true)
                HorizontalDivider()
                Text("Konum", style = MaterialTheme.typography.titleSmall)
                val locationHint = if (assigning) "Adres için konum seç." else "Konum isteğe bağlı. Haritaya uzun basarak da seçebilirsin."
                Text(pin?.let { String.format(Locale.ROOT, "Seçilen konum: %.5f, %.5f", it.latitude, it.longitude) }
                    ?: locationHint, style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(onClick = onSearch, enabled = !busy) {
                    Text(if (osm == null) "OSM’de yer eşleştir" else "OSM eşlemesini değiştir")
                }
                TextButton(onClick = onLocate, enabled = !busy) { Text("Bulunduğum konumu kullan") }
                osm?.let { Text("OSM: ${it.name}", style = MaterialTheme.typography.bodySmall, color = Muted) }
                onMapNote?.let { openNote ->
                    OutlinedButton(onClick = { if (exitGuard == null) openNote() else exitGuard.requestExit(openNote) },
                        enabled = !operationBusy()) { Text("Harita notu yaz") }
                }
                onOsmCorrection?.let { correction ->
                    OutlinedButton(onClick = correction, enabled = !operationBusy()) { Text("OSM mekânını düzelt") }
                }
                onOsmSurvey?.let { survey ->
                    OutlinedButton(onClick = survey, enabled = !operationBusy(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("osm-place-survey")) { Text("Yerinde tamamla") }
                }
            }
            if (!assigning) {
                HorizontalDivider()
                OutlinedTextField(note, { if (!operationBusy()) note = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Bu ziyarete özel not") }, minLines = 2)
            }
            saveError?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        Button(onClick = { onSave(name, note, selected) },
            enabled = !busy && if (assigning) canAssign else selected != null || name.isNotBlank(),
            modifier = if (assigning) Modifier.heightIn(min = 48.dp).testTag("shortcut-assignment-editor-save") else Modifier) {
            Text(if (assigning) "Adresi kaydet" else "Ziyareti kaydet")
        }
    }, dismissButton = {
        TextButton(onClick = if (assigning) back else onDismiss, enabled = !busy) { Text("Vazgeç") }
    })
}

@Composable
internal fun VisitEditor(
    visit: Visit,
    onDismiss: () -> Unit,
    placeName: String? = null,
    onPhoto: (() -> Unit)? = null,
    onSave: (String) -> Unit,
) {
    val operationBusy = LocalUiOperationBusy.current
    var note by remember(visit.id) { mutableStateOf(visit.note) }
    val busy = operationBusy()
    val dateLabel = remember(visit.visitedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("tr-TR"))
            .format(Date(visit.visitedAt))
    }
    val back = rememberUiEditExit("visit-${visit.id}", note, visit.note, busy = busy,
        currentWorking = { note }, currentBusy = operationBusy,
        discardUnsaved = { note = visit.note }, exit = onDismiss)
    AlertDialog(
        onDismissRequest = back,
        title = { Text("Ziyaret") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        placeName?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(dateLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("visit-date"))
                    }
                }
                OutlinedTextField(note, { if (!operationBusy()) note = it }, Modifier.fillMaxWidth(),
                    enabled = !busy, label = { Text("Yalnızca sana özel") }, minLines = 3)
                onPhoto?.let { attach ->
                    OutlinedButton(onClick = attach, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Fotoğraf ekle")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(note.trim()) }, enabled = !busy) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Vazgeç") } },
    )
}
