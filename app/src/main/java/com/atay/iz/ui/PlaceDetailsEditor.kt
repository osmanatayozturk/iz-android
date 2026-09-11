package com.atay.iz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atay.iz.data.*
import java.util.Locale

@Composable
fun PlaceDetailsEditor(place: Place, osm: SelectedOsmPlace?, onSearch: () -> Unit, onDismiss: () -> Unit, onSave: (Place) -> Unit) {
    var name by rememberSaveable(place.id) { mutableStateOf(place.name) }
    var latitude by rememberSaveable(place.id) { mutableStateOf(place.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable(place.id) { mutableStateOf(place.longitude?.toString().orEmpty()) }
    var reference by remember { mutableStateOf(place.osmType?.let { type -> place.osmId?.let { OsmRef(type, it) } }) }
    var source by remember { mutableStateOf(place.source) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(osm) {
        if (osm != null) { name = osm.name; latitude = osm.latitude.toString(); longitude = osm.longitude.toString(); reference = osm.osmRef; source = if (osm.osmRef == null) PlaceSource.USER else PlaceSource.OSM }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Yeri düzenle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Bu yere verdiğin ad") })
            OutlinedTextField(latitude, { latitude = it; reference = null; source = PlaceSource.USER }, label = { Text("Enlem (isteğe bağlı)") })
            OutlinedTextField(longitude, { longitude = it; reference = null; source = PlaceSource.USER }, label = { Text("Boylam (isteğe bağlı)") })
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
internal fun PlaceEditor(pin: GeoCoordinate?, osm: SelectedOsmPlace?, places: List<Place>, onDismiss: () -> Unit, onSearch: () -> Unit, onLocate: () -> Unit, onSave: (String, String, Place?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Place?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(osm) {
        if (osm != null) {
            name = osm.name
            selected = osm.osmRef?.let { ref -> places.firstOrNull { it.osmType == ref.type && it.osmId == ref.id } }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bir yer kaydet") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (places.isNotEmpty()) Box {
                OutlinedButton(onClick = { expanded = true }) { Text(selected?.name ?: "Kayıtlı yerlerimden seç") }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem({ Text("Yeni yer oluştur") }, { selected = null; expanded = false })
                    places.forEach { place -> DropdownMenuItem({ Text(place.name) }, { selected = place; expanded = false }) }
                }
            }
            if (selected == null) {
                OutlinedTextField(name, { name = it }, label = { Text("Bu yere verdiğin ad") }, singleLine = true)
                TextButton(onClick = onLocate) { Text("Bulunduğum konumu kullan") }
                Text(pin?.let { String.format(Locale.ROOT, "Seçilen konum: %.5f, %.5f", it.latitude, it.longitude) } ?: "Konum isteğe bağlı. Haritaya uzun basarak da seçebilirsin.", style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(onClick = onSearch) { Text(if (osm == null) "OSM’de yer eşleştir" else "OSM eşlemesini değiştir") }
                osm?.let { Text("OSM: ${it.name}", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Bu ziyarete özel not") }, minLines = 2)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, note, selected) }, enabled = selected != null || name.isNotBlank()) { Text("Ziyareti kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
internal fun VisitEditor(visit: Visit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by rememberSaveable(visit.id) { mutableStateOf(visit.note) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ziyaret notun") }, text = {
        OutlinedTextField(note, { note = it }, label = { Text("Yalnızca sana özel") }, minLines = 4)
    }, confirmButton = { TextButton(onClick = { onSave(note.trim()) }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
