@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.iz.navigation.ui

import androidx.compose.foundation.clickable
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
import java.io.File

@Composable
internal fun PhotoShareSheet(photos: List<Photo>, onDismiss: () -> Unit, onShare: (List<Photo>) -> Unit, onGallery: (List<Photo>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fotoğraflarını paylaş", style = MaterialTheme.typography.headlineSmall)
            Text("Paylaşacağın veya galeriye kaydedeceğin fotoğrafları seç.", color = Muted)
            photos.forEach { photo -> Row(Modifier.fillMaxWidth().clickable { selected = if (photo.id in selected) selected - photo.id else selected + photo.id }) {
                Checkbox(photo.id in selected, { checked -> selected = if (checked) selected + photo.id else selected - photo.id })
                AsyncImage(File(context.filesDir, photo.relativePath), photo.caption.ifBlank { "Günlük fotoğrafı" }, Modifier.size(72.dp))
            } }
            val chosen = photos.filter { it.id in selected }
            Button(onClick = { onShare(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Seçilen fotoğrafları paylaş") }
            OutlinedButton(onClick = { onGallery(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Galeriye kaydet") }
        }
    }
}
