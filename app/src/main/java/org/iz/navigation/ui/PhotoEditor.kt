package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.iz.navigation.data.Photo
import org.iz.navigation.ui.history.rememberUiEditExit
import org.iz.navigation.ui.history.LocalUiOperationBusy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhotoEditor(photo: Photo, onDismiss: () -> Unit, onSave: (Photo) -> Unit) {
    val operationBusy = LocalUiOperationBusy.current
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false } }
    var time by remember(photo.id) { mutableStateOf(photo.takenAt?.let { formatter.format(Date(it)) }.orEmpty()) }
    var caption by remember(photo.id) { mutableStateOf(photo.caption) }
    var latitude by remember(photo.id) { mutableStateOf(photo.latitude?.toString().orEmpty()) }
    var longitude by remember(photo.id) { mutableStateOf(photo.longitude?.toString().orEmpty()) }
    var error by remember(photo.id) { mutableStateOf<String?>(null) }
    val working = { listOf(time, caption, latitude, longitude) }
    val originalTime = photo.takenAt?.let { formatter.format(Date(it)) }.orEmpty()
    val baseline = listOf(originalTime, photo.caption, photo.latitude?.toString().orEmpty(), photo.longitude?.toString().orEmpty())
    val back = rememberUiEditExit("photo-${photo.id}", working(), baseline, currentWorking = working,
        discardUnsaved = { time = originalTime; caption = photo.caption; latitude = photo.latitude?.toString().orEmpty(); longitude = photo.longitude?.toString().orEmpty() }, exit = onDismiss)
    AlertDialog(onDismissRequest = back, title = { Text("Fotoğraf bilgileri") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(File(LocalContext.current.filesDir, photo.relativePath), "Fotoğraf önizlemesi", Modifier.fillMaxWidth().height(180.dp))
            Text("Eksik tarih veya konumu ekleyebilir ya da boş bırakabilirsin. Fotoğraf seçtiğin ziyaret veya yolculuğa bağlıdır.", style = MaterialTheme.typography.bodySmall)
            Text("Bu alanlar İz’deki kaydı değiştirir; özgün fotoğraf korunur. Paylaşım ve galeri kopyalarından dosyaya ekli konum ve çekim bilgileri kaldırılır.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(caption, { if (!operationBusy()) caption = it }, enabled = !operationBusy(), label = { Text("Açıklama") })
            OutlinedTextField(time, { if (!operationBusy()) time = it }, enabled = !operationBusy(), label = { Text("Çekim: yyyy-AA-gg SS:dd") }, supportingText = { Text("Telefonun saat dilimi · İsteğe bağlı") }, singleLine = true)
            OutlinedTextField(latitude, { if (!operationBusy()) latitude = it }, enabled = !operationBusy(), label = { Text("Enlem (isteğe bağlı)") }, singleLine = true)
            OutlinedTextField(longitude, { if (!operationBusy()) longitude = it }, enabled = !operationBusy(), label = { Text("Boylam (isteğe bağlı)") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val parsedDate = if (time.isBlank()) null else runCatching { formatter.parse(time)?.takeIf { formatter.format(it) == time }?.time }.getOrNull()
        val lat = latitude.replace(',', '.').toDoubleOrNull(); val lon = longitude.replace(',', '.').toDoubleOrNull()
        when {
            time.isNotBlank() && parsedDate == null -> error = "Tarihi örnekteki gibi yaz: 2026-09-06 14:30"
            (latitude.isNotBlank() || longitude.isNotBlank()) && (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) -> error = "Geçerli enlem ve boylamı birlikte gir."
            else -> onSave(photo.copy(takenAt = parsedDate, latitude = lat, longitude = lon, caption = caption.trim()))
        }
    }) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Olduğu gibi bırak") } })
}
