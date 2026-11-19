@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    val originalTime = photo.takenAt?.let { formatter.format(Date(it)) }.orEmpty()
    var time by remember(photo.id) { mutableStateOf(originalTime) }
    var caption by remember(photo.id) { mutableStateOf(photo.caption) }
    var latitude by remember(photo.id) { mutableStateOf(photo.latitude?.toString().orEmpty()) }
    var longitude by remember(photo.id) { mutableStateOf(photo.longitude?.toString().orEmpty()) }
    var error by remember(photo.id) { mutableStateOf<String?>(null) }
    val working = { listOf(time, caption, latitude, longitude) }
    val baseline = listOf(originalTime, photo.caption, photo.latitude?.toString().orEmpty(), photo.longitude?.toString().orEmpty())
    val back = rememberUiEditExit("photo-${photo.id}", working(), baseline, currentWorking = working,
        discardUnsaved = { time = originalTime; caption = photo.caption; latitude = photo.latitude?.toString().orEmpty(); longitude = photo.longitude?.toString().orEmpty() }, exit = onDismiss)
    val enabled = !operationBusy()
    AlertDialog(onDismissRequest = back, title = { Text("Fotoğraf bilgileri") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DiaryPhotoImage(photo, description = "Fotoğraf önizlemesi", modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Fit)
            Text("Bu alanlar İz’deki kaydı değiştirir; özgün fotoğraf korunur. Paylaşım ve galeri kopyalarından dosyaya ekli konum ve çekim bilgileri kaldırılır.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            OutlinedTextField(caption, { if (!operationBusy()) caption = it }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("Açıklama") })
            Text("Çekim bilgileri", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(time, { if (!operationBusy()) time = it }, Modifier.fillMaxWidth(), enabled = enabled,
                label = { Text("Çekim zamanı") },
                supportingText = { Text("2026-09-06 14:30 · Telefonun saat dilimi") }, singleLine = true)
            OutlinedTextField(latitude, { if (!operationBusy()) latitude = it }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("Enlem (isteğe bağlı)") }, singleLine = true)
            OutlinedTextField(longitude, { if (!operationBusy()) longitude = it }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("Boylam (isteğe bağlı)") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(enabled = enabled, onClick = {
        val parsedDate = when {
            time.isBlank() -> null
            time == originalTime -> photo.takenAt
            else -> runCatching { formatter.parse(time)?.takeIf { formatter.format(it) == time }?.time }.getOrNull()
        }
        val lat = latitude.replace(',', '.').toDoubleOrNull(); val lon = longitude.replace(',', '.').toDoubleOrNull()
        when {
            time.isNotBlank() && parsedDate == null -> error = "Tarihi örnekteki gibi yaz: 2026-09-06 14:30"
            (latitude.isNotBlank() || longitude.isNotBlank()) && (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) -> error = "Geçerli enlem ve boylamı birlikte gir."
            else -> onSave(photo.copy(takenAt = parsedDate, latitude = lat, longitude = lon, caption = caption.trim()))
        }
    }) { Text("Kaydet") } }, dismissButton = {
        TextButton(onClick = onDismiss, enabled = enabled) { Text("Olduğu gibi bırak") }
    })
}

@Composable
internal fun DiaryPhotoGallery(
    photos: List<Photo>,
    onDelete: (Photo) -> Unit,
    onEdit: (Photo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Fotoğraflar", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { index, photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val label = photo.caption.ifBlank { "Fotoğraf ${index + 1}" }
                    DiaryPhotoImage(photo, description = label, modifier = Modifier.size(96.dp)
                        .testTag("diary-photo-edit-${photo.id}")
                        .clickable(onClickLabel = "Fotoğraf bilgilerini düzenle") { onEdit(photo) })
                    IconButton(onClick = { onDelete(photo) }, modifier = Modifier.testTag("diary-photo-delete-${photo.id}")) {
                        Icon(Icons.Outlined.DeleteOutline, "$label: fotoğrafı sil")
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiaryPhotoImage(
    photo: Photo,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val files = LocalContext.current.filesDir
    AsyncImage(
        model = remember(files, photo.relativePath) { File(files, photo.relativePath) },
        contentDescription = description,
        modifier = modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentScale = contentScale,
    )
}
